package com.spandan.app.oximetry

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

/**
 * [Segment 35 Phase 3] Crops [OximeterGuideBox]'s region out of one RGBA_8888
 * analysis frame (Segment 35 Phase 2 item 4's own output format -- a single
 * interleaved R/G/B/A plane, so this is a plain sub-rect byte read, no YUV
 * conversion) and rotates it upright by [rotationDegrees].
 *
 * Analysis frames are NOT mirrored -- only PreviewView's own rendering is
 * (docs/Segment35_Accuracy_Research_and_Plan.md Phase 3's own key fact) --
 * so this crop is never mirrored either: an oximeter's digits, captured
 * from the raw sensor buffer, already read left-to-right correctly once
 * rotated upright. No un-mirror step is needed or applied here.
 *
 * [sensorCropRect] must already be in the RAW SENSOR buffer's coordinate
 * space (the caller maps [OximeterGuideBox]'s view-space rect there via
 * `CoordinateMapper.viewRectToRotatedRect` then `rotatedRectToSensorRect`,
 * the same two-step path every other ROI in this app takes).
 */
object OximeterInset {

    @ExperimentalGetImage
    fun extractUprightCrop(imageProxy: ImageProxy, sensorCropRect: Rect, rotationDegrees: Int): Bitmap? {
        val image = imageProxy.image ?: return null
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = if (plane.pixelStride > 0) plane.pixelStride else 4
        val capacity = buffer.capacity()

        val left = sensorCropRect.left.coerceIn(0, imageProxy.width - 1)
        val top = sensorCropRect.top.coerceIn(0, imageProxy.height - 1)
        val right = sensorCropRect.right.coerceIn(left + 1, imageProxy.width)
        val bottom = sensorCropRect.bottom.coerceIn(top + 1, imageProxy.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowBase = (top + y) * rowStride
            for (x in 0 until w) {
                val idx = rowBase + (left + x) * pixelStride
                if (idx + 2 >= capacity) continue
                val r = buffer.get(idx).toInt() and 0xFF
                val g = buffer.get(idx + 1).toInt() and 0xFF
                val b = buffer.get(idx + 2).toInt() and 0xFF
                // ARGB_8888, opaque (alpha = 0xFF) -- source RGBA's own alpha
                // byte is unused, same as every other RGBA consumer in this
                // app (RoiPixelAverager only reads R/G/B too).
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val cropped = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return cropped
        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, w, h, matrix, true)
    }
}
