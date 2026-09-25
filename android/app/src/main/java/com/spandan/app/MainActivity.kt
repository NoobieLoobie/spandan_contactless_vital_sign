package com.spandan.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spandan.app.camera.CoordinateMapper
import com.spandan.app.camera.FaceAnalysisResult
import com.spandan.app.camera.FaceAnalyzer
import com.spandan.app.oximetry.CalibrationCsvFormat
import com.spandan.app.oximetry.CalibrationRecorder
import com.spandan.app.oximetry.CameraCapabilityProbe
import com.spandan.app.oximetry.OximetryCaptureController
import com.spandan.app.oximetry.OximetryCaptureController.LockState
import com.spandan.app.oximetry.OximetryMath
import com.spandan.app.oximetry.ZeroLightMeter
import com.spandan.app.oximetry.ZeroLightOffset
import com.spandan.app.oximetry.asText
import com.spandan.app.signal.DisplaySmoother
import com.spandan.app.signal.EstimatorStatus
import com.spandan.app.signal.LiveSpo2Estimator
import com.spandan.app.signal.MorphologyWaveformEstimator
import com.spandan.app.signal.RealHeartRateEstimator
import com.spandan.app.signal.RgbSample
import com.spandan.app.signal.SignalBuffer
import com.spandan.app.ui.OverlayView
import com.spandan.app.ui.WaveformView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single-screen glue: camera lifecycle, permission handling, and wiring the
 * real analysis pipeline into the UI. HR is a real, validated CHROM/POS+FFT
 * port (see signal/RealHeartRateEstimator.kt). SpO2 is a real ratio-of-ratios
 * + linear-calibration estimate (see signal/LiveSpo2Estimator.kt), added
 * purely additively alongside HR -- both estimators read the same
 * [signalBuffer] snapshot independently, neither one's class references or
 * modifies the other's.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var hrText: TextView
    private lateinit var spo2Text: TextView
    private lateinit var permissionDeniedView: View
    private lateinit var noFaceBanner: View
    private lateinit var hrStatusDot: View
    private lateinit var hrStatusLabel: TextView
    private lateinit var spo2StatusDot: View
    private lateinit var spo2StatusLabel: TextView
    private lateinit var morphologyWaveformView: WaveformView
    private lateinit var morphologyStatusDot: View
    private lateinit var morphologyStatusLabel: TextView

    private val signalBuffer = SignalBuffer(windowSeconds = SignalBuffer.WINDOW_DURATION_SECONDS)
    private val heartRateEstimator = RealHeartRateEstimator()
    private val spo2Estimator = LiveSpo2Estimator()

    // Segment 19 -- Branch 2 (waveform morphology / dicrotic notch), reading
    // the SAME signalBuffer snapshot as the two estimators above, completely
    // independently (matches Branch 1/Branch 2's deliberate MATLAB-side
    // separation -- see MorphologyWaveformEstimator's own KDoc).
    private val morphologyEstimator = MorphologyWaveformEstimator()

    // Segment 16 Task 1 -- DISPLAY-LEVEL smoothing only (see DisplaySmoother's
    // own KDoc for why this is not a re-introduction of the RAKF/Kalman
    // approach MATLAB already rejected). ON by default as of 2026-09-14,
    // after a real on-device A/B capture showed a genuine reduction in
    // tick-to-tick jitter with no accuracy cost -- see
    // ENABLE_HR_DISPLAY_SMOOTHING_DEFAULT's own KDoc below and
    // docs/Segment16_Task1_HR_Stability.md for the full result/caveats.
    private val hrDisplaySmoother = DisplaySmoother(mode = DisplaySmoother.Mode.ROLLING_MEDIAN)
    private val enableHrDisplaySmoothing = ENABLE_HR_DISPLAY_SMOOTHING_DEFAULT

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    // Segment 16 Task 3 -- when a face was last seen, for the "No face
    // detected" banner's debounce (see handleAnalysisResult/refreshUi
    // below). A single missed frame is normal (FaceAnalyzer's own
    // every-Nth-frame detection-skip design, plus ordinary detector noise)
    // and must NOT flash the banner -- only a SUSTAINED gap should.
    private var lastFaceSeenMs: Long = 0L

    // Tracks the permission state as of the last time we actually acted on it
    // (onCreate or a resume), so onResume can tell "still the same state" apart
    // from "changed while backgrounded" -- see onResume() below.
    private var permissionGrantedLastKnown = false

    // [Segment 34] Oximetry-grade capture + calibration recorder. OFF by
    // default (USE_OXIMETRY_CAPTURE_DEFAULT); switchable at runtime from the
    // developer panel (long-press the vitals card). When off, the camera is
    // configured exactly as before -- the controller only reads capture
    // results. The displayed SpO2 formula is NOT changed by any of this.
    private var useOximetryCapture = USE_OXIMETRY_CAPTURE_DEFAULT
    private val oximetry = OximetryCaptureController()
    private var cameraCapabilities: CameraCapabilityProbe.Capabilities? = null
    private val zeroLightMeter = ZeroLightMeter()
    private lateinit var recorder: CalibrationRecorder
    private var recordStartSensorNs = 0L
    private var recordStartElapsedNs = 0L
    private var lastFrameSensorNs = 0L
    private var lastFrameReceiptElapsedNs = 0L
    private var lastRotatedWidth = 0
    private var lastRotatedHeight = 0
    private var lastHrBpm: Double? = null
    private var lastSpo2: Double? = null
    private lateinit var calibrationPanel: View
    private lateinit var elapsedText: TextView
    private lateinit var oximetryStatusText: TextView
    private lateinit var oximetrySwitch: SwitchCompat
    private lateinit var lightingInput: EditText
    private lateinit var recordButton: Button
    private var zeroLightNote: String = ""

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGrantedLastKnown = granted
            if (granted) startCamera() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        hrText = findViewById(R.id.hrText)
        spo2Text = findViewById(R.id.spo2Text)
        permissionDeniedView = findViewById(R.id.permissionDeniedView)
        noFaceBanner = findViewById(R.id.noFaceBanner)
        hrStatusDot = findViewById(R.id.hrStatusDot)
        hrStatusLabel = findViewById(R.id.hrStatusLabel)
        spo2StatusDot = findViewById(R.id.spo2StatusDot)
        spo2StatusLabel = findViewById(R.id.spo2StatusLabel)
        morphologyWaveformView = findViewById(R.id.morphologyWaveformView)
        morphologyStatusDot = findViewById(R.id.morphologyStatusDot)
        morphologyStatusLabel = findViewById(R.id.morphologyStatusLabel)
        setUpCalibrationPanel()

        // App targets SDK 35, where edge-to-edge is enforced -- content draws
        // behind system bars by default. Without this, the bottom HR/SpO2
        // status chips get clipped by the device's nav bar (found via
        // on-device screenshot during an earlier task's verification, same
        // "check the real device, don't assume" discipline as the
        // CoordinateMapper bugs).
        val rootLayout = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<Button>(R.id.grantPermissionButton).setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        permissionGrantedLastKnown = hasCameraPermission()
        if (permissionGrantedLastKnown) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        startUiRefreshLoop()
    }

    /**
     * Re-checks camera permission every time the activity resumes, not just at
     * onCreate. Without this, a user who denies the permission, backgrounds the
     * app, grants it via system Settings, and returns would stay stuck on the
     * "permission denied" screen -- onCreate's one-time check never re-runs on
     * a plain resume (only on activity re-creation). Compares against
     * [permissionGrantedLastKnown] rather than unconditionally acting every
     * resume, so this is a no-op on the very first resume right after onCreate
     * (before the initial permission dialog has even been answered) and on
     * every ordinary resume where nothing changed.
     */
    override fun onResume() {
        super.onResume()
        val granted = hasCameraPermission()
        if (granted == permissionGrantedLastKnown) return
        permissionGrantedLastKnown = granted

        if (granted) {
            // Denied earlier, granted since (e.g. via system Settings) while backgrounded.
            startCamera()
        } else {
            // Was granted, revoked since (e.g. via system Settings) while backgrounded.
            cameraProvider?.unbindAll()
            cameraProvider = null
            showPermissionDenied()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionDenied() {
        // Graceful denial state -- no crash, just an explanation + a retry button.
        permissionDeniedView.visibility = View.VISIBLE
    }

    private fun startCamera() {
        permissionDeniedView.visibility = View.GONE

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class, ExperimentalCamera2Interop::class)
    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        // [Segment 34] Task 1 capability probe -- logged once (SPANDAN_CAPS).
        if (cameraCapabilities == null) {
            cameraCapabilities = try {
                CameraCapabilityProbe.frontCameraId(this)?.let { CameraCapabilityProbe.probe(this, it) }
            } catch (e: Exception) {
                Log.e(TAG, "Camera capability probe failed", e)
                null
            }
        }

        val previewBuilder = Preview.Builder()
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        // [Segment 34] read-only capture-result callback always; capture
        // request options only when useOximetryCapture is on.
        oximetry.configure(analysisBuilder, previewBuilder, cameraCapabilities, useOximetryCapture)

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = analysisBuilder.build()

        val analyzer = FaceAnalyzer { result ->
            // Analyzer callback runs on analysisExecutor; hop back to the
            // main thread before touching any views.
            uiHandler.post { handleAnalysisResult(result) }
        }
        // [Segment 34] zeroLightMeter.offer is a no-op unless the developer
        // zero-light measurement is running.
        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            zeroLightMeter.offer(imageProxy)
            analyzer.analyze(imageProxy)
        }

        try {
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
            val boundId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            if (boundId != cameraCapabilities?.cameraId) {
                Log.w(TAG, "Bound camera $boundId differs from probed ${cameraCapabilities?.cameraId}; probing it (takes effect on next bind)")
                cameraCapabilities = CameraCapabilityProbe.probe(this, boundId)
            }
            oximetry.onCameraBound(camera, useOximetryCapture)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind CameraX use cases", e)
        }
    }

    private fun handleAnalysisResult(result: FaceAnalysisResult) {
        when (result) {
            is FaceAnalysisResult.NoFace -> overlayView.update(null, null)

            is FaceAnalysisResult.FaceDetected -> {
                lastFaceSeenMs = System.currentTimeMillis()

                val viewWidth = previewView.width
                val viewHeight = previewView.height

                val faceView = CoordinateMapper.rotatedRectToViewRect(
                    result.faceBoxRotated, result.rotatedImageWidth, result.rotatedImageHeight,
                    viewWidth, viewHeight, isFrontCamera = true
                )
                val roiView = CoordinateMapper.rotatedRectToViewRect(
                    result.roiBoxRotated, result.rotatedImageWidth, result.rotatedImageHeight,
                    viewWidth, viewHeight, isFrontCamera = true
                )
                overlayView.update(faceView, roiView)

                result.rgbSample?.let { signalBuffer.add(it) }

                // [Segment 34] exposure metering + calibration CSV row.
                lastFrameSensorNs = result.sensorTimestampNs
                lastFrameReceiptElapsedNs = SystemClock.elapsedRealtimeNanos()
                lastRotatedWidth = result.rotatedImageWidth
                lastRotatedHeight = result.rotatedImageHeight
                result.rgbSample?.let { s ->
                    oximetry.onRoiSample(result.sensorTimestampNs, s.red.toDouble(), s.green.toDouble(), s.blue.toDouble())
                    if (recorder.isRecording) writeCalibrationRow(result, s)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // [Segment 34] developer calibration panel + recorder
    // ---------------------------------------------------------------------

    private fun setUpCalibrationPanel() {
        recorder = CalibrationRecorder(getExternalFilesDir(null) ?: filesDir)
        calibrationPanel = findViewById(R.id.calibrationPanel)
        elapsedText = findViewById(R.id.elapsedText)
        oximetryStatusText = findViewById(R.id.oximetryStatusText)
        oximetrySwitch = findViewById(R.id.oximetrySwitch)
        lightingInput = findViewById(R.id.lightingInput)
        recordButton = findViewById(R.id.recordButton)

        findViewById<View>(R.id.vitalsCard).setOnLongClickListener {
            calibrationPanel.visibility = if (calibrationPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        oximetrySwitch.isChecked = useOximetryCapture
        oximetrySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == useOximetryCapture) return@setOnCheckedChangeListener
            useOximetryCapture = checked
            // An exposure/tone-curve change is a step in every channel; clear
            // the window so HR/SpO2/morphology restart on consistent data.
            signalBuffer.clear()
            hrDisplaySmoother.reset()
            bindUseCases()
        }
        recordButton.setOnClickListener { if (recorder.isRecording) stopRecording() else startRecording() }
        findViewById<Button>(R.id.holdStartButton).setOnClickListener { markEvent(CalibrationCsvFormat.Event.BREATH_HOLD_START) }
        findViewById<Button>(R.id.holdEndButton).setOnClickListener { markEvent(CalibrationCsvFormat.Event.BREATH_HOLD_END) }
        findViewById<Button>(R.id.noteButton).setOnClickListener { markEvent(CalibrationCsvFormat.Event.NOTE) }
        findViewById<Button>(R.id.zeroLightButton).setOnClickListener { runZeroLightMeasurement() }
    }

    /** "Now" on the same clock as the frame rows' sensor timestamps: the
     *  elapsedRealtime clock directly when the sensor timestamp source is
     *  REALTIME, otherwise extrapolated from the last frame's receipt. */
    private fun nowOnSensorClockNs(): Long {
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        if (cameraCapabilities?.timestampSourceRealtime == true || lastFrameSensorNs == 0L) return nowElapsed
        return lastFrameSensorNs + (nowElapsed - lastFrameReceiptElapsedNs)
    }

    private fun startRecording() {
        if (useOximetryCapture && oximetry.lockState != LockState.LOCKED) {
            Toast.makeText(this, "Camera not locked yet (${oximetry.lockState}) - recording anyway; see lock_state column", Toast.LENGTH_LONG).show()
        }
        recordStartSensorNs = nowOnSensorClockNs()
        recordStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        val file = recorder.start(calibrationHeader())
        recordButton.text = getString(R.string.cal_record_stop)
        oximetrySwitch.isEnabled = false
        lightingInput.isEnabled = false
        startElapsedTicker()
        Toast.makeText(this, "Recording to ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        flushPendingRows(force = true)
        recorder.stop()
        recordButton.text = getString(R.string.cal_record_start)
        oximetrySwitch.isEnabled = true
        lightingInput.isEnabled = true
        Toast.makeText(this, "Saved ${recorder.file?.name}", Toast.LENGTH_SHORT).show()
    }

    private fun markEvent(event: CalibrationCsvFormat.Event) {
        if (!recorder.isRecording) {
            Toast.makeText(this, "Not recording", Toast.LENGTH_SHORT).show()
            return
        }
        recorder.writeEvent(nowOnSensorClockNs(), event)
        Toast.makeText(this, "${event.label} @ ${elapsedText.text}", Toast.LENGTH_SHORT).show()
    }

    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (!recorder.isRecording) return
            flushPendingRows(force = false)
            val ms = (SystemClock.elapsedRealtimeNanos() - recordStartElapsedNs) / 1_000_000L
            elapsedText.text = String.format(Locale.US, "%02d:%02d.%d", ms / 60_000, (ms / 1000) % 60, (ms / 100) % 10)
            uiHandler.postDelayed(this, 100L)
        }
    }

    private fun startElapsedTicker() {
        uiHandler.removeCallbacks(elapsedTicker)
        uiHandler.post(elapsedTicker)
    }

    /** Rows waiting for their frame's TotalCaptureResult, oldest first. On
     *  skipped-detection frames the ROI sample can reach the main thread
     *  before the camera delivers that frame's capture result (measured on
     *  the A35: 21-32 % of rows had no metadata when written immediately), so
     *  each row is held up to [ROW_META_WAIT_MS], in order, and written with
     *  whatever metadata exists by then. */
    private val pendingRows = ArrayDeque<Pair<CalibrationCsvFormat.FrameRow, Long>>()

    private fun writeCalibrationRow(result: FaceAnalysisResult.FaceDetected, s: RgbSample) {
        val roi = result.roiBoxRotated
        val row = CalibrationCsvFormat.FrameRow(
            timestampNs = result.sensorTimestampNs,
            rMean = s.red.toDouble(), gMean = s.green.toDouble(), bMean = s.blue.toDouble(),
            clippedPx = s.clippedPixels,
            roiLeft = roi.left, roiTop = roi.top, roiRight = roi.right, roiBottom = roi.bottom,
            exposureNs = null, iso = null, aeState = null, awbState = null,
            hrBpm = lastHrBpm, spo2Displayed = lastSpo2,
            piRed = spo2Estimator.lastPerfusionIndexRed, piBlue = spo2Estimator.lastPerfusionIndexBlue,
            sampledPx = s.sampledPixels,
            elapsedMs = (result.sensorTimestampNs - recordStartSensorNs) / 1_000_000L,
            lockState = if (useOximetryCapture) oximetry.lockState.name else "AUTO",
            tonemapMode = null
        )
        pendingRows.addLast(row to SystemClock.elapsedRealtime())
        flushPendingRows(force = false)
    }

    private fun flushPendingRows(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        while (pendingRows.isNotEmpty()) {
            val (row, queuedAt) = pendingRows.first()
            val meta = oximetry.metaFor(row.timestampNs)
            if (meta == null && !force && now - queuedAt < ROW_META_WAIT_MS) return
            pendingRows.removeFirst()
            recorder.writeFrame(
                row.copy(
                    exposureNs = meta?.exposureNs, iso = meta?.iso,
                    aeState = meta?.aeState, awbState = meta?.awbState,
                    tonemapMode = meta?.tonemapMode
                )
            )
        }
    }

    private fun calibrationHeader(): List<Pair<String, String>> {
        val caps = cameraCapabilities
        val meta = oximetry.latestMeta
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        val activeMode = if (!useOximetryCapture) "AUTO (oximetry capture off)" else caps?.plan?.branch?.name ?: "unknown"
        val linearActive = useOximetryCapture && caps?.plan?.linearToneCurve == true
        val isBranchA = useOximetryCapture && caps?.plan?.branch == OximetryMath.CaptureBranch.A_MANUAL
        return listOf(
            "spandan_calibration_csv" to "schema_version=${CalibrationCsvFormat.SCHEMA_VERSION}",
            "app_version" to versionName,
            "recording_start_wallclock" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            "recording_start_timestamp_ns" to recordStartSensorNs.toString(),
            "timestamp_source" to (if (caps?.timestampSourceRealtime == true) "REALTIME (elapsedRealtimeNanos)" else "UNKNOWN (event rows extrapolated from last frame)"),
            "phone_manufacturer" to Build.MANUFACTURER,
            "phone_model" to Build.MODEL,
            "phone_device" to Build.DEVICE,
            "android_release" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
            "camera_id" to (caps?.cameraId ?: "?"),
            "camera_hardware_level" to (caps?.hardwareLevel ?: "?"),
            "camera_capability_branch" to (caps?.plan?.branch?.name ?: "?"),
            "active_capture_mode" to activeMode,
            "linear_tone_curve_active" to linearActive.toString(),
            "lock_state_at_start" to (if (useOximetryCapture) oximetry.lockState.name else "AUTO"),
            "lock_note" to oximetry.lockNote,
            "requested_exposure_ns" to (oximetry.requestedSetting?.exposureNs?.toString() ?: ""),
            "requested_iso" to (oximetry.requestedSetting?.iso?.toString() ?: ""),
            "requested_ae_compensation" to (if (useOximetryCapture) "${oximetry.requestedAeCompensation} steps x ${caps?.aeCompensationStepEv} EV" else ""),
            "applied_exposure_ns_at_start" to (meta?.exposureNs?.toString() ?: ""),
            "applied_iso_at_start" to (meta?.iso?.toString() ?: ""),
            "applied_color_gains_rggb_at_start" to OximetryCaptureController.gainsText(meta?.gains),
            "applied_tonemap_mode_at_start" to CameraCapabilityProbe.toneMapName(meta?.tonemapMode),
            "roi_brightest_fraction_at_lock" to (oximetry.lockedBrightestFraction?.let { String.format(Locale.US, "%.3f", it) } ?: ""),
            "fps_range" to (if (useOximetryCapture) caps?.chosenFpsRange()?.asText() ?: "?" else "auto (CameraX default)"),
            "sensor_frame_duration_ns_requested" to (if (isBranchA) oximetry.frameDurationNs().toString() else ""),
            "zero_light_offset" to ZeroLightOffset.describe(),
            "zero_light_last_measured_this_session" to zeroLightNote,
            "lighting_condition" to lightingInput.text.toString().ifBlank { "(not entered)" },
            "rgb_values" to "raw 8-bit ROI means; BT.601 from YUV_420_888; stride-2 sampled; zero-light offset NOT subtracted",
            "clip_threshold" to "any channel >= ${OximetryMath.CLIP_THRESHOLD.toInt()} (clipped_px of sampled_px)",
            "roi_coordinates" to "upright (rotated) analysis frame ${lastRotatedWidth}x$lastRotatedHeight px",
            "hr_spo2_columns" to "latest displayed values (1 Hz recompute); spo2 formula unchanged: 96.476+0.416*R",
            "event_rows" to "first field EVENT then timestamp_ns then breath_hold_start|breath_hold_end|note (a note may carry a 4th text field)",
            "elapsed_ms" to "sensor time since the Start tap; the first few rows are slightly negative (frames exposed before the tap; about 100-150 ms pipeline latency)"
        )
    }

    /** Task 3: cover the front camera, measure 5 s of dark frames under the
     *  current LOCKED settings, log the mean R/G/B (SPANDAN_ZERO_LIGHT). */
    private fun runZeroLightMeasurement() {
        if (!useOximetryCapture || oximetry.lockState != LockState.LOCKED) {
            Toast.makeText(this, "Turn on locked/linear capture and wait for LOCKED first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Cover the front camera now - measuring in 3 s for 5 s", Toast.LENGTH_LONG).show()
        uiHandler.postDelayed({
            zeroLightMeter.start()
            uiHandler.postDelayed({
                val r = zeroLightMeter.stop()
                val meta = oximetry.latestMeta
                zeroLightNote = String.format(
                    Locale.US, "R=%.3f;G=%.3f;B=%.3f;frames=%d;exposure_ns=%s;iso=%s",
                    r.red, r.green, r.blue, r.frames, meta?.exposureNs, meta?.iso
                )
                Log.i(ZERO_LIGHT_TAG, "model=${Build.MANUFACTURER} ${Build.MODEL} state=${oximetry.lockState} $zeroLightNote")
                if (recorder.isRecording) recorder.writeEvent(nowOnSensorClockNs(), CalibrationCsvFormat.Event.NOTE, "zero_light $zeroLightNote")
                Toast.makeText(this, "Zero-light: $zeroLightNote", Toast.LENGTH_LONG).show()
            }, 5000L)
        }, 3000L)
    }

    /** Periodically refreshes the chart + HR text, decoupled from the camera
     *  analysis frame rate. */
    private fun startUiRefreshLoop() {
        val refreshIntervalMs = 200L
        val runnable = object : Runnable {
            override fun run() {
                refreshUi()
                uiHandler.postDelayed(this, refreshIntervalMs)
            }
        }
        uiHandler.post(runnable)
    }

    private fun refreshUi() {
        val samples = signalBuffer.snapshot()

        // Segment 16 Task 3 -- "No face detected" banner, debounced so a
        // single missed detection (normal noise, or one of FaceAnalyzer's
        // own every-Nth-frame skipped-detection cycles) doesn't flash it.
        val msSinceFace = System.currentTimeMillis() - lastFaceSeenMs
        val noFaceSustained = lastFaceSeenMs == 0L || msSinceFace > NO_FACE_DEBOUNCE_MS
        noFaceBanner.visibility = if (noFaceSustained) View.VISIBLE else View.GONE

        // HR: real pipeline (detrend -> bandpass -> CHROM/POS -> FFT), see
        // signal/RealHeartRateEstimator.kt. Null until enough of the buffer window
        // has filled.
        val hrRaw = heartRateEstimator.update(samples)
        // Segment 16 Task 1 -- display-level smoothing only, gated off by
        // default (see the field's own KDoc above). When disabled this is a
        // pure passthrough (DisplaySmoother.Mode.NONE-equivalent), so hrBpm
        // is byte-for-byte hrRaw unless explicitly enabled.
        val hrBpm = if (enableHrDisplaySmoothing) hrDisplaySmoother.smooth(hrRaw) else hrRaw
        lastHrBpm = hrBpm
        hrText.text = if (hrBpm != null) {
            getString(R.string.hr_format, hrBpm)
        } else {
            getString(R.string.hr_placeholder_default)
        }
        applyStatusPill(
            dot = hrStatusDot, label = hrStatusLabel, noFace = noFaceSustained,
            status = heartRateEstimator.lastStatus, okText = getString(R.string.status_ok_hr)
        )

        // SpO2: real ratio-of-ratios + linear calibration, see
        // signal/LiveSpo2Estimator.kt. Independent call on the same samples
        // snapshot -- does not read heartRateEstimator's state or vice versa.
        val spo2 = spo2Estimator.update(samples)
        lastSpo2 = spo2
        spo2Text.text = if (spo2 != null) {
            getString(R.string.spo2_format, spo2)
        } else {
            getString(R.string.spo2_placeholder_default)
        }
        applyStatusPill(
            dot = spo2StatusDot, label = spo2StatusLabel, noFace = noFaceSustained,
            status = spo2Estimator.lastStatus, okText = getString(R.string.status_ok_spo2)
        )

        // Segment 19 -- Branch 2 morphology, independent call on the same
        // samples snapshot (does not read heartRateEstimator/spo2Estimator
        // state or vice versa, matching Branch 1/Branch 2's deliberate
        // MATLAB-side separation).
        val morphologyEstimate = morphologyEstimator.update(samples)
        // Segment 31 -- the multi-cycle continuous trace, not the single
        // averaged beat; see WaveformView's own KDoc for why.
        morphologyWaveformView.update(morphologyEstimate?.continuousWaveform)
        applyMorphologyStatusPill(noFaceSustained, morphologyEstimator.lastStatus, morphologyEstimate)

        // [Segment 34] developer panel status (only while it is visible).
        if (calibrationPanel.visibility == View.VISIBLE) {
            val last = samples.lastOrNull()
            val clip = if (last != null && last.sampledPixels > 0) " clip=${last.clippedPixels}/${last.sampledPixels}" else ""
            val rgb = if (last != null) String.format(Locale.US, " RGB=%.0f/%.0f/%.0f", last.red, last.green, last.blue) else ""
            val rec = if (recorder.isRecording) " REC ${recorder.rowsWritten} rows" else ""
            val zero = if (zeroLightNote.isNotEmpty()) "\nzero-light $zeroLightNote" else ""
            oximetryStatusText.text = oximetry.statusLine() + rgb + clip + rec + zero
        }
    }

    /**
     * Segment 19 -- like [applyStatusPill], but additionally surfaces the
     * notch CONFIDENCE VALUE in the label text (per this task's own
     * instruction: "surface the confidence score in the UI, not just a
     * yes/no" -- [NotchDetectIEM]'s own boolean `detected` output is a
     * near-useless gate at pool scale on the MATLAB side, so a bare
     * "detected"/"not detected" label here would repeat that same mistake).
     * When [status] is OK, the dot color additionally reflects whether the
     * RAW confidence clears this project's own 0.3 bar (green) or not
     * (amber) -- EstimatorStatus.OK alone only means "a value was computed
     * this tick," not "that value was a confident notch."
     */
    private fun applyMorphologyStatusPill(noFace: Boolean, status: EstimatorStatus, estimate: MorphologyWaveformEstimator.Estimate?) {
        val (color, text) = when {
            noFace -> R.color.status_no_face to getString(R.string.status_no_face)
            status == EstimatorStatus.WARMING_UP -> R.color.status_warming to getString(R.string.status_warming_up)
            status == EstimatorStatus.LOW_SIGNAL_QUALITY -> R.color.status_low_quality to getString(R.string.status_low_signal)
            estimate == null -> R.color.status_warming to getString(R.string.status_warming_up)
            !estimate.notchDetected -> R.color.status_low_quality to getString(R.string.morphology_no_notch)
            else -> {
                val methodLabel = if (estimate.harmonicMethodUsed == "gaussian015") {
                    getString(R.string.morphology_method_gaussian)
                } else {
                    getString(R.string.morphology_method_abpf)
                }
                val statusColor = if (estimate.notchConfidenceRaw > MORPHOLOGY_CONFIDENCE_BAR) R.color.status_ok else R.color.status_low_quality
                statusColor to getString(R.string.morphology_status_format, methodLabel, estimate.notchConfidenceRaw)
            }
        }
        val tint = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        morphologyStatusDot.backgroundTintList = tint
        morphologyStatusLabel.text = text
        morphologyStatusLabel.setTextColor(ContextCompat.getColor(this, color))
    }

    /**
     * Segment 16 Task 1/2/3 -- maps an [EstimatorStatus] (plus the UI-only
     * "no face" case, which isn't one of the estimators' own states) onto a
     * status-pill color + label. Presentation-only: this function is never
     * called from, and never influences, either estimator's own computation.
     */
    private fun applyStatusPill(dot: View, label: TextView, noFace: Boolean, status: EstimatorStatus, okText: String) {
        val (color, text) = when {
            noFace -> R.color.status_no_face to getString(R.string.status_no_face)
            status == EstimatorStatus.WARMING_UP -> R.color.status_warming to getString(R.string.status_warming_up)
            status == EstimatorStatus.LOW_SIGNAL_QUALITY -> R.color.status_low_quality to getString(R.string.status_low_signal)
            else -> R.color.status_ok to okText
        }
        val tint = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        dot.backgroundTintList = tint
        label.text = text
        label.setTextColor(ContextCompat.getColor(this, color))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::recorder.isInitialized) recorder.shutdown()
        analysisExecutor.shutdown()
        uiHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "SpandanMainActivity"

        /** Segment 16 Task 3 -- how long since a face was last detected
         *  before the "No face detected" banner appears. 1200ms is a few
         *  multiples of FaceAnalyzer's own DETECT_EVERY_N_FRAMES=3 skip
         *  cycle at this project's measured ~13-21fps range (a few hundred
         *  ms per cycle), so an ordinary skip cycle or one failed detection
         *  never flashes the banner, but a real sustained absence (phone
         *  set down, face turned away) shows it within about a second. Not
         *  tuned against a real on-device capture this session -- flagged
         *  for the on-device test alongside Task 1's smoothing evaluation. */
        private const val NO_FACE_DEBOUNCE_MS = 1200L

        /** Segment 16 Task 1 -- PROMOTED TO ON 2026-09-14 after a real
         *  on-device A/B capture (Galaxy A35, 83 distinct recomputes over
         *  ~66s): rolling-median smoothing cut mean tick-to-tick jump from
         *  17.46bpm to 5.49bpm (~69% reduction) and stdev from 24.63 to
         *  20.54bpm, with no accuracy cost (the raw switched value is still
         *  computed and logged unchanged; smoothing is display-only). Real
         *  bug found and fixed during that same test -- see
         *  DisplaySmoother.kt's own header -- before this result was
         *  trustworthy. Single session, single subject, no manual-pulse
         *  cross-check this round -- see docs/Segment16_Task1_HR_
         *  Stability.md for the full caveats and the remaining test ideas. */
        private const val ENABLE_HR_DISPLAY_SMOOTHING_DEFAULT = true

        /** Segment 19 -- this project's own standing notch-confidence bar
         *  (matches `morphology/harmonicFilterConfidenceGate.m`'s own
         *  `confidenceThreshold` default and every MATLAB-side notch
         *  pass/fail table), used here only to color the status pill --
         *  the numeric confidence itself is always shown regardless of
         *  which side of this bar it falls on. */
        private const val MORPHOLOGY_CONFIDENCE_BAR = 0.3

        /** [Segment 34] OFF by default: locked exposure/ISO/white balance +
         *  linear tone curve for oximetry-grade RGB (Xuan et al. 2023). Not
         *  promoted until HR is shown not to be worse with it on -- see
         *  docs/Segment34_SpO2_Oximetry_Capture.md. Switchable at runtime from
         *  the developer calibration panel (long-press the vitals card). */
        private const val USE_OXIMETRY_CAPTURE_DEFAULT = false

        private const val ZERO_LIGHT_TAG = "SPANDAN_ZERO_LIGHT"

        /** How long a calibration row may wait for its frame's capture result. */
        private const val ROW_META_WAIT_MS = 300L
    }
}
