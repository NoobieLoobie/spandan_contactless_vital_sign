package com.spandan.app.camera

import android.graphics.Rect
import com.spandan.app.signal.RgbSample

/**
 * [Segment 36] Direct Kotlin port of
 * matlab/src/roi/faceMeshAnatomyROIExtraction.m's `poolSkinPixels`/
 * `ycbcrSkinMask` -- classical YCbCr skin-tone pixel filtering (Chai & Ngan
 * 1999-style range, Cb in [77,127], Cr in [133,173]) applied per-region
 * before averaging, with the same "if any region's skin-filtered count is
 * below 10% of its box, skip the filter for ALL regions this frame" fallback
 * the MATLAB original uses (simpler and more conservative than a per-box
 * fallback -- avoids mixing filtered and unfiltered pixel populations within
 * one frame's average, same reasoning as the MATLAB source's own comment).
 *
 * Operates on a plain `IntArray` of ARGB_8888 pixels (as
 * `android.graphics.Bitmap.getPixels` returns), not a `Bitmap` object
 * directly -- so this stays exercisable from a plain-JUnit test with
 * synthetic pixel data, the same "keep the pure math testable" discipline
 * [AnatomyRoiCalculator] and [RoiPixelAverager] already use. The one Android
 * API call ([android.graphics.Bitmap.getPixels]) lives in the analyzer that
 * calls this object, not here.
 *
 * YCbCr formula: ITU-R BT.601 digital range (MATLAB's own `rgb2ycbcr`
 * convention), applied directly to 0-255 R/G/B -- NOT the same coefficients
 * as [RoiPixelAverager]'s YUV<->RGB conversion (that one converts sensor
 * YUV *to* RGB; this one converts already-RGB pixels *to* YCbCr for the skin
 * mask, a different standard matrix, verified against MATLAB's own
 * documented `rgb2ycbcr` coefficients before writing this).
 */
object AnatomyRoiPixelAverager {

    private const val CB_LO = 77
    private const val CB_HI = 127
    private const val CR_LO = 133
    private const val CR_HI = 173
    private const val MIN_SKIN_FRACTION = 0.10

    /**
     * [pixels] is a full-frame (or at least full-bounding-box) ARGB_8888
     * pixel array, [pixels.size] == [bitmapWidth] * [bitmapHeight], row-major
     * (same layout `Bitmap.getPixels(pixels, 0, width, 0, 0, width, height)`
     * produces). [boxes] are pixel-space rects already clamped to
     * [0, bitmapWidth) x [0, bitmapHeight) (as [AnatomyRoiCalculator.clampBox]
     * guarantees). Returns null only if every box is empty/out of bounds.
     */
    fun averageRgb(pixels: IntArray, bitmapWidth: Int, bitmapHeight: Int, boxes: List<Rect>, sensorTimestampNs: Long = 0L): RgbSample? {
        val validBoxes = boxes.mapNotNull { clampToBitmap(it, bitmapWidth, bitmapHeight) }
        if (validBoxes.isEmpty()) return null

        // Pass 1: does ANY box fall below the 10% skin-pixel floor?
        // NOT box.width()/box.height() -- confirmed this session that,
        // unlike Rect's left/top/right/bottom (real public fields, always
        // safe), width()/height() are COMPUTED METHODS on the real
        // android.graphics.Rect class and are ALSO stubbed to return 0
        // under this project's plain-JUnit harness (isReturnDefaultValues=
        // true) -- silently zeroing `total` and making this whole skin-
        // fraction check a no-op in every unit test, though harmless on a
        // real device where these methods work correctly. Field arithmetic
        // avoids the trap entirely, same discipline as [makeRect]-style
        // Rect construction elsewhere in this package.
        var anyTooFew = false
        for (box in validBoxes) {
            val total = (box.right - box.left) * (box.bottom - box.top)
            if (total <= 0) continue
            var skinCount = 0
            forEachPixel(pixels, bitmapWidth, box) { r, g, b ->
                if (isSkin(r, g, b)) skinCount++
            }
            if (skinCount < MIN_SKIN_FRACTION * total) anyTooFew = true
        }

        // Pass 2: pool (concatenate-then-average, matching the MATLAB
        // convention) using the skin mask unless Pass 1 found any box too
        // sparse, in which case every box falls back to its raw average.
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (box in validBoxes) {
            forEachPixel(pixels, bitmapWidth, box) { r, g, b ->
                if (anyTooFew || isSkin(r, g, b)) {
                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                }
            }
        }

        if (count == 0) return null
        return RgbSample(
            timestampMs = System.currentTimeMillis(),
            red = sumR.toFloat() / count,
            green = sumG.toFloat() / count,
            blue = sumB.toFloat() / count,
            clippedPixels = 0,
            sampledPixels = count,
            sensorTimestampNs = sensorTimestampNs
        )
    }

    private inline fun forEachPixel(pixels: IntArray, bitmapWidth: Int, box: Rect, action: (r: Int, g: Int, b: Int) -> Unit) {
        var y = box.top
        while (y < box.bottom) {
            var x = box.left
            while (x < box.right) {
                val p = pixels[y * bitmapWidth + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                action(r, g, b)
                x++
            }
            y++
        }
    }

    private fun clampToBitmap(box: Rect, bitmapWidth: Int, bitmapHeight: Int): Rect? {
        val left = box.left.coerceIn(0, bitmapWidth - 1)
        val top = box.top.coerceIn(0, bitmapHeight - 1)
        val right = box.right.coerceIn(left + 1, bitmapWidth)
        val bottom = box.bottom.coerceIn(top + 1, bitmapHeight)
        if (right <= left || bottom <= top) return null
        val r = Rect()
        r.left = left
        r.top = top
        r.right = right
        r.bottom = bottom
        return r
    }

    /** ITU-R BT.601 digital-range RGB->YCbCr (MATLAB `rgb2ycbcr` convention),
     *  Cb/Cr only -- Y is unused by the skin-tone gate. */
    private fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val cb = 128.0 + (-37.797 * r - 74.203 * g + 112.0 * b) / 255.0
        val cr = 128.0 + (112.0 * r - 93.786 * g - 18.214 * b) / 255.0
        return cb in CB_LO.toDouble()..CB_HI.toDouble() && cr in CR_LO.toDouble()..CR_HI.toDouble()
    }
}
