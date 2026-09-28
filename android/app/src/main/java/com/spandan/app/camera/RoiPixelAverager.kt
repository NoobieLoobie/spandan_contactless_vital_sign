package com.spandan.app.camera

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.spandan.app.oximetry.OximetryMath
import com.spandan.app.signal.RgbSample

/**
 * Spatially averages R/G/B pixel intensities inside an ROI rect, sampled
 * directly from the pixel planes of a CameraX ImageProxy -- no Bitmap/JPEG
 * round-trip needed.
 *
 * [Segment 35 Phase 2 item 4] Reads `OUTPUT_IMAGE_FORMAT_RGBA_8888` planes
 * (single interleaved plane, R/G/B/A byte order, per CameraX's own contract
 * for that output format) instead of the prior YUV_420_888 + BT.601
 * conversion. Two independent reasons, both from docs/Segment35_Accuracy_
 * Research_and_Plan.md's own Phase 2 item 4: (a) it removes a YUV->RGB
 * conversion step entirely -- one less place for rounding/clipping to add
 * noise upstream of CHROM/POS -- and (b) it is the same format Segment 30's
 * own "future re-attempt" note flagged as removing that segment's 71ms
 * YUV->Bitmap conversion tax, relevant again if/when a MediaPipe-based ROI
 * (Phase 1) is ever ported to Android. [SAMPLE_STRIDE] also drops from 2 to
 * 1 (denser sampling) per the same plan item, now that RGBA reads are a
 * single-plane lookup instead of three separate plane lookups per pixel.
 *
 * This IS real, permanent code: spatial averaging is plain arithmetic and
 * reading a documented pixel format is not a tuned DSP parameter, so fixed
 * constants here are fine -- unlike CHROM/POS/bandpass coefficients
 * elsewhere in this project, which must stay out of this codebase until the
 * MATLAB side finalizes them.
 */
object RoiPixelAverager {

    // [Segment 35 Phase 2 item 4] 1 (every pixel), down from 2 -- more
    // sampled pixels per ROI, closer to what Phase 1 validated offline
    // (pixel count, not placement, was the single factor Segment 7 Task
    // H/J's notch-confidence investigation could confirm). Still a
    // performance knob, not a signal-processing parameter.
    private const val SAMPLE_STRIDE = 1

    /** Bytes per pixel in `OUTPUT_IMAGE_FORMAT_RGBA_8888`'s single plane. */
    private const val RGBA_BYTES_PER_PIXEL = 4

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

        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = if (plane.pixelStride > 0) plane.pixelStride else RGBA_BYTES_PER_PIXEL
        val capacity = buffer.capacity()

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
                    val idx = y * rowStride + x * pixelStride
                    if (idx + 2 < capacity) {
                        val r = buffer.get(idx).toInt() and 0xFF
                        val g = buffer.get(idx + 1).toInt() and 0xFF
                        val b = buffer.get(idx + 2).toInt() and 0xFF

                        sumR += r
                        sumG += g
                        sumB += b
                        // [Segment 34] clipped-pixel count for the oximetry
                        // capture mode's exposure check / calibration CSV --
                        // RGBA values are already discrete 0-255, no
                        // clipping/coercion needed before this check (unlike
                        // the prior YUV->RGB path's floating-point math).
                        if (OximetryMath.isClipped(r.toDouble(), g.toDouble(), b.toDouble())) clipped++
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
