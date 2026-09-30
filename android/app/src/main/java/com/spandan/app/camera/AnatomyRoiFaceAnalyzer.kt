package com.spandan.app.camera

import android.content.Context
import android.graphics.Rect
import android.media.Image
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.spandan.app.signal.RgbSample
import com.spandan.app.signal.SignalBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Segment 36] `ImageAnalysis.Analyzer` for the "anatomy ROI" toggle --
 * ports matlab/src/roi/faceMeshAnatomyROIExtraction.m (MediaPipe FaceMesh
 * forehead + both-malar ROI, Kim/Lee/Sohn 2021) to Android, using MediaPipe
 * Tasks Vision's FaceLandmarker. Emits the SAME [FaceAnalysisResult] sealed
 * type [FaceAnalyzer] does, so [MainActivity]'s existing `handleAnalysisResult`
 * needs ZERO changes to consume either analyzer's output -- only
 * `faceBoxRotated` (set to the union of the 3 anatomy boxes, for the overlay)
 * and `roiBoxRotated` (set to the forehead box specifically) are repurposed
 * slightly from [FaceAnalyzer]'s own single-face-box meaning.
 *
 * WHY THIS DIFFERS FROM SEGMENT 30'S OWN "STAY ON FACEDETECTOR, NOT
 * FACELANDMARKER" DECISION (read before touching this file): Segment 30's
 * MediaPipe migration doc (`android_segment30_mediapipe/docs/
 * Segment30_MediaPipe_Migration.md`) deliberately avoided FaceLandmarker,
 * citing `matlab/docs/Segment7_Task_D_Landmark_ROI.md`'s negative finding
 * that a KLT-TRACKED landmark ROI regressed 2/5 UBFC subjects vs. a plain
 * box. That finding is about optical-flow TRACKING of one frame's landmarks
 * across many subsequent frames -- a different technique from what this
 * file does, which is a REAL, repeated FaceLandmarker call whose latest
 * result is reused only until the next one lands (not tracked via
 * pixel-content motion estimation). This is the Android port of
 * `faceMeshAnatomyROIExtraction.m` specifically (Segment 35 Phase 1),
 * which already showed a real, Holm-significant HR accuracy effect on
 * MATLAB (`matlab/docs/Segment35_MediaPipe_Anatomy_ROI.md`). MediaPipe Tasks
 * Vision's Android packet creator rejects raw YUV_420_888 through any
 * zero-copy path (Segment 30), so the landmarker input must still be an
 * ARGB Bitmap -- but see THREADING below for how that cost is now kept off
 * the rPPG sampling path.
 *
 * COORDINATE SPACE: unlike [FaceAnalyzer]'s MediaPipe FaceDetector branch
 * (which passes the RAW sensor-orientation bitmap + a rotation HINT, then
 * has to un-rotate ONE returned box afterward), this analyzer builds an
 * already-upright (and downscaled) bitmap for FaceLandmarker, so the
 * normalized landmarks come back relative to the upright image, exactly
 * matching faceMeshAnatomyROIExtraction.m's own convention (MATLAB decodes an
 * already-upright VideoReader frame). They are scaled by the FULL-resolution
 * upright dimensions, so boxes live in the same rotated space [FaceAnalyzer]'s
 * boxes do, and are mapped to sensor space with
 * [CoordinateMapper.rotatedRectToSensorRect] for pixel averaging.
 *
 * THREADING / SAMPLING MODEL ([Segment 36 on-device fix, 2026-09-30]): the
 * first on-device version held each detection frame's [ImageProxy] open
 * until FaceLandmarker's LIVE_STREAM callback fired, and converted the FULL
 * 1280x720 frame to an ARGB Bitmap (plus a rotated copy) on EVERY frame --
 * including skipped-detection frames, just to average ~19k ROI pixels. With
 * KEEP_ONLY_LATEST backpressure the camera delivered no new frame while
 * inference ran, so the rPPG sample stream inherited every inference stall,
 * and a one-off slow inference/GC pause could push the inter-sample gap past
 * [SignalBuffer.GAP_CLEAR_THRESHOLD_SECONDS], clearing the buffer
 * ("Re-acquiring signal") -- the "more frequent cycling than classical mode"
 * characteristic Segment36_Anatomy_ROI_Port.md §6.3 recorded. It also meant
 * a LIVE_STREAM frame dropped without a callback would never close its
 * ImageProxy and would stall the camera for good. Now:
 *
 *  - every frame's [ImageProxy] is closed synchronously in [analyze];
 *  - the rPPG sample is pooled straight from YUV over ONLY the ROI boxes
 *    ([AnatomyRoiPixelAverager.averageRgbFromYuv]) using the most recent
 *    landmark boxes, so the sample rate is the camera rate, independent of
 *    inference latency;
 *  - FaceLandmarker runs asynchronously on a 1/[DETECTION_DOWNSCALE_STEP]
 *    upright copy, at most one call in flight, with a watchdog that frees the
 *    slot if a callback never arrives;
 *  - a single missed landmark result no longer drops the face: the last
 *    boxes are kept for [MISS_GRACE_MS] before NoFace is reported (the
 *    classical path's stale-box/Kalman reuse gives it the same tolerance).
 */
class AnatomyRoiFaceAnalyzer(
    context: Context,
    private val onResult: (FaceAnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker = buildFaceLandmarker(context)

    private var frameCounter = 0

    /** Latest landmark-derived boxes in full-resolution upright space --
     *  written by MediaPipe's callback thread, read by the analysis
     *  executor. */
    @Volatile private var latestBoxes: AnatomyRoiCalculator.AnatomyBoxes? = null
    @Volatile private var lastLandmarkHitMs = 0L

    private val detectionInFlight = AtomicBoolean(false)
    @Volatile private var detectionStartedMs = 0L
    @Volatile private var closed = false

    // [Real bug, found on-device 2026-09-30] close() runs on the main thread
    // (MainActivity.bindUseCases on a toggle flip) while analyze() may be
    // inside detectAsync on the analysis executor. Closing the native task
    // graph mid-call was a SIGSEGV (null deref inside
    // libmediapipe_tasks_jni.so, reproduced by flipping the toggle a few
    // times quickly). Both calls now hold this lock, and detectAsync is
    // skipped once closed.
    private val landmarkerLock = Any()

    // Upright full-res dimensions of the frame the in-flight detection was
    // taken from -- landmarks are normalized, so they are scaled by these,
    // not by the downscaled bitmap's own size.
    @Volatile private var pendingRotatedWidth = 0
    @Volatile private var pendingRotatedHeight = 0

    // [Real bug, found on-device 2026-09-29] MediaPipe Tasks Vision's
    // LIVE_STREAM mode requires STRICTLY INCREASING timestamps across
    // detectAsync calls. A real Camera2 session hiccup (CameraManagerGlobal
    // briefly reporting STATUS_NOT_AVAILABLE, then CameraX transparently
    // reopening the SAME camera and reusing this SAME analyzer instance)
    // reset imageProxy.imageInfo.timestamp to a lower value, and
    // FaceLandmarker silently stopped invoking its callbacks for the rest of
    // the session. Clamped so that can never recur.
    private var lastSentTimestampMs = 0L

    private val stats = Stats()

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        try {
            analyzeFrame(imageProxy)
        } finally {
            imageProxy.close()
        }
    }

    @ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        if (closed) return

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rotatedImageWidth: Int
        val rotatedImageHeight: Int
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            rotatedImageWidth = imageProxy.height
            rotatedImageHeight = imageProxy.width
        } else {
            rotatedImageWidth = imageProxy.width
            rotatedImageHeight = imageProxy.height
        }

        frameCounter++
        val nowMs = SystemClock.elapsedRealtime()
        stats.onFrame(nowMs)

        if (detectionInFlight.get() && nowMs - detectionStartedMs > DETECTION_WATCHDOG_MS) {
            // No callback for this long means MediaPipe dropped the frame
            // (or is wedged) -- free the slot so detection keeps going.
            detectionInFlight.set(false)
            stats.watchdogResets++
        }

        val boxes = latestBoxes
        val wantDetection = boxes == null || frameCounter % DETECT_EVERY_N_FRAMES == 0
        if (wantDetection && detectionInFlight.compareAndSet(false, true)) {
            startDetection(mediaImage, imageProxy, rotationDegrees, rotatedImageWidth, rotatedImageHeight, nowMs)
        }

        // No face known yet: nothing to sample. NoFace itself is reported
        // from the landmark callback, which is what actually knows.
        if (boxes == null) return

        val sensorRegions = listOf(boxes.forehead, boxes.leftCheek, boxes.rightCheek).map { rect ->
            CoordinateMapper.rotatedRectToSensorRect(rect, rotationDegrees, imageProxy.width, imageProxy.height)
        }
        val rgbSample = AnatomyRoiPixelAverager.averageRgbFromYuv(imageProxy, sensorRegions)
        if (rgbSample != null) stats.onSample(rgbSample.sensorTimestampNs)
        onResult(toFaceDetected(boxes, rotatedImageWidth, rotatedImageHeight, rgbSample, imageProxy.imageInfo.timestamp))
    }

    private fun startDetection(
        mediaImage: Image,
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int,
        nowMs: Long
    ) {
        try {
            val bitmap = MediaPipeImageConverter.yuv420ToUprightArgb8888Bitmap(
                mediaImage, rotationDegrees, DETECTION_DOWNSCALE_STEP
            )
            pendingRotatedWidth = rotatedImageWidth
            pendingRotatedHeight = rotatedImageHeight
            detectionStartedMs = nowMs
            val rawTimestampMs = imageProxy.imageInfo.timestamp / 1_000_000
            val sendTimestampMs = maxOf(rawTimestampMs, lastSentTimestampMs + 1)
            lastSentTimestampMs = sendTimestampMs
            // The bitmap is deliberately NOT recycled in the callback: after
            // a watchdog reset, a late callback could otherwise recycle a
            // bitmap MediaPipe is still reading. It is ~0.9 MB; left to GC.
            synchronized(landmarkerLock) {
                if (closed) {
                    detectionInFlight.set(false)
                    return
                }
                faceLandmarker.detectAsync(BitmapImageBuilder(bitmap).build(), sendTimestampMs)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "detectAsync failed to start", e)
            detectionInFlight.set(false)
        }
    }

    /** LIVE_STREAM resultListener (runs on MediaPipe's own callback thread). */
    private fun onLandmarkResult(result: FaceLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        val nowMs = SystemClock.elapsedRealtime()
        stats.onDetectionDone(nowMs - detectionStartedMs)
        try {
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) {
                onLandmarkMiss(nowMs)
                return
            }

            val rotatedImageWidth = pendingRotatedWidth
            val rotatedImageHeight = pendingRotatedHeight
            // Only one face requested (setNumFaces(1)); take it directly.
            val landmarksList = faces[0]
            val landmarks = HashMap<Int, FloatArray>(AnatomyRoiCalculator.REQUIRED_LANDMARK_INDICES.size)
            for (idx in AnatomyRoiCalculator.REQUIRED_LANDMARK_INDICES) {
                if (idx < landmarksList.size) {
                    val lm = landmarksList[idx]
                    landmarks[idx] = floatArrayOf(lm.x() * rotatedImageWidth, lm.y() * rotatedImageHeight)
                }
            }
            latestBoxes = AnatomyRoiCalculator.landmarksToBoxes(landmarks, rotatedImageWidth, rotatedImageHeight)
            lastLandmarkHitMs = nowMs
        } finally {
            detectionInFlight.set(false)
        }
    }

    private fun onLandmarkError(e: RuntimeException) {
        Log.w(TAG, "FaceLandmarker failed for this frame", e)
        onLandmarkMiss(SystemClock.elapsedRealtime())
        detectionInFlight.set(false)
    }

    /** A detection that found no face. Keeps the last boxes through a short
     *  grace window (one missed inference is normal pose/blur noise, not
     *  "face gone"); only after that does the face count as lost. */
    private fun onLandmarkMiss(nowMs: Long) {
        stats.onLandmarkMiss()
        if (latestBoxes != null && nowMs - lastLandmarkHitMs < MISS_GRACE_MS) return
        latestBoxes = null
        onResult(FaceAnalysisResult.NoFace)
    }

    private fun toFaceDetected(
        boxes: AnatomyRoiCalculator.AnatomyBoxes,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int,
        rgbSample: RgbSample?,
        sensorTimestampNs: Long
    ): FaceAnalysisResult.FaceDetected {
        val unionBox = Rect(boxes.forehead)
        unionBox.union(boxes.leftCheek)
        unionBox.union(boxes.rightCheek)
        return FaceAnalysisResult.FaceDetected(
            faceBoxRotated = unionBox,
            roiBoxRotated = boxes.forehead,
            rotatedImageWidth = rotatedImageWidth,
            rotatedImageHeight = rotatedImageHeight,
            rgbSample = rgbSample,
            // [Real bug, found on-device 2026-09-29] was never threaded
            // through (defaulted to 0L) -- MainActivity uses it for oximetry
            // capture's per-frame metadata join and the calibration CSV.
            sensorTimestampNs = sensorTimestampNs,
            // [Real bug, found on-device 2026-09-29] without this the
            // overlay drew only the forehead box (the field's default),
            // though all 3 regions were already pooled into rgbSample.
            roiBoxesRotated = listOf(boxes.forehead, boxes.leftCheek, boxes.rightCheek)
        )
    }

    /** Releases native resources (loaded model + task graph). MainActivity
     *  calls this on every rebind that replaces this analyzer; [closed] makes
     *  any frame already queued on the executor a no-op. */
    fun close() {
        synchronized(landmarkerLock) {
            if (closed) return
            closed = true
            faceLandmarker.close()
        }
    }

    private fun buildFaceLandmarker(context: Context): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setResultListener(::onLandmarkResult)
            .setErrorListener(::onLandmarkError)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /** Throttled throughput summary: one log line per [STATS_INTERVAL_MS],
     *  not per frame, so it stays on in normal use. `adb logcat -s
     *  AnatomyRoiFaceAnalyzer` verifies sample rate, inference latency and
     *  gap behavior on any device. */
    private class Stats {
        private var windowStartMs = 0L
        private var frames = 0
        private var samples = 0
        private var detections = 0
        private var latencySumMs = 0L
        private var latencyMaxMs = 0L
        private var misses = 0
        var watchdogResets = 0
        private var lastSampleNs = 0L
        private var maxGapMs = 0L
        private var clearingGaps = 0

        @Synchronized fun onFrame(nowMs: Long) {
            if (windowStartMs == 0L) windowStartMs = nowMs
            frames++
            val elapsedMs = nowMs - windowStartMs
            if (elapsedMs < STATS_INTERVAL_MS) return
            val secs = elapsedMs / 1000.0
            val meanLatency = if (detections > 0) latencySumMs / detections else 0L
            Log.i(
                TAG,
                "STATS fps=%.1f samples/s=%.1f det/s=%.1f detLatMs(mean=%d,max=%d) misses=%d maxGapMs=%d clearingGaps=%d watchdog=%d".format(
                    frames / secs, samples / secs, detections / secs, meanLatency, latencyMaxMs,
                    misses, maxGapMs, clearingGaps, watchdogResets
                )
            )
            windowStartMs = nowMs
            frames = 0; samples = 0; detections = 0
            latencySumMs = 0L; latencyMaxMs = 0L
            misses = 0; watchdogResets = 0
            maxGapMs = 0L; clearingGaps = 0
        }

        @Synchronized fun onSample(sensorNs: Long) {
            samples++
            if (lastSampleNs > 0 && sensorNs > lastSampleNs) {
                val gapMs = (sensorNs - lastSampleNs) / 1_000_000
                if (gapMs > maxGapMs) maxGapMs = gapMs
                if (gapMs >= SignalBuffer.GAP_CLEAR_THRESHOLD_SECONDS * 1000) clearingGaps++
            }
            lastSampleNs = sensorNs
        }

        @Synchronized fun onDetectionDone(latencyMs: Long) {
            detections++
            latencySumMs += latencyMs
            if (latencyMs > latencyMaxMs) latencyMaxMs = latencyMs
        }

        @Synchronized fun onLandmarkMiss() {
            misses++
        }
    }

    companion object {
        private const val TAG = "AnatomyRoiFaceAnalyzer"

        /** Request a FaceLandmarker run every Nth frame (and every frame
         *  while no face is known), with at most one call in flight. Same N
         *  as [FaceAnalyzer]'s own detection cadence. */
        private const val DETECT_EVERY_N_FRAMES = 3

        /** Landmarker input is the frame subsampled by this factor
         *  (1280x720 -> 640x360); see
         *  [MediaPipeImageConverter.yuv420ToUprightArgb8888Bitmap]. */
        private const val DETECTION_DOWNSCALE_STEP = 2

        /** How long the last landmark boxes stay in use after a detection
         *  finds no face -- long enough to ride out one or two missed
         *  inferences, short enough that a face that really left stops being
         *  sampled well inside MainActivity's 1200ms "No face" debounce. */
        private const val MISS_GRACE_MS = 400L

        /** A LIVE_STREAM call with no callback after this long is treated as
         *  dropped and its in-flight slot freed. */
        private const val DETECTION_WATCHDOG_MS = 1000L

        /** Library defaults are 0.5 for all three. Handheld at arm's length,
         *  0.5 presence/tracking dropped the face more readily than ML Kit's
         *  detector (§6.3). This is a single-face selfie use case with no
         *  competing faces, and the skin mask downstream still rejects
         *  non-skin pixels if a box lands slightly off. */
        private const val MIN_DETECTION_CONFIDENCE = 0.4f
        private const val MIN_PRESENCE_CONFIDENCE = 0.3f
        private const val MIN_TRACKING_CONFIDENCE = 0.3f

        private const val STATS_INTERVAL_MS = 5000L

        /** Bundled in `app/src/main/assets/`, downloaded from
         *  storage.googleapis.com/mediapipe-models/face_landmarker/
         *  face_landmarker/float16/1/face_landmarker.task -- verified a real
         *  MediaPipe task bundle (a ZIP archive, PK magic bytes, matching the
         *  server's own reported content-length) before being committed,
         *  same discipline as Segment 30's own `.tflite` asset check. */
        const val MODEL_ASSET_PATH = "face_landmarker.task"
    }
}
