package com.spandan.app.camera

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.spandan.app.oximetry.OximetryMath
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

        // NOT box.width()/box.height() -- confirmed this session that,
        // unlike Rect's left/top/right/bottom (real public fields, always
        // safe), width()/height() are COMPUTED METHODS on the real
        // android.graphics.Rect class and are ALSO stubbed to return 0
        // under this project's plain-JUnit harness (isReturnDefaultValues=
        // true). Field arithmetic avoids the trap entirely, same discipline
        // as [makeRect]-style Rect construction elsewhere in this package.
        val accumulators = validBoxes.map { box ->
            val acc = BoxAccumulator()
            var y = box.top
            while (y < box.bottom) {
                var x = box.left
                while (x < box.right) {
                    val p = pixels[y * bitmapWidth + x]
                    acc.add((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                    x++
                }
                y++
            }
            acc
        }
        return pool(accumulators, sensorTimestampNs)
    }

    /**
     * [Segment 36 on-device fix] Same skin-masked pooling as [averageRgb],
     * but read straight from the camera's YUV_420_888 planes over ONLY the
     * ROI boxes' own pixels -- no full-frame ARGB [android.graphics.Bitmap]
     * conversion, rotation, or `getPixels` copy. The previous per-frame path
     * converted the whole 1280x720 analysis frame to a Bitmap on every frame
     * (twice on the rotated path) just to average ~19k ROI pixels, which is
     * what throttled this analyzer's sample rate on a Galaxy A35. The skin
     * mask is per-pixel and order-independent, so iterating each box in
     * SENSOR space (after [CoordinateMapper.rotatedRectToSensorRect]) visits
     * exactly the same pixel set as iterating it in upright space. YUV->RGB
     * is the same BT.601 formula + truncation [MediaPipeImageConverter] used
     * to build the old Bitmap, so the pooled values match the old path's.
     */
    @ExperimentalGetImage
    fun averageRgbFromYuv(imageProxy: ImageProxy, sensorBoxes: List<Rect>): RgbSample? {
        val image = imageProxy.image ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val accumulators = ArrayList<BoxAccumulator>(sensorBoxes.size)
        for (raw in sensorBoxes) {
            val box = clampToBitmap(raw, width, height) ?: continue
            val acc = BoxAccumulator()
            var y = box.top
            while (y < box.bottom) {
                val uvRow = y / 2
                var x = box.left
                while (x < box.right) {
                    val uvCol = x / 2
                    val yVal = yBuffer.get(y * yRowStride + x * yPixelStride).toInt() and 0xFF
                    val uVal = (uBuffer.get(uvRow * uRowStride + uvCol * uPixelStride).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get(uvRow * vRowStride + uvCol * vPixelStride).toInt() and 0xFF) - 128
                    val r = (yVal + 1.402 * vVal).coerceIn(0.0, 255.0).toInt()
                    val g = (yVal - 0.344136 * uVal - 0.714136 * vVal).coerceIn(0.0, 255.0).toInt()
                    val b = (yVal + 1.772 * uVal).coerceIn(0.0, 255.0).toInt()
                    acc.add(r, g, b)
                    x++
                }
                y++
            }
            accumulators.add(acc)
        }
        if (accumulators.isEmpty()) return null
        return pool(accumulators, imageProxy.imageInfo.timestamp)
    }

    /** One box's running sums, for BOTH candidate pixel populations (skin-
     *  masked and raw) in a single pass -- which one is pooled is decided
     *  across all boxes afterwards in [pool], exactly the old two-pass
     *  "any box under the 10% skin floor -> no mask for any box" rule. */
    internal class BoxAccumulator {
        var total = 0
        var skinCount = 0
        var skinR = 0L
        var skinG = 0L
        var skinB = 0L
        var skinClipped = 0
        var rawR = 0L
        var rawG = 0L
        var rawB = 0L
        var rawClipped = 0

        fun add(r: Int, g: Int, b: Int) {
            val clipped = r >= OximetryMath.CLIP_THRESHOLD || g >= OximetryMath.CLIP_THRESHOLD || b >= OximetryMath.CLIP_THRESHOLD
            total++
            rawR += r
            rawG += g
            rawB += b
            if (clipped) rawClipped++
            if (isSkin(r, g, b)) {
                skinCount++
                skinR += r
                skinG += g
                skinB += b
                if (clipped) skinClipped++
            }
        }
    }

    internal fun pool(accumulators: List<BoxAccumulator>, sensorTimestampNs: Long): RgbSample? {
        val anyTooFew = accumulators.any { it.total > 0 && it.skinCount < MIN_SKIN_FRACTION * it.total }
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        var clipped = 0
        for (acc in accumulators) {
            if (anyTooFew) {
                sumR += acc.rawR; sumG += acc.rawG; sumB += acc.rawB
                count += acc.total; clipped += acc.rawClipped
            } else {
                sumR += acc.skinR; sumG += acc.skinG; sumB += acc.skinB
                count += acc.skinCount; clipped += acc.skinClipped
            }
        }
        if (count == 0) return null
        return RgbSample(
            timestampMs = System.currentTimeMillis(),
            red = sumR.toFloat() / count,
            green = sumG.toFloat() / count,
            blue = sumB.toFloat() / count,
            clippedPixels = clipped,
            sampledPixels = count,
            sensorTimestampNs = sensorTimestampNs
        )
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
    internal fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val cb = 128.0 + (-37.797 * r - 74.203 * g + 112.0 * b) / 255.0
        val cr = 128.0 + (112.0 * r - 93.786 * g - 18.214 * b) / 255.0
        return cb in CB_LO.toDouble()..CB_HI.toDouble() && cr in CR_LO.toDouble()..CR_HI.toDouble()
    }
}
