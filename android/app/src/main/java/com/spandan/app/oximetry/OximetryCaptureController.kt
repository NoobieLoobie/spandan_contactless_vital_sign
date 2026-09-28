package com.spandan.app.oximetry

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import com.spandan.app.oximetry.OximetryMath.CaptureBranch
import com.spandan.app.oximetry.OximetryMath.ExposureSetting
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * [Segment 34] Oximetry-grade capture: after a short auto-exposure pre-roll,
 * freezes the front camera's exposure, sensitivity and colour gains and (where
 * the hardware allows) forces a LINEAR tone curve and identity colour matrix,
 * so a change in skin reflectance maps to a proportional, fixed-gain change in
 * all three channels (Xuan et al. 2023, Front. Digit. Health 5:1301019).
 *
 * Gated by MainActivity's `useOximetryCapture` (default false). When it is
 * false this class only READS each frame's [TotalCaptureResult] (so the
 * calibration CSV still logs exposure/ISO/AE state in auto mode) and never
 * sets a capture-request option -- the camera behaves exactly as before.
 *
 * Branches (decided by [OximetryMath.decideCapturePlan] from the
 * [CameraCapabilityProbe] result):
 *  - A_MANUAL: AE/AWB off, frozen SENSOR_EXPOSURE_TIME/SENSOR_SENSITIVITY/
 *    COLOR_CORRECTION_GAINS, identity COLOR_CORRECTION_TRANSFORM,
 *    TONEMAP_MODE_CONTRAST_CURVE (0,0)-(1,1) on R/G/B, AF/OIS/video-stab off
 *    where offered, fixed SENSOR_FRAME_DURATION. Then up to
 *    [MAX_METERING_ITERATIONS] linear correction steps to bring the ROI's
 *    brightest channel to 40-60 % of full scale ([OximetryMath.correctExposure],
 *    with 50 Hz anti-flicker exposure quantisation since AE antibanding is off).
 *  - B_LOCK: CONTROL_AE_LOCK/CONTROL_AWB_LOCK after convergence; linear curve
 *    only if CONTRAST_CURVE is advertised -- otherwise the tone curve stays
 *    non-linear and that is logged, not faked.
 *
 * Everything the hardware REALLY applied is read back from every
 * [TotalCaptureResult] and logged (tag `SPANDAN_OXI`, throttled to 1 Hz,
 * plus one line per state change and any post-lock drift).
 *
 * Threading: [captureCallback] runs on CameraX's camera thread; every state
 * transition and every capture-request change runs on the main thread.
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
class OximetryCaptureController {

    enum class LockState { AUTO, PREROLL, METERING, LOCKED, UNSUPPORTED }

    /** What the camera HAL reported for one frame. */
    data class FrameMeta(
        val sensorTimestampNs: Long,
        val exposureNs: Long?,
        val iso: Int?,
        val frameDurationNs: Long?,
        val aeMode: Int?,
        val aeState: Int?,
        val aeLock: Boolean?,
        val awbMode: Int?,
        val awbState: Int?,
        val awbLock: Boolean?,
        val afMode: Int?,
        val tonemapMode: Int?,
        val colorCorrectionMode: Int?,
        val gains: RggbChannelVector?,
        val focusDistance: Float?,
        val antibandingMode: Int?,
        val aeCompensation: Int?,
        /** ISP digital gain applied after RAW, x100 (100 = none). The
         *  sensor-side ISO alone does not describe total gain when this moves. */
        val postRawBoost: Int?
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    var capabilities: CameraCapabilityProbe.Capabilities? = null
        private set

    @Volatile var lockState: LockState = LockState.AUTO
        private set

    /** Exposure/ISO last REQUESTED in Branch A (null otherwise). */
    @Volatile var requestedSetting: ExposureSetting? = null
        private set

    /** Branch A: brightest-channel ROI fraction measured at the final lock. */
    @Volatile var lockedBrightestFraction: Double? = null
        private set

    /** Branch B: AE exposure compensation (steps) last requested. */
    @Volatile var requestedAeCompensation: Int = 0
        private set

    /** Free-text note on how the lock went (e.g. "manual exposure not honoured"). */
    @Volatile var lockNote: String = ""
        private set

    @Volatile var latestMeta: FrameMeta? = null
        private set

    /** [Segment 35 Phase 2 item 5] Called (main thread) every time this
     *  controller starts a RE-lock (brightness drift, or a face reacquired
     *  after loss) -- MainActivity wires this to clear the HR/SpO2 signal
     *  buffer, since an exposure step hits every channel at once and the
     *  window must not mix pre-relock and post-relock samples. Never called
     *  for the FIRST lock (onCameraBound's own preroll->metering->lock
     *  path) -- only for a re-lock of an already-LOCKED session. */
    var onRelock: (() -> Unit)? = null

    private val recentMeta = ArrayDeque<FrameMeta>()
    private var control: Camera2CameraControl? = null
    private var enabled = false
    private var frozenGains: RggbChannelVector? = null
    private var frozenFocusDistance: Float? = null
    private var prerollStartMs = 0L
    private var meteringIteration = 0
    private var meteringPhaseStartMs = 0L
    private val meteringFractions = ArrayList<Double>()
    private val lastVerboseLogMs = AtomicLong(0L)
    private var lockedAtMeta: FrameMeta? = null
    private var driftWarned = false

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            record(result)
        }
    }

    /**
     * Call on the use-case BUILDERS before `build()`. Always attaches the
     * read-only capture callback; only when [enable] sets the fixed target
     * fps range on both use cases so pre-roll AE converges under the same
     * frame-rate constraint the lock will use.
     */
    fun configure(
        analysisBuilder: ImageAnalysis.Builder,
        previewBuilder: Preview.Builder,
        caps: CameraCapabilityProbe.Capabilities?,
        enable: Boolean
    ) {
        capabilities = caps
        Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(captureCallback)
        val fps = caps?.chosenFpsRange()
        if (enable && fps != null) {
            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        }
    }

    /** Call on the main thread right after `bindToLifecycle`. */
    fun onCameraBound(camera: Camera, enable: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        enabled = enable
        requestedSetting = null
        lockedBrightestFraction = null
        lockedAtMeta = null
        driftWarned = false
        lockNote = ""
        synchronized(recentMeta) { recentMeta.clear() }

        val ctl = Camera2CameraControl.from(camera.cameraControl)
        control = ctl
        // Camera2CameraControl options survive unbind/rebind on the same
        // camera, so a previous locked session's options must be cleared
        // explicitly -- otherwise switching the mode OFF would leave the
        // camera locked.
        ctl.clearCaptureRequestOptions()

        if (!enable) {
            setState(LockState.AUTO, "oximetry capture off (fully automatic camera)")
            return
        }
        val caps = capabilities
        if (caps == null || caps.plan.branch == CaptureBranch.NONE) {
            setState(LockState.UNSUPPORTED, "no manual control and no AE/AWB lock on this camera; staying automatic")
            return
        }
        prerollStartMs = SystemClock.elapsedRealtime()
        requestedAeCompensation = 0
        if (caps.plan.branch == CaptureBranch.B_LOCK) {
            // Branch B meters through AE itself, so the linear tone curve
            // must already be in place while AE converges (the ROI level we
            // measure is then the level we will lock at).
            applyBranchB(caps, lock = false)
        }
        setState(LockState.PREROLL, "auto pre-roll ${PREROLL_MS}ms, branch=${caps.plan.branch} linearCurve=${caps.plan.linearToneCurve}")
        mainHandler.postDelayed(::finishPreroll, PREROLL_MS)
    }

    /** [Segment 35 Phase 2 item 5] Call (main thread) when the face analyzer
     *  reports a real re-acquisition after a lost-face gap (see
     *  SignalBuffer.isReacquiring, which is what MainActivity's caller
     *  actually watches). A no-op unless currently LOCKED -- losing and
     *  regaining the face during PREROLL/METERING/AUTO/UNSUPPORTED needs no
     *  re-lock, there is no existing lock to have drifted. */
    fun onFaceReacquired() {
        if (lockState == LockState.LOCKED) startRelock("face reacquired after a lost-face gap")
    }

    /** Feed each ROI sample (main thread). Drift-checks while LOCKED
     *  ([Segment 35 Phase 2 item 5]); otherwise used only while METERING. */
    fun onRoiSample(sensorTimestampNs: Long, r: Double, g: Double, b: Double) {
        if (lockState == LockState.LOCKED) {
            val locked = lockedBrightestFraction
            if (locked != null && locked > 0.0) {
                val current = OximetryMath.brightestChannelFraction(r, g, b)
                if (abs(current - locked) / locked > RELOCK_DRIFT_FRACTION) {
                    startRelock("ROI brightness drifted %.0f%% -> %.0f%% of full scale (>%.0f%% from locked baseline)"
                        .format(locked * 100, current * 100, RELOCK_DRIFT_FRACTION * 100))
                }
            }
            return
        }
        if (lockState != LockState.METERING) return
        if (capabilities?.plan?.branch == CaptureBranch.B_LOCK) {
            onRoiSampleBranchB(sensorTimestampNs, r, g, b)
            return
        }
        val requested = requestedSetting ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - meteringPhaseStartMs > METERING_TIMEOUT_MS) {
            finishLock("metering timed out (no face, or requested exposure never reported back) -- locked at the last request")
            return
        }
        if (now - meteringPhaseStartMs < METERING_SETTLE_MS) return
        // Only count frames the HAL says were actually taken with the
        // requested settings (a few in-flight frames still carry the old ones).
        val meta = metaFor(sensorTimestampNs) ?: return
        if (!matches(meta, requested)) return

        meteringFractions.add(OximetryMath.brightestChannelFraction(r, g, b))
        if (meteringFractions.size < METERING_SAMPLES) return

        val fraction = meteringFractions.sorted()[meteringFractions.size / 2]
        meteringFractions.clear()
        Log.i(TAG, "METER iter=$meteringIteration exp=${requested.exposureNs} iso=${requested.iso} brightestFraction=%.3f".format(fraction))

        if (OximetryMath.isWithinTarget(fraction) || meteringIteration >= MAX_METERING_ITERATIONS) {
            lockedBrightestFraction = fraction
            finishLock(
                if (OximetryMath.isWithinTarget(fraction)) "ROI brightest channel at %.0f%% of full scale".format(fraction * 100)
                else "target not reached after $MAX_METERING_ITERATIONS steps (%.0f%% of full scale; sensor range limit?)".format(fraction * 100)
            )
            return
        }
        val caps = capabilities ?: return
        val next = OximetryMath.correctExposure(
            current = requested,
            measuredFraction = fraction,
            exposureRangeNs = caps.exposureRangeNs!!.lower..caps.exposureRangeNs.upper,
            isoRange = caps.sensitivityRange!!.lower..caps.sensitivityRange.upper,
            maxExposureNs = frameDurationNs()
        )
        meteringIteration++
        if (next == requested) {
            lockedBrightestFraction = fraction
            finishLock("exposure at sensor limit; ROI brightest channel %.0f%% of full scale".format(fraction * 100))
            return
        }
        applyManual(next)
        meteringPhaseStartMs = SystemClock.elapsedRealtime()
    }

    /** Branch B metering: AE keeps running (fixed fps, linear curve) and
     *  exposure compensation is stepped until the ROI's brightest channel is
     *  at 40-60 % of full scale, then AE/AWB are locked. */
    private fun onRoiSampleBranchB(sensorTimestampNs: Long, r: Double, g: Double, b: Double) {
        val caps = capabilities ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - meteringPhaseStartMs > METERING_TIMEOUT_MS) {
            applyBranchB(caps, lock = true)
            finishLock("metering timed out (no face, or AE never converged) -- AE/AWB locked at comp=$requestedAeCompensation")
            return
        }
        if (now - meteringPhaseStartMs < METERING_SETTLE_MS) return
        val meta = metaFor(sensorTimestampNs) ?: return
        if (meta.aeCompensation != requestedAeCompensation) return
        if (meta.aeState != CaptureResult.CONTROL_AE_STATE_CONVERGED &&
            meta.aeState != CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) return

        meteringFractions.add(OximetryMath.brightestChannelFraction(r, g, b))
        if (meteringFractions.size < METERING_SAMPLES) return
        val fraction = meteringFractions.sorted()[meteringFractions.size / 2]
        meteringFractions.clear()
        Log.i(TAG, "METER-B iter=$meteringIteration comp=$requestedAeCompensation exp=${meta.exposureNs} iso=${meta.iso} postRawBoost=${meta.postRawBoost} " +
            "brightestFraction=%.3f".format(fraction))

        val range = caps.aeCompensationRange
        val next = if (range == null) requestedAeCompensation else OximetryMath.nextAeCompensation(
            requestedAeCompensation, fraction, caps.aeCompensationStepEv, range.lower..range.upper
        )
        if (OximetryMath.isWithinTarget(fraction) || meteringIteration >= MAX_METERING_ITERATIONS || next == requestedAeCompensation) {
            lockedBrightestFraction = fraction
            applyBranchB(caps, lock = true)
            val curve = if (caps.plan.linearToneCurve) "linear tone curve requested" else "tone curve NON-LINEAR (CONTRAST_CURVE unavailable)"
            finishLock(
                (if (OximetryMath.isWithinTarget(fraction)) "AE/AWB locked, ROI brightest channel %.0f%% of full scale".format(fraction * 100)
                else "AE/AWB locked OFF-TARGET at %.0f%% of full scale (comp limit or iterations)".format(fraction * 100)) +
                    " comp=$requestedAeCompensation; $curve"
            )
            return
        }
        meteringIteration++
        requestedAeCompensation = next
        applyBranchB(caps, lock = false)
        meteringPhaseStartMs = SystemClock.elapsedRealtime()
    }

    /** Most recent HAL metadata for [sensorTimestampNs] (exact match, else
     *  the nearest within 5 ms), or null. */
    fun metaFor(sensorTimestampNs: Long): FrameMeta? = synchronized(recentMeta) {
        recentMeta.lastOrNull { it.sensorTimestampNs == sensorTimestampNs }
            ?: recentMeta.minByOrNull { abs(it.sensorTimestampNs - sensorTimestampNs) }
                ?.takeIf { abs(it.sensorTimestampNs - sensorTimestampNs) <= 5_000_000L }
    }

    /** One-line human summary for the developer panel. */
    fun statusLine(): String {
        val caps = capabilities
        val m = latestMeta
        val branch = caps?.plan?.branch?.name ?: "?"
        val curve = CameraCapabilityProbe.toneMapName(m?.tonemapMode)
        val exp = m?.exposureNs?.let { "%.2fms".format(it / 1e6) } ?: "?"
        val frac = lockedBrightestFraction?.let { " roi=%.0f%%".format(it * 100) } ?: ""
        return "${lockState.name} branch=$branch exp=$exp iso=${m?.iso ?: "?"} comp=${m?.aeCompensation ?: "?"} ae=${aeStateName(m?.aeState)} " +
            "awb=${awbStateName(m?.awbState)} tone=$curve$frac"
    }

    fun frameDurationNs(): Long {
        val fps = capabilities?.chosenFpsRange()?.upper ?: 30
        return 1_000_000_000L / fps
    }

    // ------------------------------------------------------------ internals

    private fun finishPreroll() {
        val caps = capabilities ?: return
        val m = latestMeta
        val waited = SystemClock.elapsedRealtime() - prerollStartMs
        val converged = m != null && m.aeState != CaptureResult.CONTROL_AE_STATE_SEARCHING &&
            m.awbState != CaptureResult.CONTROL_AWB_STATE_SEARCHING
        if (!converged && waited < PREROLL_TIMEOUT_MS) {
            mainHandler.postDelayed(::finishPreroll, 250)
            return
        }
        if (m == null) {
            setState(LockState.UNSUPPORTED, "no capture results received during pre-roll; staying automatic")
            return
        }
        Log.i(TAG, "PREROLL done after ${waited}ms converged=$converged: exp=${m.exposureNs} iso=${m.iso} gains=${gainsText(m.gains)} " +
            "ae=${aeStateName(m.aeState)} awb=${awbStateName(m.awbState)} focus=${m.focusDistance}")

        when (caps.plan.branch) {
            CaptureBranch.A_MANUAL -> {
                frozenGains = m.gains
                frozenFocusDistance = m.focusDistance
                val exp = (m.exposureNs ?: 10_000_000L).coerceAtMost(frameDurationNs())
                val iso = m.iso ?: 400
                meteringIteration = 0
                meteringFractions.clear()
                applyManual(ExposureSetting(exp, iso))
                meteringPhaseStartMs = SystemClock.elapsedRealtime()
                setState(LockState.METERING, "manual linear capture applied; metering ROI")
            }
            CaptureBranch.B_LOCK -> {
                meteringIteration = 0
                meteringFractions.clear()
                meteringPhaseStartMs = SystemClock.elapsedRealtime()
                setState(LockState.METERING, "AE running with fixed fps + " +
                    (if (caps.plan.linearToneCurve) "linear curve" else "default (non-linear) curve") + "; metering ROI via AE compensation")
            }
            CaptureBranch.NONE -> setState(LockState.UNSUPPORTED, "no lock available")
        }
    }

    private fun applyManual(s: ExposureSetting) {
        val caps = capabilities ?: return
        val b = CaptureRequestOptions.Builder()
        b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
        b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs())
        caps.chosenFpsRange()?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(OximetryMath.identityCcmRationals()))
        frozenGains?.let { b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, it) }

        val pts = OximetryMath.linearToneCurvePoints()
        b.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        b.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))

        applyFocusAndStabilisation(b, caps)
        requestedSetting = s
        control?.setCaptureRequestOptions(b.build())
        Log.i(TAG, "REQUEST branch=A exp=${s.exposureNs} iso=${s.iso} frameDur=${frameDurationNs()} gains=${gainsText(frozenGains)} " +
            "ccm=identity tonemap=CONTRAST_CURVE(0,0)-(1,1) afOff=${CameraMetadata.CONTROL_AF_MODE_OFF in caps.afModes}")
    }

    /** Branch B request: fixed fps, AE compensation, linear curve if
     *  available, AF/OIS/stab off where offered; AE_LOCK/AWB_LOCK when [lock]. */
    private fun applyBranchB(caps: CameraCapabilityProbe.Capabilities, lock: Boolean) {
        val b = CaptureRequestOptions.Builder()
        if (caps.aeLockAvailable) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
        if (caps.awbLockAvailable) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, requestedAeCompensation)
        caps.chosenFpsRange()?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (caps.plan.linearToneCurve) {
            val pts = OximetryMath.linearToneCurvePoints()
            b.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
        }
        frozenFocusDistance = latestMeta?.focusDistance
        applyFocusAndStabilisation(b, caps)
        control?.setCaptureRequestOptions(b.build())
        Log.i(TAG, "REQUEST branch=B aeLock=${lock && caps.aeLockAvailable} awbLock=${lock && caps.awbLockAvailable} comp=$requestedAeCompensation " +
            "fps=${caps.chosenFpsRange()} linearCurve=${caps.plan.linearToneCurve}")
    }

    private fun applyFocusAndStabilisation(b: CaptureRequestOptions.Builder, caps: CameraCapabilityProbe.Capabilities) {
        if (CameraMetadata.CONTROL_AF_MODE_OFF in caps.afModes) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            val minFocus = caps.minFocusDistance ?: 0f
            val focus = frozenFocusDistance
            if (minFocus > 0f && focus != null) b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in caps.oisModes) {
            b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    }

    /** [Segment 35 Phase 2 item 5] Re-enters METERING from an existing LOCK,
     *  reusing the SAME metering state machine [onRoiSample]/
     *  [onRoiSampleBranchB] already drive to completion (which calls
     *  [finishLock] again when it settles) -- not a separate code path. Only
     *  called when [lockState] is already LOCKED (both call sites check
     *  this), so it can never race the initial preroll->metering->lock
     *  sequence in [onCameraBound]/[finishPreroll]. */
    private fun startRelock(reason: String) {
        val caps = capabilities ?: return
        Log.i(TAG, "RELOCK triggered: $reason")
        meteringIteration = 0
        meteringFractions.clear()
        meteringPhaseStartMs = SystemClock.elapsedRealtime()
        if (caps.plan.branch == CaptureBranch.B_LOCK) {
            // Must unlock AE/AWB first -- exposure/WB compensation changes
            // have no effect while CONTROL_AE_LOCK/CONTROL_AWB_LOCK are true.
            applyBranchB(caps, lock = false)
        }
        // Branch A needs no new request here: onRoiSample's existing
        // metering loop keeps correcting from `requestedSetting` (still the
        // last-applied manual exposure/ISO), the same state it already reads
        // every METERING tick.
        setState(LockState.METERING, "RE-METERING: $reason")
        onRelock?.invoke()
    }

    private fun finishLock(note: String) {
        lockNote = note
        lockedAtMeta = null // reference taken from the first frame that reports the lock in effect
        // [Segment 35 Phase 2 item 5] reset so a genuine drift after a
        // RE-lock can still be warned about -- before re-locking existed,
        // finishLock only ever ran once per session, so this reset was
        // never reachable/necessary.
        driftWarned = false
        setState(LockState.LOCKED, note)
    }

    private fun setState(state: LockState, why: String) {
        lockState = state
        Log.i(TAG, "STATE $state -- $why")
    }

    private fun matches(meta: FrameMeta, s: ExposureSetting): Boolean {
        val e = meta.exposureNs ?: return false
        val i = meta.iso ?: return false
        return abs(e - s.exposureNs) <= s.exposureNs * 0.02 + 20_000 && abs(i - s.iso) <= s.iso * 0.02 + 1
    }

    /** Camera thread(s) -- CameraX may deliver on more than one, so serialised. */
    @Synchronized
    private fun record(r: TotalCaptureResult) {
        val meta = FrameMeta(
            sensorTimestampNs = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return,
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            frameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION),
            aeMode = r.get(CaptureResult.CONTROL_AE_MODE),
            aeState = r.get(CaptureResult.CONTROL_AE_STATE),
            aeLock = r.get(CaptureResult.CONTROL_AE_LOCK),
            awbMode = r.get(CaptureResult.CONTROL_AWB_MODE),
            awbState = r.get(CaptureResult.CONTROL_AWB_STATE),
            awbLock = r.get(CaptureResult.CONTROL_AWB_LOCK),
            afMode = r.get(CaptureResult.CONTROL_AF_MODE),
            tonemapMode = r.get(CaptureResult.TONEMAP_MODE),
            colorCorrectionMode = r.get(CaptureResult.COLOR_CORRECTION_MODE),
            gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS),
            focusDistance = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
            antibandingMode = r.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
            aeCompensation = r.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
            postRawBoost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
        )
        latestMeta = meta
        synchronized(recentMeta) {
            recentMeta.addLast(meta)
            while (recentMeta.size > META_HISTORY) recentMeta.removeFirst()
        }

        val now = SystemClock.elapsedRealtime()
        val last = lastVerboseLogMs.get()
        if (now - last >= 1000 && lastVerboseLogMs.compareAndSet(last, now)) {
            Log.i(TAG, "APPLIED state=$lockState aeMode=${meta.aeMode} aeState=${aeStateName(meta.aeState)} aeLock=${meta.aeLock} " +
                "awbMode=${meta.awbMode} awbState=${awbStateName(meta.awbState)} awbLock=${meta.awbLock} " +
                "exp=${meta.exposureNs} iso=${meta.iso} frameDur=${meta.frameDurationNs} " +
                "tonemap=${CameraCapabilityProbe.toneMapName(meta.tonemapMode)} ccMode=${meta.colorCorrectionMode} " +
                "gains=${gainsText(meta.gains)} afMode=${meta.afMode} antibanding=${meta.antibandingMode} aeComp=${meta.aeCompensation} postRawBoost=${meta.postRawBoost} " +
                "curvePts=${curveSummary(r.get(CaptureResult.TONEMAP_CURVE))}")
        }
        checkDrift(meta)
    }

    /** After LOCKED, warn once if exposure/ISO/gains move -- i.e. the lock
     *  was not actually honoured by the HAL. */
    private fun checkDrift(meta: FrameMeta) {
        if (lockState != LockState.LOCKED || driftWarned) return
        val ref = lockedAtMeta
        if (ref == null) {
            // In-flight frames still carry pre-lock values; start comparing
            // only once the HAL reports the lock (or AE off) in effect.
            val inEffect = meta.aeMode == CameraMetadata.CONTROL_AE_MODE_OFF || meta.aeLock == true ||
                meta.aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            if (inEffect) {
                lockedAtMeta = meta
                Log.i(TAG, "LOCK IN EFFECT exp=${meta.exposureNs} iso=${meta.iso} postRawBoost=${meta.postRawBoost} aeState=${aeStateName(meta.aeState)} " +
                    "awbState=${awbStateName(meta.awbState)} gains=${gainsText(meta.gains)} tonemap=${CameraCapabilityProbe.toneMapName(meta.tonemapMode)}")
            }
            return
        }
        val e0 = ref.exposureNs; val e1 = meta.exposureNs
        val i0 = ref.iso; val i1 = meta.iso
        val drift = (e0 != null && e1 != null && abs(e1 - e0) > e0 * 0.02 + 20_000) ||
            (i0 != null && i1 != null && abs(i1 - i0) > i0 * 0.02 + 1) ||
            gainsDiffer(ref.gains, meta.gains)
        if (drift) {
            driftWarned = true
            Log.w(TAG, "DRIFT after lock: exp $e0->$e1 iso $i0->$i1 gains ${gainsText(ref.gains)}->${gainsText(meta.gains)} -- lock NOT fully honoured")
        }
    }

    companion object {
        private const val TAG = "SPANDAN_OXI"
        const val PREROLL_MS = 2000L
        private const val PREROLL_TIMEOUT_MS = 5000L
        private const val METERING_SETTLE_MS = 400L
        private const val METERING_SAMPLES = 12
        private const val METERING_TIMEOUT_MS = 15_000L
        private const val MAX_METERING_ITERATIONS = 4
        private const val META_HISTORY = 90

        /** [Segment 35 Phase 2 item 5] Re-lock when the ROI's brightest-
         *  channel fraction drifts by more than this much (relative) from
         *  [lockedBrightestFraction] -- per docs/Segment35_Accuracy_Research_
         *  and_Plan.md's own instruction. Not on-device re-tuned this session
         *  (no physical device available); Phase 4 should check whether 15%
         *  fires too eagerly (normal head/lighting micro-motion) or too
         *  rarely (a real lighting change not caught before HR/SpO2 visibly
         *  degrade) on a real capture. */
        const val RELOCK_DRIFT_FRACTION = 0.15

        fun aeStateName(s: Int?): String = when (s) {
            CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
            CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
            CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
            CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
            null -> "null"
            else -> "S$s"
        }

        fun awbStateName(s: Int?): String = when (s) {
            CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
            CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
            CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
            null -> "null"
            else -> "S$s"
        }

        /** >1 % change in any RGGB white-balance gain. */
        fun gainsDiffer(a: RggbChannelVector?, b: RggbChannelVector?): Boolean {
            if (a == null || b == null) return false
            fun d(x: Float, y: Float) = abs(x - y) > 0.01f * abs(x)
            return d(a.red, b.red) || d(a.greenEven, b.greenEven) || d(a.greenOdd, b.greenOdd) || d(a.blue, b.blue)
        }

        fun gainsText(g: RggbChannelVector?): String =
            if (g == null) "null" else "%.3f/%.3f/%.3f/%.3f".format(g.red, g.greenEven, g.greenOdd, g.blue)

        private fun curveSummary(c: TonemapCurve?): String {
            if (c == null) return "null"
            val n = c.getPointCount(TonemapCurve.CHANNEL_RED)
            val first = c.getPoint(TonemapCurve.CHANNEL_RED, 0)
            val last = c.getPoint(TonemapCurve.CHANNEL_RED, n - 1)
            val mid = c.getPoint(TonemapCurve.CHANNEL_RED, n / 2)
            return "n=$n first=(${first.x},${first.y}) mid=(${mid.x},${mid.y}) last=(${last.x},${last.y})"
        }
    }
}

/** Kotlin-friendly view of a fixed fps [Range] for logging/CSV. */
fun Range<Int>.asText(): String = "$lower-$upper fps"
