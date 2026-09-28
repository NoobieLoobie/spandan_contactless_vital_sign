package com.spandan.app.camera

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.spandan.app.oximetry.OximetryMath
import com.spandan.app.signal.RgbSample

/**
 * Spatially averages R/G/B pixel intensities inside an ROI rect, sampled
 * directly from the raw YUV_420_888 planes of a CameraX ImageProxy -- no
 * Bitmap/JPEG round-trip needed.
 *
 * [Segment 35 Phase 2 item 4, REVERTED on real-device test] `OUTPUT_IMAGE_
 * FORMAT_RGBA_8888` was tried here (see git history) to remove this file's
 * own YUV->RGB conversion, but it crashes the app on a real device: ML
 * Kit's `InputImage.fromMediaImage()`, which `FaceAnalyzer.analyze()` calls
 * on every frame, ONLY accepts JPEG or YUV_420_888 (confirmed via a real
 * `FATAL EXCEPTION`, Galaxy A35: "Only JPEG and YUV_420_888 are supported
 * now") -- an on-device-only failure this project's plain-JUnit test setup
 * cannot catch (`ImageProxy`/`InputImage` aren't constructible there).
 * Reverted to YUV_420_888 + this file's own BT.601 conversion.
 * [SAMPLE_STRIDE] stays 1 (kept from the RGBA attempt -- denser sampling is
 * still worth it even at YUV's per-pixel 3-plane-lookup cost) and the
 * production pipeline still pools forehead+both cheeks via
 * [averageRgbMultiRect] (also kept) -- neither of those depended on the
 * pixel format.
 *
 * This IS real, permanent code: spatial averaging is plain arithmetic, and
 * the YUV->RGB conversion below is the standard, universal BT.601 colorspace
 * formula (a physical-sensor-format conversion, not a tuned DSP parameter),
 * so fixed constants here are fine -- unlike CHROM/POS/bandpass coefficients
 * elsewhere in this project, which must stay out of this codebase until the
 * MATLAB side finalizes them.
 */
object RoiPixelAverager {

    // [Segment 35 Phase 2 item 4] 1 (every pixel), down from 2 -- more
    // sampled pixels per ROI, closer to what Phase 1 validated offline
    // (pixel count, not placement, was the single factor Segment 7 Task
    // H/J's notch-confidence investigation could confirm).
    private const val SAMPLE_STRIDE = 1

    @ExperimentalGetImage
    fun averageRgb(imageProxy: ImageProxy, roiSensorRect: Rect): RgbSample? =
        averageRgbMultiRect(imageProxy, listOf(roiSensorRect))

    /**
     * Pools RGB pixels from MULTIPLE rects into ONE spatial mean
     * (concatenate-then-average, matching matlab/src/roi/extractROISignals.m's
     * own bilateral-region convention -- see that file's header comment).
     * [Segment 35 Phase 2 item 4]: this is now what the LIVE pipeline calls
     * (FaceAnalyzer.kt passes forehead+leftCheek+rightCheek), not only the
     * debug-only MultiRegionProfilingFaceAnalyzer.kt that introduced this
     * function in the Field Guide Action 1 pilot.
     */
    @ExperimentalGetImage
    fun averageRgbMultiRect(imageProxy: ImageProxy, roiSensorRects: List<Rect>): RgbSample? {
        val image = imageProxy.image ?: return null
        val width = imageProxy.width
        val height = imageProxy.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        var clipped = 0

        for (roiSensorRect in roiSensorRects) {
            val left = roiSensorRect.left.coerceIn(0, width - 1)
            val top = roiSensorRect.top.coerceIn(0, height - 1)
            val right = roiSensorRect.right.coerceIn(left + 1, width)
            val bottom = roiSensorRect.bottom.coerceIn(top + 1, height)
            if (right <= left || bottom <= top) continue

            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                    val uvRow = y / 2
                    val uvCol = x / 2
                    val uIndex = uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride
                    val vIndex = uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride

                    if (yIndex < yBuffer.capacity() && uIndex < uBuffer.capacity() && vIndex < vBuffer.capacity()) {
                        val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                        val uVal = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                        val vVal = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                        // Standard BT.601 YUV -> RGB.
                        val r = yVal + 1.402 * vVal
                        val g = yVal - 0.344136 * uVal - 0.714136 * vVal
                        val b = yVal + 1.772 * uVal

                        sumR += r.coerceIn(0.0, 255.0).toLong()
                        sumG += g.coerceIn(0.0, 255.0).toLong()
                        sumB += b.coerceIn(0.0, 255.0).toLong()
                        // [Segment 34] clipped-pixel count for the oximetry
                        // capture mode's exposure check / calibration CSV.
                        if (OximetryMath.isClipped(r, g, b)) clipped++
                        count++
                    }
                    x += SAMPLE_STRIDE
                }
                y += SAMPLE_STRIDE
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
            sensorTimestampNs = imageProxy.imageInfo.timestamp
        )
    }
}
