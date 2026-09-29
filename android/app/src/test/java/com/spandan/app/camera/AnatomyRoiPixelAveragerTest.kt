package com.spandan.app.camera

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain Kotlin/JUnit -- verifies [AnatomyRoiPixelAverager.averageRgb] against
 * synthetic ARGB pixel arrays (no real Bitmap/device needed, same "keep the
 * pure math testable" discipline the object's own KDoc describes). Skin-tone
 * reference colors below were picked and checked by hand against the ITU-R
 * BT.601 rgb2ycbcr formula this object itself implements, not assumed.
 *
 * Uses [makeRect] (no-arg constructor + field writes), NOT `Rect(l,t,r,b)`
 * directly -- confirmed the hard way this session: the 4-arg constructor is
 * a no-op under this project's plain-JUnit harness
 * (`isReturnDefaultValues = true`, android/app/build.gradle.kts), silently
 * producing a (0,0,0,0) rect and making every test below fail for the wrong
 * reason before this fix. Same known issue [CroppedDetectionStrategy]'s own
 * `makeRect` helper already documents.
 */
class AnatomyRoiPixelAveragerTest {

    private fun makeRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        val r = Rect()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        return r
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun solidPixels(width: Int, height: Int, color: Int): IntArray =
        IntArray(width * height) { color }

    // A mid-tone skin-like color: R=200,G=150,B=120 -> Cb ~= 107.4, Cr ~= 152.1,
    // both inside [77,127]/[133,173] -- computed from the BT.601 formula
    // (verified with a standalone script before writing this test, not
    // guessed).
    private val skinColor = argb(200, 150, 120)

    // A saturated blue: R=20,G=30,B=220 -> Cb ~= 212.9, Cr ~= 110.0 -- well
    // outside both ranges, a clean "not skin" reference.
    private val nonSkinColor = argb(20, 30, 220)

    @Test
    fun poolsMultipleSkinColoredBoxesIntoOneAverage() {
        val width = 20
        val height = 20
        val pixels = solidPixels(width, height, skinColor)
        val box1 = makeRect(0, 0, 5, 5)
        val box2 = makeRect(10, 10, 15, 15)

        val sample = AnatomyRoiPixelAverager.averageRgb(pixels, width, height, listOf(box1, box2))

        assertNotNull(sample)
        assertEquals(200.0, sample!!.red.toDouble(), 0.5)
        assertEquals(150.0, sample.green.toDouble(), 0.5)
        assertEquals(120.0, sample.blue.toDouble(), 0.5)
        assertEquals(50, sample.sampledPixels) // two 5x5 boxes = 50 pixels, all skin
    }

    @Test
    fun nonSkinPixelsAreExcludedWhenEnoughSkinPixelsExist() {
        val width = 10
        val height = 10
        val pixels = IntArray(width * height) { i ->
            // Left half skin-colored, right half non-skin -- >=10% skin in
            // this single box either way, so the filter should apply and
            // only the skin half should be averaged.
            val x = i % width
            if (x < width / 2) skinColor else nonSkinColor
        }
        val box = makeRect(0, 0, width, height)

        val sample = AnatomyRoiPixelAverager.averageRgb(pixels, width, height, listOf(box))

        assertNotNull(sample)
        assertEquals(200.0, sample!!.red.toDouble(), 0.5)
        assertEquals(50, sample.sampledPixels) // only the left (skin) half of a 10x10 box
    }

    @Test
    fun fallsBackToRawAverageWhenAnyBoxHasTooFewSkinPixels() {
        val width = 10
        val height = 10
        val skinBoxPixels = solidPixels(width, height, skinColor)
        // Two separate backing arrays conceptually, but averageRgb takes one
        // shared bitmap-sized array -- lay a "sparse skin" box (only 1 of
        // 100 pixels skin-colored, well under the 10% floor) next to a
        // fully-skin box, in one 20x10 canvas.
        val canvasWidth = 20
        val pixels = IntArray(canvasWidth * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * canvasWidth + x] = skinBoxPixels[y * width + x] // left box: all skin
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isTheOneSkinPixel = (x == 0 && y == 0)
                pixels[y * canvasWidth + (width + x)] = if (isTheOneSkinPixel) skinColor else nonSkinColor
            }
        }
        val leftBox = makeRect(0, 0, width, height) // fully skin -- would pass alone
        val rightBox = makeRect(width, 0, canvasWidth, height) // 1/100 skin -- below the 10% floor

        val sample = AnatomyRoiPixelAverager.averageRgb(pixels, canvasWidth, height, listOf(leftBox, rightBox))

        assertNotNull(sample)
        // Fallback means EVERY pixel in both boxes counts (200 total), not
        // just the skin-colored ones -- the average must sit strictly
        // between pure skinColor and pure nonSkinColor on every channel.
        assertEquals(200, sample!!.sampledPixels)
        assertTrue("expected a blended red between the two colors, got ${sample.red}", sample.red < 200f && sample.red > 20f)
    }

    @Test
    fun clampsAnOutOfBoundsBoxRatherThanRejectingIt() {
        // Matches faceMeshAnatomyROIExtraction.m's own clampBox: a box that
        // starts beyond the frame is clamped into a small in-bounds region,
        // never rejected outright -- so this returns a (tiny, 1-pixel)
        // sample, not null. See [returnsNullWithNoBoxes] for the actually
        // reachable null case.
        val pixels = solidPixels(10, 10, skinColor)
        val outOfBounds = makeRect(50, 50, 60, 60)
        val sample = AnatomyRoiPixelAverager.averageRgb(pixels, 10, 10, listOf(outOfBounds))
        assertNotNull(sample)
        assertEquals(1, sample!!.sampledPixels)
    }

    @Test
    fun returnsNullWithNoBoxes() {
        val pixels = solidPixels(10, 10, skinColor)
        val sample = AnatomyRoiPixelAverager.averageRgb(pixels, 10, 10, emptyList())
        assertNull(sample)
    }

    @Test
    fun carriesThroughTheProvidedSensorTimestamp() {
        val pixels = solidPixels(4, 4, skinColor)
        val box = makeRect(0, 0, 4, 4)
        val sample = AnatomyRoiPixelAverager.averageRgb(pixels, 4, 4, listOf(box), sensorTimestampNs = 123456789L)
        assertEquals(123456789L, sample!!.sensorTimestampNs)
    }
}
