package com.spandan.app.camera

import android.graphics.Bitmap
import android.media.Image

/**
 * [Segment 30] Converts a YUV_420_888 `android.media.Image` into a full-
 * resolution ARGB_8888 [Bitmap], for MediaPipe's `BitmapImageBuilder`.
 *
 * HONEST STATUS / why this exists at all: the original design for this
 * migration used `com.google.mediapipe.framework.image.MediaImageBuilder`
 * directly on the raw `android.media.Image` (zero-copy, no per-frame
 * conversion) -- MediaImageBuilder's own constructor accepts any
 * `android.media.Image` with no compile-time format check, so this looked
 * safe. Confirmed WRONG on a real device (Galaxy A35, this session): every
 * `detectAsync` call crashed the app with
 * `java.lang.UnsupportedOperationException: Android media image must use
 * RGBA_8888 config` from
 * `com.google.mediapipe.framework.AndroidPacketCreator.createImage` --
 * MediaPipe Tasks Vision's Android packet creator does NOT accept a raw
 * YUV_420_888 image through that path at all, LIVE_STREAM or otherwise. This
 * also explains, in hindsight, why EVERY real-world MediaPipe+CameraX
 * integration this migration's own research pass read (Google's own
 * official mediapipe-samples Android helpers, github.com/namdpran8/Ojas)
 * converts to a `Bitmap` first instead of using `MediaImageBuilder` on the
 * camera's own YUV buffer -- at the time that looked like avoidable
 * overhead; it turned out to be load-bearing.
 *
 * This is the one place this migration's "avoid a per-frame Bitmap/RGB
 * conversion" goal did not survive contact with a real device -- see
 * `android_segment30_mediapipe/docs/Segment30_MediaPipe_Migration.md` for
 * the honest fps cost this adds on top of MediaPipe's own detection time.
 *
 * Same rowStride/pixelStride-aware plane indexing and BT.601 YUV->RGB
 * formula as [RoiPixelAverager.averageRgb] (this project's own established
 * reference for "don't assume planes are contiguous") -- but over EVERY
 * pixel at full resolution (no [RoiPixelAverager.SAMPLE_STRIDE]-style
 * skipping), since MediaPipe's detector needs the whole frame, not a
 * spatial average.
 */
object MediaPipeImageConverter {

    fun yuv420ToArgb8888Bitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val pixels = IntArray(width * height)

        var y = 0
        while (y < height) {
            val uvRow = y / 2
            var x = 0
            while (x < width) {
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                val uvCol = x / 2
                val uIndex = uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride
                val vIndex = uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride

                val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                val uVal = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                // Standard BT.601 YUV -> RGB, same formula as RoiPixelAverager.averageRgb.
                val r = (yVal + 1.402 * vVal).coerceIn(0.0, 255.0).toInt()
                val g = (yVal - 0.344136 * uVal - 0.714136 * vVal).coerceIn(0.0, 255.0).toInt()
                val b = (yVal + 1.772 * uVal).coerceIn(0.0, 255.0).toInt()

                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                x++
            }
            y++
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    /**
     * [Segment 36 on-device fix] Upright (already rotated by
     * [rotationDegrees], same clockwise convention as
     * `Matrix.postRotate(rotationDegrees)`) and subsampled by an integer
     * [step] in ONE pass -- for [AnatomyRoiFaceAnalyzer]'s FaceLandmarker
     * input only. FaceLandmarker returns NORMALIZED landmarks and internally
     * crops/resizes the face to 192-256 px anyway, so feeding it a 640x360
     * frame instead of the full 1280x720 one costs it nothing in landmark
     * precision that matters for ROI boxes, while cutting this conversion's
     * per-pixel work 4x and removing the separate full-size
     * `Bitmap.createBitmap(..., matrix)` rotation copy. ROI pixel averaging
     * never reads this bitmap (see [AnatomyRoiPixelAverager.averageRgbFromYuv]),
     * so the subsampling cannot affect the rPPG signal itself.
     */
    fun yuv420ToUprightArgb8888Bitmap(image: Image, rotationDegrees: Int, step: Int): Bitmap {
        val s = step.coerceAtLeast(1)
        val srcW = image.width / s
        val srcH = image.height / s
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val outW = if (rotation == 90 || rotation == 270) srcH else srcW
        val outH = if (rotation == 90 || rotation == 270) srcW else srcH

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val pixels = IntArray(outW * outH)
        var ys = 0
        while (ys < srcH) {
            val sy = ys * s
            val uvRow = sy / 2
            var xs = 0
            while (xs < srcW) {
                val sx = xs * s
                val uvCol = sx / 2
                val yVal = yBuffer.get(sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 0xFF
                val uVal = (uBuffer.get(uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride).toInt() and 0xFF) - 128
                val r = (yVal + 1.402 * vVal).coerceIn(0.0, 255.0).toInt()
                val g = (yVal - 0.344136 * uVal - 0.714136 * vVal).coerceIn(0.0, 255.0).toInt()
                val b = (yVal + 1.772 * uVal).coerceIn(0.0, 255.0).toInt()

                val outIndex = uprightIndex(xs, ys, srcW, srcH, outW, rotation)
                pixels[outIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                xs++
            }
            ys++
        }

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bitmap
    }

    /** Where sensor pixel ([x], [y]) of a [srcW]x[srcH] buffer lands in the
     *  upright buffer (row-major, width [outW]) after a clockwise rotation --
     *  the forward form of [CoordinateMapper.rotatedRectToSensorRect]'s
     *  mapping. Pure index math, unit-tested directly. */
    internal fun uprightIndex(x: Int, y: Int, srcW: Int, srcH: Int, outW: Int, rotation: Int): Int = when (rotation) {
        90 -> x * outW + (srcH - 1 - y)
        180 -> (srcH - 1 - y) * outW + (srcW - 1 - x)
        270 -> (srcW - 1 - x) * outW + y
        else -> y * outW + x
    }
}
