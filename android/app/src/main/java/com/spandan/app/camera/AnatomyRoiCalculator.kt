package com.spandan.app.camera

import android.graphics.Rect

/**
 * [Segment 36] Direct Kotlin port of
 * matlab/src/roi/faceMeshAnatomyROIExtraction.m's `landmarksToBoxes`/
 * `centeredFallbackBoxes`/`clampBox` -- the anatomy-informed forehead +
 * both-malar (cheekbone) ROI (Kim, Lee & Sohn 2021, Sensors 21:7923's own
 * top-5 regions), landmark-driven via MediaPipe FaceMesh/FaceLandmarker.
 *
 * Pure math, no Android/MediaPipe types in the signatures below on purpose --
 * [landmarksToBoxes] takes plain (x,y) pairs so it is exercisable from a
 * plain-JUnit test with synthetic landmark data, the same "keep the pure math
 * testable, Android API calls only at the edges" discipline this project's
 * own [RoiPixelAverager] (raw ByteBuffers, not Bitmap) and
 * [CoordinateMapper]'s `makeRect` helper (Segment 28/30's own fix for
 * `Rect`'s untestable 4-arg constructor under this project's plain-JUnit
 * harness) already established.
 *
 * LANDMARK INDICES (0-based MediaPipe FaceMesh/FaceLandmarker convention --
 * MediaPipe's 468-point topology is the same numbering regardless of
 * platform binding, so these are copied verbatim from
 * faceMeshAnatomyROIExtraction.m's own `lm` struct, not re-derived):
 *   foreheadTop=10, leftBrowOuter=105, rightBrowOuter=334,
 *   leftUnderEye=111, leftNasolabial=216, leftCheekOuter=137, leftNoseAla=129,
 *   rightUnderEye=340, rightNasolabial=436, rightCheekOuter=366, rightNoseAla=358.
 *
 * Coordinate-space contract: [landmarks] must already be in the SAME upright
 * pixel space as [frameWidth]/[frameHeight] -- i.e. the Bitmap MediaPipe
 * detected on must already be rotated upright BEFORE calling
 * FaceLandmarker, not left in raw sensor orientation. This is a deliberate,
 * simpler choice than [CoordinateMapper.mediaPipeSensorBoxToRotatedRect]'s
 * own approach for MediaPipe's FaceDetector (Segment 30): that function
 * exists because Segment 30 passed the RAW sensor-orientation bitmap +  a
 * rotation HINT (which MediaPipe documents as affecting inference only, NOT
 * the returned coordinate space) and had to un-rotate the single returned
 * box after the fact. Nine independent landmark POINTS (not one box) would
 * need that same fix applied nine times over, so [AnatomyRoiFaceAnalyzer]
 * instead physically rotates the Bitmap once before detection -- landmarks
 * then come back already relative to the upright image, matching
 * faceMeshAnatomyROIExtraction.m's own convention exactly (that function
 * reads `frames.CurrentTime`/`readFrame` from an already-upright MATLAB
 * VideoReader frame).
 */
object AnatomyRoiCalculator {

    const val LM_FOREHEAD_TOP = 10
    const val LM_LEFT_BROW_OUTER = 105
    const val LM_RIGHT_BROW_OUTER = 334
    const val LM_LEFT_UNDER_EYE = 111
    const val LM_LEFT_NASOLABIAL = 216
    const val LM_LEFT_CHEEK_OUTER = 137
    const val LM_LEFT_NOSE_ALA = 129
    const val LM_RIGHT_UNDER_EYE = 340
    const val LM_RIGHT_NASOLABIAL = 436
    const val LM_RIGHT_CHEEK_OUTER = 366
    const val LM_RIGHT_NOSE_ALA = 358

    /** All landmark indices this ROI needs -- callers only need to read
     *  these 9 (of MediaPipe's full 468) out of a detection result. */
    val REQUIRED_LANDMARK_INDICES = intArrayOf(
        LM_FOREHEAD_TOP, LM_LEFT_BROW_OUTER, LM_RIGHT_BROW_OUTER,
        LM_LEFT_UNDER_EYE, LM_LEFT_NASOLABIAL, LM_LEFT_CHEEK_OUTER, LM_LEFT_NOSE_ALA,
        LM_RIGHT_UNDER_EYE, LM_RIGHT_NASOLABIAL, LM_RIGHT_CHEEK_OUTER, LM_RIGHT_NOSE_ALA
    )

    data class AnatomyBoxes(val forehead: Rect, val leftCheek: Rect, val rightCheek: Rect)

    /**
     * Direct port of `landmarksToBoxes` + `clampBox`. [landmarks] maps a
     * MediaPipe landmark index to its (x, y) pixel coordinate in the
     * already-upright [frameWidth] x [frameHeight] image (NOT normalized
     * [0,1] -- caller multiplies by frameWidth/frameHeight first, matching
     * faceMeshAnatomyROIExtraction.m's own `allX = xyArray(:,1)' *
     * frameWidth` line). Missing any required index falls back to
     * [centeredFallbackBoxes], same "no history yet" philosophy as the
     * MATLAB original.
     */
    fun landmarksToBoxes(landmarks: Map<Int, FloatArray>, frameWidth: Int, frameHeight: Int): AnatomyBoxes {
        for (idx in REQUIRED_LANDMARK_INDICES) {
            if (idx !in landmarks) return centeredFallbackBoxes(frameWidth, frameHeight)
        }

        fun x(idx: Int) = landmarks.getValue(idx)[0]
        fun y(idx: Int) = landmarks.getValue(idx)[1]

        val fx1 = minOf(x(LM_LEFT_BROW_OUTER), x(LM_RIGHT_BROW_OUTER))
        val fx2 = maxOf(x(LM_LEFT_BROW_OUTER), x(LM_RIGHT_BROW_OUTER))
        val fy2 = (y(LM_LEFT_BROW_OUTER) + y(LM_RIGHT_BROW_OUTER)) / 2f
        val fyTop = y(LM_FOREHEAD_TOP)
        val fy1 = fyTop + 0.15f * (fy2 - fyTop)
        val forehead = clampBox(fx1, fy1, fx2 - fx1, fy2 - fy1, frameWidth, frameHeight)

        val lx1 = minOf(x(LM_LEFT_CHEEK_OUTER), x(LM_LEFT_NOSE_ALA))
        val lx2 = maxOf(x(LM_LEFT_CHEEK_OUTER), x(LM_LEFT_NOSE_ALA))
        val ly1 = y(LM_LEFT_UNDER_EYE)
        val ly2 = y(LM_LEFT_NASOLABIAL)
        val leftCheek = clampBox(lx1, minOf(ly1, ly2), lx2 - lx1, kotlin.math.abs(ly2 - ly1), frameWidth, frameHeight)

        val rx1 = minOf(x(LM_RIGHT_NOSE_ALA), x(LM_RIGHT_CHEEK_OUTER))
        val rx2 = maxOf(x(LM_RIGHT_NOSE_ALA), x(LM_RIGHT_CHEEK_OUTER))
        val ry1 = y(LM_RIGHT_UNDER_EYE)
        val ry2 = y(LM_RIGHT_NASOLABIAL)
        val rightCheek = clampBox(rx1, minOf(ry1, ry2), rx2 - rx1, kotlin.math.abs(ry2 - ry1), frameWidth, frameHeight)

        return AnatomyBoxes(forehead, leftCheek, rightCheek)
    }

    /** Direct port of `centeredFallbackBoxes` -- used when no landmark
     *  detection has ever succeeded yet (this project's standing "no
     *  history" philosophy, same as [RoiCalculator]'s own face-box
     *  fallback). */
    fun centeredFallbackBoxes(frameWidth: Int, frameHeight: Int): AnatomyBoxes {
        val cx = 0.5f * frameWidth
        val forehead = clampBox(cx - 0.12f * frameWidth, 0.18f * frameHeight, 0.24f * frameWidth, 0.10f * frameHeight, frameWidth, frameHeight)
        val leftCheek = clampBox(cx - 0.30f * frameWidth, 0.32f * frameHeight, 0.12f * frameWidth, 0.10f * frameHeight, frameWidth, frameHeight)
        val rightCheek = clampBox(cx + 0.18f * frameWidth, 0.32f * frameHeight, 0.12f * frameWidth, 0.10f * frameHeight, frameWidth, frameHeight)
        return AnatomyBoxes(forehead, leftCheek, rightCheek)
    }

    /** Port of `clampBox`, adapted for 0-based Android pixel coordinates
     *  (MATLAB's own `max(1, round(...))` is 1-based -- `maxOf(0, ...)`
     *  below is the correct equivalent, not a literal same-numbers copy).
     *  Field arithmetic, not a 4-arg `Rect(...)` constructor call, matching
     *  [CroppedDetectionStrategy]'s own `makeRect`-style convention (that
     *  constructor is a no-op under this project's plain-JUnit harness,
     *  `isReturnDefaultValues=true`). */
    fun clampBox(rawX: Float, rawY: Float, rawW: Float, rawH: Float, frameWidth: Int, frameHeight: Int): Rect {
        var x1 = maxOf(0, Math.round(rawX))
        var y1 = maxOf(0, Math.round(rawY))
        var x2 = minOf(frameWidth, Math.round(rawX + rawW))
        var y2 = minOf(frameHeight, Math.round(rawY + rawH))
        x2 = maxOf(x2, x1 + 1)
        y2 = maxOf(y2, y1 + 1)
        val r = Rect()
        r.left = x1
        r.top = y1
        r.right = x2
        r.bottom = y2
        return r
    }
}
