package com.spandan.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
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
 * file does, which is a REAL per-detection-frame FaceMesh/FaceLandmarker
 * call (frozen only between the same every-Nth-frame skip window every
 * other analyzer in this app already uses, not tracked via pixel-content
 * motion estimation). This is the Android port of
 * `faceMeshAnatomyROIExtraction.m` specifically (Segment 35 Phase 1),
 * which already showed a real, Holm-significant HR accuracy effect on
 * MATLAB (`matlab/docs/Segment35_MediaPipe_Anatomy_ROI.md`) -- a materially
 * different, more direct precedent than the KLT-tracking result Segment 30
 * was avoiding. Segment 30's OTHER finding -- the YUV->Bitmap conversion tax
 * (measured ~71ms of ~87ms bundled MediaPipe cost on a Galaxy A35) -- DOES
 * apply here identically (MediaPipe Tasks Vision's Android packet creator
 * rejects raw YUV_420_888 through any zero-copy path, confirmed by Segment
 * 30 and not re-litigated here) and is accepted as a real, disclosed cost of
 * this being an opt-in "quality" toggle, not the default camera path.
 *
 * COORDINATE SPACE: unlike [FaceAnalyzer]'s MediaPipe FaceDetector branch
 * (which passes the RAW sensor-orientation bitmap + a rotation HINT, then
 * has to un-rotate ONE returned box afterward via
 * [CoordinateMapper.mediaPipeSensorBoxToRotatedRect]), this analyzer
 * physically rotates the converted [Bitmap] to upright BEFORE calling
 * FaceLandmarker. Nine independent landmark points would need that same
 * fix applied nine times over if left in sensor space, so pre-rotating once
 * is simpler and less bug-prone -- landmarks then come back directly
 * relative to the upright image, exactly matching
 * faceMeshAnatomyROIExtraction.m's own convention (MATLAB decodes an
 * already-upright VideoReader frame). See [AnatomyRoiCalculator]'s own KDoc
 * for the same point from the landmark-math side.
 *
 * HONEST STATUS: built and unit-tested (pure math in [AnatomyRoiCalculator]/
 * [AnatomyRoiPixelAverager]) but NOT on-device verified this session (no
 * physical Android device attached to this environment) -- unlike most of
 * this project's prior Android segments, which had real device access
 * partway through. Do not treat this as validated the way
 * `FaceAnalyzer.kt`'s promoted defaults are; treat every fps/accuracy claim
 * in this file's own KDoc as inherited from Segment 30/35's MEASUREMENTS on
 * a related but not identical code path, not as a fresh measurement of this
 * exact file.
 */
class AnatomyRoiFaceAnalyzer(
    context: Context,
    private val onResult: (FaceAnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker = buildFaceLandmarker(context)

    private var frameCounter = 0
    private var lastBoxes: AnatomyRoiCalculator.AnatomyBoxes? = null

    // Single in-flight LIVE_STREAM call state -- same safety argument as
    // FaceAnalyzer's own pendingMediaPipeImageProxy (STRATEGY_KEEP_ONLY_LATEST
    // on a single-threaded executor means at most one detectAsync is ever
    // in flight).
    private var pendingImageProxy: ImageProxy? = null
    private var pendingRotatedBitmap: Bitmap? = null
    private var pendingRotatedImageWidth = 0
    private var pendingRotatedImageHeight = 0

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

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
        val staleBoxes = lastBoxes
        val shouldSkipDetection = staleBoxes != null && frameCounter % DETECT_EVERY_N_FRAMES != 0

        if (shouldSkipDetection) {
            // Frozen between real detections, same discipline FaceAnalyzer's
            // own default (no motion-tracking/Kalman) path uses for its
            // single face box -- no measured landmark-specific tracker
            // exists, and matlab/docs/Segment7_Task_D_Landmark_ROI.md's own
            // KLT-tracking negative result is a real reason not to reach for
            // one without new evidence.
            emitBoxesResult(staleBoxes!!, rotatedImageWidth, rotatedImageHeight, imageProxy, sensorTimestampNs = imageProxy.imageInfo.timestamp)
            imageProxy.close()
            return
        }

        val rawBitmap = MediaPipeImageConverter.yuv420ToArgb8888Bitmap(mediaImage)
        val rotatedBitmap = rotateBitmap(rawBitmap, rotationDegrees)
        if (rotatedBitmap !== rawBitmap) rawBitmap.recycle()

        pendingImageProxy = imageProxy
        pendingRotatedBitmap = rotatedBitmap
        pendingRotatedImageWidth = rotatedImageWidth
        pendingRotatedImageHeight = rotatedImageHeight

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        faceLandmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp / 1_000_000)
    }

    /** Shared LIVE_STREAM resultListener -- see [analyze]'s own note on why
     *  per-call state lives in [pendingImageProxy] and friends. */
    private fun onLandmarkResult(result: FaceLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        val imageProxy = pendingImageProxy
        val rotatedBitmap = pendingRotatedBitmap
        val rotatedImageWidth = pendingRotatedImageWidth
        val rotatedImageHeight = pendingRotatedImageHeight
        pendingImageProxy = null
        pendingRotatedBitmap = null
        if (imageProxy == null || rotatedBitmap == null) return // stray callback

        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            lastBoxes = null
            onResult(FaceAnalysisResult.NoFace)
            rotatedBitmap.recycle()
            imageProxy.close()
            return
        }

        // Only one face requested (setNumFaces(1)); take it directly.
        val landmarksList = faces[0]
        val landmarks = HashMap<Int, FloatArray>(AnatomyRoiCalculator.REQUIRED_LANDMARK_INDICES.size)
        for (idx in AnatomyRoiCalculator.REQUIRED_LANDMARK_INDICES) {
            if (idx < landmarksList.size) {
                val lm = landmarksList[idx]
                landmarks[idx] = floatArrayOf(lm.x() * rotatedImageWidth, lm.y() * rotatedImageHeight)
            }
        }

        val boxes = AnatomyRoiCalculator.landmarksToBoxes(landmarks, rotatedImageWidth, rotatedImageHeight)
        lastBoxes = boxes
        emitBoxesResultFromBitmap(boxes, rotatedBitmap, rotatedImageWidth, rotatedImageHeight, imageProxy)
        rotatedBitmap.recycle()
        imageProxy.close()
    }

    private fun onLandmarkError(e: RuntimeException) {
        Log.w(TAG, "FaceLandmarker failed for this frame", e)
        val imageProxy = pendingImageProxy
        val rotatedBitmap = pendingRotatedBitmap
        pendingImageProxy = null
        pendingRotatedBitmap = null
        lastBoxes = null
        onResult(FaceAnalysisResult.NoFace)
        rotatedBitmap?.recycle()
        imageProxy?.close()
    }

    /** Fresh-detection path: pixels come from the bitmap FaceLandmarker just
     *  ran on. */
    private fun emitBoxesResultFromBitmap(
        boxes: AnatomyRoiCalculator.AnatomyBoxes,
        bitmap: Bitmap,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int,
        imageProxy: ImageProxy
    ) {
        val pixels = IntArray(rotatedImageWidth * rotatedImageHeight)
        bitmap.getPixels(pixels, 0, rotatedImageWidth, 0, 0, rotatedImageWidth, rotatedImageHeight)
        val rgbSample = AnatomyRoiPixelAverager.averageRgb(
            pixels, rotatedImageWidth, rotatedImageHeight,
            listOf(boxes.forehead, boxes.leftCheek, boxes.rightCheek),
            sensorTimestampNs = imageProxy.imageInfo.timestamp
        )
        onResult(toFaceDetected(boxes, rotatedImageWidth, rotatedImageHeight, rgbSample))
    }

    /** Skipped-detection path: no fresh bitmap was converted this frame, so
     *  this re-converts+rotates JUST for the pixel averaging (still cheaper
     *  than also running FaceLandmarker, which [shouldSkipDetection] exists
     *  to avoid). */
    @ExperimentalGetImage
    private fun emitBoxesResult(
        boxes: AnatomyRoiCalculator.AnatomyBoxes,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int,
        imageProxy: ImageProxy,
        sensorTimestampNs: Long
    ) {
        val mediaImage = imageProxy.image ?: run {
            onResult(FaceAnalysisResult.NoFace)
            return
        }
        val rawBitmap = MediaPipeImageConverter.yuv420ToArgb8888Bitmap(mediaImage)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rotatedBitmap = rotateBitmap(rawBitmap, rotationDegrees)
        if (rotatedBitmap !== rawBitmap) rawBitmap.recycle()

        val pixels = IntArray(rotatedImageWidth * rotatedImageHeight)
        rotatedBitmap.getPixels(pixels, 0, rotatedImageWidth, 0, 0, rotatedImageWidth, rotatedImageHeight)
        val rgbSample = AnatomyRoiPixelAverager.averageRgb(
            pixels, rotatedImageWidth, rotatedImageHeight,
            listOf(boxes.forehead, boxes.leftCheek, boxes.rightCheek),
            sensorTimestampNs = sensorTimestampNs
        )
        rotatedBitmap.recycle()
        onResult(toFaceDetected(boxes, rotatedImageWidth, rotatedImageHeight, rgbSample))
    }

    private fun toFaceDetected(
        boxes: AnatomyRoiCalculator.AnatomyBoxes,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int,
        rgbSample: com.spandan.app.signal.RgbSample?
    ): FaceAnalysisResult.FaceDetected {
        val unionBox = Rect(boxes.forehead)
        unionBox.union(boxes.leftCheek)
        unionBox.union(boxes.rightCheek)
        return FaceAnalysisResult.FaceDetected(
            faceBoxRotated = unionBox,
            roiBoxRotated = boxes.forehead,
            rotatedImageWidth = rotatedImageWidth,
            rotatedImageHeight = rotatedImageHeight,
            rgbSample = rgbSample
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Releases native resources (loaded model + task graph). Same known gap
     *  as [FaceAnalyzer.close] -- nothing currently calls this. */
    fun close() {
        faceLandmarker.close()
    }

    private fun buildFaceLandmarker(context: Context): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setResultListener(::onLandmarkResult)
            .setErrorListener(::onLandmarkError)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    companion object {
        private const val TAG = "AnatomyRoiFaceAnalyzer"

        /** Same cadence as [FaceAnalyzer.DETECT_EVERY_N_FRAMES] -- run a real
         *  FaceLandmarker detection every Nth frame, reuse the last known
         *  anatomy boxes on the frames in between. Not independently
         *  re-measured for this heavier detector; kept consistent rather
         *  than guessed differently. */
        private const val DETECT_EVERY_N_FRAMES = 3

        /** Bundled in `app/src/main/assets/`, downloaded from
         *  storage.googleapis.com/mediapipe-models/face_landmarker/
         *  face_landmarker/float16/1/face_landmarker.task -- verified a real
         *  MediaPipe task bundle (a ZIP archive, PK magic bytes, matching the
         *  server's own reported content-length) before being committed,
         *  same discipline as Segment 30's own `.tflite` asset check. */
        const val MODEL_ASSET_PATH = "face_landmarker.task"
    }
}
