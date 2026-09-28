package com.spandan.app.camera

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Plain Kotlin/JUnit -- verifies [CoordinateMapper.sensorRectToRotatedRect]
 * (added for Segment 18's [OpticalFlowFaceTracker]) is a genuine inverse of
 * the existing, already-relied-upon [CoordinateMapper.rotatedRectToSensorRect]
 * via a round-trip property test, since no physical device was available
 * this session to verify the new function any other way -- same "verify
 * before trusting real data" discipline as every other numeric port here.
 */
class CoordinateMapperTest {

    @Test
    fun roundTripsForAllFourRotationsOnRandomRects() {
        val rng = Random(7)
        val sensorWidth = 640
        val sensorHeight = 480

        for (rotation in listOf(0, 90, 180, 270)) {
            // The "rotated" space's own extent is swapped for 90/270 -- same
            // convention FaceAnalyzer.kt itself uses to derive rotatedImageWidth/Height.
            val rotatedWidth = if (rotation == 90 || rotation == 270) sensorHeight else sensorWidth
            val rotatedHeight = if (rotation == 90 || rotation == 270) sensorWidth else sensorHeight

            repeat(200) {
                val left = rng.nextInt(0, rotatedWidth - 10)
                val top = rng.nextInt(0, rotatedHeight - 10)
                val right = rng.nextInt(left + 1, rotatedWidth)
                val bottom = rng.nextInt(top + 1, rotatedHeight)
                val original = Rect(left, top, right, bottom)

                val sensorRect = CoordinateMapper.rotatedRectToSensorRect(original, rotation, sensorWidth, sensorHeight)
                val roundTripped = CoordinateMapper.sensorRectToRotatedRect(sensorRect, rotation, sensorWidth, sensorHeight)

                // rotatedRectToSensorRect clamps to sensor bounds, so an
                // exact round trip is only guaranteed when the original rect
                // was already safely inside bounds (never negative/over-max
                // after mapping) -- true by construction here since we
                // generated `original` strictly inside [0, rotatedWidth/Height).
                assertEquals("rotation=$rotation left", original.left, roundTripped.left)
                assertEquals("rotation=$rotation top", original.top, roundTripped.top)
                assertEquals("rotation=$rotation right", original.right, roundTripped.right)
                assertEquals("rotation=$rotation bottom", original.bottom, roundTripped.bottom)
            }
        }
    }

    /** [Segment 35 Phase 3] Round-trip property test for
     *  [CoordinateMapper.viewRectToRotatedRect], the inverse of the
     *  already-relied-upon [CoordinateMapper.rotatedRectToViewRect] --
     *  same discipline as the sensor/rotated round-trip test above, since
     *  no physical device was available this session. Front-camera only
     *  (the mirrored branch): that is the only configuration this app
     *  actually runs (DEFAULT_FRONT_CAMERA, MainActivity.kt). */
    @Test
    fun viewRectToRotatedRect_roundTripsRotatedRectToViewRect() {
        val rng = Random(11)
        val srcWidth = 480
        val srcHeight = 640
        val viewWidth = 1080
        val viewHeight = 2200

        repeat(500) {
            val left = rng.nextInt(0, srcWidth - 10)
            val top = rng.nextInt(0, srcHeight - 10)
            val right = rng.nextInt(left + 1, srcWidth)
            val bottom = rng.nextInt(top + 1, srcHeight)
            val original = Rect(left, top, right, bottom)

            val viewRect = CoordinateMapper.rotatedRectToViewRect(
                original, srcWidth, srcHeight, viewWidth, viewHeight, isFrontCamera = true
            )
            val roundTripped = CoordinateMapper.viewRectToRotatedRect(
                viewRect, srcWidth, srcHeight, viewWidth, viewHeight, isFrontCamera = true
            )

            // Sub-pixel rounding (Float RectF -> Int Rect at each direction)
            // means an exact match isn't guaranteed; within 1px is.
            assertTrue("left ${original.left} vs ${roundTripped.left}", abs(original.left - roundTripped.left) <= 1)
            assertTrue("top ${original.top} vs ${roundTripped.top}", abs(original.top - roundTripped.top) <= 1)
            assertTrue("right ${original.right} vs ${roundTripped.right}", abs(original.right - roundTripped.right) <= 1)
            assertTrue("bottom ${original.bottom} vs ${roundTripped.bottom}", abs(original.bottom - roundTripped.bottom) <= 1)
        }
    }

    @Test
    fun viewRectToRotatedRect_degenerateDimensionsReturnEmptyRect() {
        // Compares individual int fields, not Rect.equals()/assertEquals on
        // two Rect instances -- this project's own established convention
        // in the test above (and Segment 28's own note on android.graphics.Rect
        // not behaving reliably in this local-JVM unit test environment
        // beyond simple field access).
        val r = RectF(0f, 0f, 10f, 10f)
        val a = CoordinateMapper.viewRectToRotatedRect(r, 0, 100, 100, 100, true)
        assertEquals(0, a.left); assertEquals(0, a.top); assertEquals(0, a.right); assertEquals(0, a.bottom)
        val b = CoordinateMapper.viewRectToRotatedRect(r, 100, 100, 0, 100, true)
        assertEquals(0, b.left); assertEquals(0, b.top); assertEquals(0, b.right); assertEquals(0, b.bottom)
    }
}
