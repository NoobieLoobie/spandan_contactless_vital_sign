package com.spandan.app.oximetry

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy

/**
 * [Segment 35 Phase 3] Crops [OximeterGuideBox]'s region out of one analysis
 * frame and rotates it upright by [rotationDegrees].
 *
 * [Segment 35 Phase 2 item 4, REVERTED on real-device test] Originally
 * written against `OUTPUT_IMAGE_FORMAT_RGBA_8888` (a single interleaved
 * plane), but that format crashes ML Kit's face detector on a real device
 * (see `RoiPixelAverager.kt`'s own header for the exact exception) --
 * `MainActivity` reverted the analysis stream to YUV_420_888, so this reads
 * the same three Y/U/V planes and the same BT.601 conversion
 * `RoiPixelAverager` uses, not a single RGBA plane.
 *
 * Analysis frames are NOT mirrored -- only `PreviewView`'s own rendering is
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
        val yPlane = image.planes.getOrNull(0) ?: return null
        val uPlane = image.planes.getOrNull(1) ?: return null
        val vPlane = image.planes.getOrNull(2) ?: return null
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val left = sensorCropRect.left.coerceIn(0, imageProxy.width - 1)
        val top = sensorCropRect.top.coerceIn(0, imageProxy.height - 1)
        val right = sensorCropRect.right.coerceIn(left + 1, imageProxy.width)
        val bottom = sensorCropRect.bottom.coerceIn(top + 1, imageProxy.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            val y = top + row
            for (col in 0 until w) {
                val x = left + col
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                val uvRow = y / 2
                val uvCol = x / 2
                val uIndex = uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride
                val vIndex = uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride
                if (yIndex >= yBuffer.capacity() || uIndex >= uBuffer.capacity() || vIndex >= vBuffer.capacity()) continue

                val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                val uVal = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                // Standard BT.601 YUV -> RGB, same formula RoiPixelAverager uses.
                val r = (yVal + 1.402 * vVal).coerceIn(0.0, 255.0).toInt()
                val g = (yVal - 0.344136 * uVal - 0.714136 * vVal).coerceIn(0.0, 255.0).toInt()
                val b = (yVal + 1.772 * uVal).coerceIn(0.0, 255.0).toInt()

                pixels[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val cropped = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return cropped
        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, w, h, matrix, true)
    }
}
