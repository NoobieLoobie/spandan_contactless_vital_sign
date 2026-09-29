package com.spandan.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain Kotlin/JUnit -- verifies [AnatomyRoiCalculator.landmarksToBoxes]
 * against the same landmark geometry
 * matlab/src/roi/faceMeshAnatomyROIExtraction.m's own `landmarksToBoxes`
 * uses, since no physical device was available this session to verify the
 * Android port any other way (same "verify before trusting" discipline as
 * [CoordinateMapperTest]'s own round-trip property test).
 */
class AnatomyRoiCalculatorTest {

    /** A plausible frontal-face landmark layout, in pixel coordinates of a
     *  480 (wide) x 640 (tall) upright frame -- loosely mirrors a real
     *  MediaPipe FaceMesh detection's rough proportions (forehead above
     *  brows, cheeks below eyes and above the nasolabial fold), not an
     *  exact capture. */
    private fun plausibleLandmarks(frameWidth: Int, frameHeight: Int): Map<Int, FloatArray> {
        val cx = frameWidth / 2f
        return mapOf(
            AnatomyRoiCalculator.LM_FOREHEAD_TOP to floatArrayOf(cx, 0.15f * frameHeight),
            AnatomyRoiCalculator.LM_LEFT_BROW_OUTER to floatArrayOf(cx - 0.15f * frameWidth, 0.30f * frameHeight),
            AnatomyRoiCalculator.LM_RIGHT_BROW_OUTER to floatArrayOf(cx + 0.15f * frameWidth, 0.30f * frameHeight),
            AnatomyRoiCalculator.LM_LEFT_UNDER_EYE to floatArrayOf(cx - 0.18f * frameWidth, 0.38f * frameHeight),
            AnatomyRoiCalculator.LM_LEFT_NASOLABIAL to floatArrayOf(cx - 0.12f * frameWidth, 0.55f * frameHeight),
            AnatomyRoiCalculator.LM_LEFT_CHEEK_OUTER to floatArrayOf(cx - 0.30f * frameWidth, 0.45f * frameHeight),
            AnatomyRoiCalculator.LM_LEFT_NOSE_ALA to floatArrayOf(cx - 0.08f * frameWidth, 0.48f * frameHeight),
            AnatomyRoiCalculator.LM_RIGHT_UNDER_EYE to floatArrayOf(cx + 0.18f * frameWidth, 0.38f * frameHeight),
            AnatomyRoiCalculator.LM_RIGHT_NASOLABIAL to floatArrayOf(cx + 0.12f * frameWidth, 0.55f * frameHeight),
            AnatomyRoiCalculator.LM_RIGHT_CHEEK_OUTER to floatArrayOf(cx + 0.30f * frameWidth, 0.45f * frameHeight),
            AnatomyRoiCalculator.LM_RIGHT_NOSE_ALA to floatArrayOf(cx + 0.08f * frameWidth, 0.48f * frameHeight)
        )
    }

    @Test
    fun foreheadBoxSitsAboveEyebrowLineAndBelowHairline() {
        val w = 480
        val h = 640
        val landmarks = plausibleLandmarks(w, h)
        val boxes = AnatomyRoiCalculator.landmarksToBoxes(landmarks, w, h)

        val browY = (landmarks.getValue(AnatomyRoiCalculator.LM_LEFT_BROW_OUTER)[1] +
            landmarks.getValue(AnatomyRoiCalculator.LM_RIGHT_BROW_OUTER)[1]) / 2f
        val topY = landmarks.getValue(AnatomyRoiCalculator.LM_FOREHEAD_TOP)[1]

        // Forehead box's bottom edge is the eyebrow line (fy2 in the MATLAB
        // source); its top edge is 15% of the way down from foreheadTop
        // toward that eyebrow line -- so it must be strictly between the two.
        assertTrue("forehead top (${boxes.forehead.top}) should be below foreheadTop landmark ($topY)", boxes.forehead.top > topY)
        assertTrue("forehead bottom (${boxes.forehead.bottom}) should be at/near the brow line ($browY)", boxes.forehead.bottom <= browY + 1)
    }

    @Test
    fun leftAndRightCheekBoxesAreMirroredAroundCenter() {
        val w = 480
        val h = 640
        val landmarks = plausibleLandmarks(w, h)
        val boxes = AnatomyRoiCalculator.landmarksToBoxes(landmarks, w, h)

        val cx = w / 2f
        val leftCenterOffset = cx - (boxes.leftCheek.left + boxes.leftCheek.right) / 2f
        val rightCenterOffset = (boxes.rightCheek.left + boxes.rightCheek.right) / 2f - cx
        assertEquals(leftCenterOffset.toDouble(), rightCenterOffset.toDouble(), 1.0)

        // Cheeks must sit below the eyes and above the mouth/nasolabial fold
        // -- i.e. strictly inside [underEyeY, nasolabialY] vertically.
        assertTrue(boxes.leftCheek.top >= landmarks.getValue(AnatomyRoiCalculator.LM_LEFT_UNDER_EYE)[1].toInt() - 1)
        assertTrue(boxes.leftCheek.bottom <= landmarks.getValue(AnatomyRoiCalculator.LM_LEFT_NASOLABIAL)[1].toInt() + 1)
    }

    @Test
    fun missingRequiredLandmarkFallsBackToCenteredBoxes() {
        val w = 480
        val h = 640
        val incomplete = plausibleLandmarks(w, h).toMutableMap()
        incomplete.remove(AnatomyRoiCalculator.LM_RIGHT_NASOLABIAL)

        val boxes = AnatomyRoiCalculator.landmarksToBoxes(incomplete, w, h)
        val fallback = AnatomyRoiCalculator.centeredFallbackBoxes(w, h)

        // NOT assertEquals(rect1, rect2): android.graphics.Rect.equals() is
        // ALSO stubbed under this project's plain-JUnit harness
        // (isReturnDefaultValues=true) -- confirmed directly (both sides
        // printed as "Rect<null>" yet compared unequal) -- so field-by-field
        // comparison is required, same discipline as [AnatomyRoiCalculator]'s
        // own `makeRect`-style constructor workaround.
        assertRectFieldsEqual(fallback.forehead, boxes.forehead)
        assertRectFieldsEqual(fallback.leftCheek, boxes.leftCheek)
        assertRectFieldsEqual(fallback.rightCheek, boxes.rightCheek)
    }

    private fun assertRectFieldsEqual(expected: android.graphics.Rect, actual: android.graphics.Rect) {
        assertEquals(expected.left, actual.left)
        assertEquals(expected.top, actual.top)
        assertEquals(expected.right, actual.right)
        assertEquals(expected.bottom, actual.bottom)
    }

    @Test
    fun clampBoxNeverProducesAZeroOrNegativeSizedRect() {
        // Degenerate input (a box entirely outside the frame, or zero width)
        // must still clamp to at least a 1px box, never left>=right.
        val box = AnatomyRoiCalculator.clampBox(rawX = -50f, rawY = -50f, rawW = 0f, rawH = 0f, frameWidth = 100, frameHeight = 100)
        assertTrue(box.right > box.left)
        assertTrue(box.bottom > box.top)
        assertTrue(box.left >= 0 && box.top >= 0)
    }

    @Test
    fun clampBoxClampsToFrameBounds() {
        val box = AnatomyRoiCalculator.clampBox(rawX = 90f, rawY = 90f, rawW = 50f, rawH = 50f, frameWidth = 100, frameHeight = 100)
        assertTrue(box.right <= 100)
        assertTrue(box.bottom <= 100)
    }

    @Test
    fun centeredFallbackBoxesAreWithinFrameAndRoughlyCentered() {
        val w = 480
        val h = 640
        val boxes = AnatomyRoiCalculator.centeredFallbackBoxes(w, h)
        for (box in listOf(boxes.forehead, boxes.leftCheek, boxes.rightCheek)) {
            assertTrue(box.left >= 0 && box.right <= w)
            assertTrue(box.top >= 0 && box.bottom <= h)
        }
        // Forehead sits centered horizontally.
        val foreheadCenter = (boxes.forehead.left + boxes.forehead.right) / 2f
        assertEquals((w / 2).toDouble(), foreheadCenter.toDouble(), 2.0)
    }
}
