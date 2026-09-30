package com.spandan.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Segment 36 on-device fix] [MediaPipeImageConverter.uprightIndex] must be
 * the same clockwise rotation `Matrix.postRotate(rotationDegrees)` applied in
 * the old Bitmap path (and the forward form of
 * [CoordinateMapper.rotatedRectToSensorRect]) -- otherwise FaceLandmarker's
 * landmarks would come back in a different frame than the boxes are sampled in.
 */
class MediaPipeImageConverterTest {

    private val srcW = 4
    private val srcH = 3

    private fun outWidth(rotation: Int) = if (rotation == 90 || rotation == 270) srcH else srcW

    @Test
    fun everyRotationIsABijection() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val outW = outWidth(rotation)
            val seen = HashSet<Int>()
            for (y in 0 until srcH) for (x in 0 until srcW) {
                val idx = MediaPipeImageConverter.uprightIndex(x, y, srcW, srcH, outW, rotation)
                assertTrue("rotation $rotation index $idx out of range", idx in 0 until srcW * srcH)
                seen.add(idx)
            }
            assertEquals("rotation $rotation must hit every output pixel once", srcW * srcH, seen.size)
        }
    }

    @Test
    fun clockwiseRotationMovesSensorOriginToTheExpectedCorner() {
        // Sensor top-left (0,0):
        //   90 CW  -> top-right of the upright image
        //   180    -> bottom-right
        //   270 CW -> bottom-left
        assertEquals(srcH - 1, MediaPipeImageConverter.uprightIndex(0, 0, srcW, srcH, srcH, 90))
        assertEquals(srcW * srcH - 1, MediaPipeImageConverter.uprightIndex(0, 0, srcW, srcH, srcW, 180))
        assertEquals((srcW - 1) * srcH, MediaPipeImageConverter.uprightIndex(0, 0, srcW, srcH, srcH, 270))
        assertEquals(0, MediaPipeImageConverter.uprightIndex(0, 0, srcW, srcH, srcW, 0))
    }

    @Test
    fun matchesCoordinateMapperSensorMappingFor270() {
        // CoordinateMapper.rotatedRectToSensorRect at 270: sensor x = W - rotated.bottom..,
        // sensor y = rotated.left.. -> for a single pixel, rotated (xr, yr) <- sensor
        // (W-1-yr, xr). Check the forward map agrees for every pixel.
        val outW = srcH
        for (y in 0 until srcH) for (x in 0 until srcW) {
            val idx = MediaPipeImageConverter.uprightIndex(x, y, srcW, srcH, outW, 270)
            val xr = idx % outW
            val yr = idx / outW
            assertEquals(x, srcW - 1 - yr)
            assertEquals(y, xr)
        }
    }
}
