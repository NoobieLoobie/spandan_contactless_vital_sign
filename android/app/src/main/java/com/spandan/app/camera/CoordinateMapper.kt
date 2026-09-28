package com.spandan.app.camera

import android.graphics.Rect
import android.graphics.RectF

/**
 * Coordinate-space plumbing between three different frames of reference that
 * show up in a CameraX + ML Kit pipeline:
 *
 *  1. The raw sensor buffer (ImageProxy width/height, YUV_420_888 planes) --
 *     what we need to index into for pixel averaging.
 *  2. The "rotated" (upright) image ML Kit's face detector works in. Face
 *     boxes come back in this space, but NOTE: InputImage.fromMediaImage(...)
 *     .width/.height do NOT report this space's dimensions -- they report
 *     the raw, unrotated sensor-buffer dimensions unchanged, regardless of
 *     rotationDegrees (confirmed on-device; see FaceAnalyzer.kt, which
 *     derives the true rotated width/height itself by swapping the raw
 *     sensor dimensions for 90/270 rather than trusting InputImage's
 *     getters). Callers of this object must pass the true rotated
 *     width/height, not InputImage's.
 *  3. On-screen view coordinates, for drawing the overlay on top of a
 *     PreviewView.
 *
 * None of this is DSP -- it's just geometry -- but getting it wrong either
 * crashes (out-of-bounds plane reads) or silently misaligns the ROI overlay,
 * so it's worth its own file with real derivations rather than ad hoc code
 * inline in the analyzer.
 */
object CoordinateMapper {

    /**
     * Maps a rect from ML Kit's rotated-image space back into the raw sensor
     * buffer's space, so [RoiPixelAverager] can index the correct pixels.
     * Derived by hand for all four rotation values; only empirically
     * exercised for the default front-camera-portrait case (rotationDegrees
     * = 90) since that's what this app runs -- see android/README.md for the
     * "not yet verified on a real device" caveat.
     */
    fun rotatedRectToSensorRect(
        rect: Rect,
        rotationDegrees: Int,
        sensorWidth: Int,
        sensorHeight: Int
    ): Rect {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val mapped = when (normalizedRotation) {
            0 -> Rect(rect)
            180 -> Rect(
                sensorWidth - rect.right, sensorHeight - rect.bottom,
                sensorWidth - rect.left, sensorHeight - rect.top
            )
            90 -> Rect(
                rect.top, sensorHeight - rect.right,
                rect.bottom, sensorHeight - rect.left
            )
            270 -> Rect(
                sensorWidth - rect.bottom, rect.left,
                sensorWidth - rect.top, rect.right
            )
            else -> Rect(rect)
        }
        return clampToBounds(mapped, sensorWidth, sensorHeight)
    }

    /**
     * The mathematical inverse of [rotatedRectToSensorRect]: maps a rect
     * FROM the raw sensor buffer's space back INTO ML Kit's rotated-image
     * space. Added for Segment 18's [OpticalFlowFaceTracker], which tracks
     * face-box motion directly on sensor-space luma pixels (that's the only
     * space the Y-plane data lives in) and needs to hand the result back to
     * [FaceAnalyzer] as a rotated-space [Rect] (the space every other part
     * of the pipeline -- [RoiCalculator], the overlay -- already expects).
     *
     * Derived by hand from [rotatedRectToSensorRect]'s own four branches
     * (solve each branch's equations for the rotated-space coordinates
     * given the sensor-space ones) -- verified by a round-trip property
     * test ([CoordinateMapperTest]: forward then inverse returns the
     * original rect, for random rects at all four rotation values), the
     * same "verify before trusting" discipline as every other numeric port
     * in this project, since no physical device was available this session
     * to verify it any other way.
     *
     * `sensorWidth`/`sensorHeight` are the RAW sensor buffer's dimensions
     * (same convention as [rotatedRectToSensorRect]'s own parameters of the
     * same name -- NOT the rotated width/height).
     */
    fun sensorRectToRotatedRect(
        rect: Rect,
        rotationDegrees: Int,
        sensorWidth: Int,
        sensorHeight: Int
    ): Rect {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        return when (normalizedRotation) {
            0 -> Rect(rect)
            180 -> Rect(
                sensorWidth - rect.right, sensorHeight - rect.bottom,
                sensorWidth - rect.left, sensorHeight - rect.top
            )
            90 -> Rect(
                sensorHeight - rect.bottom, rect.left,
                sensorHeight - rect.top, rect.right
            )
            270 -> Rect(
                rect.top, sensorWidth - rect.right,
                rect.bottom, sensorWidth - rect.left
            )
            else -> Rect(rect)
        }
    }

    private fun clampToBounds(r: Rect, width: Int, height: Int): Rect {
        val left = r.left.coerceIn(0, width - 1)
        val top = r.top.coerceIn(0, height - 1)
        val right = r.right.coerceIn(left + 1, width)
        val bottom = r.bottom.coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    /**
     * Maps a rect from the rotated-image coordinate space into on-screen view
     * pixel coordinates, matching a PreviewView using ScaleType.FIT_CENTER
     * (uniform scale, letterboxed, never cropped) plus the horizontal mirror
     * CameraX applies automatically for the front camera preview.
     */
    fun rotatedRectToViewRect(
        rect: Rect,
        srcWidth: Int,
        srcHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean
    ): RectF {
        if (srcWidth <= 0 || srcHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return RectF()

        val scale = minOf(viewWidth.toFloat() / srcWidth, viewHeight.toFloat() / srcHeight)
        val offsetX = (viewWidth - srcWidth * scale) / 2f
        val offsetY = (viewHeight - srcHeight * scale) / 2f

        return if (isFrontCamera) {
            RectF(
                offsetX + (srcWidth - rect.right) * scale,
                offsetY + rect.top * scale,
                offsetX + (srcWidth - rect.left) * scale,
                offsetY + rect.bottom * scale
            )
        } else {
            RectF(
                offsetX + rect.left * scale,
                offsetY + rect.top * scale,
                offsetX + rect.right * scale,
                offsetY + rect.bottom * scale
            )
        }
    }

    /**
     * [Segment 35 Phase 3] The mathematical inverse of [rotatedRectToViewRect]:
     * maps a rect FROM on-screen view pixel coordinates BACK INTO the
     * rotated-image coordinate space (analysis frames are NOT mirrored, only
     * PreviewView's own rendering is -- so this undoes both the FIT_CENTER
     * scale/letterbox AND the front-camera mirror flip [rotatedRectToViewRect]
     * applies). Used to map [com.spandan.app.oximetry.OximeterGuideBox]'s
     * fixed view-space guide rectangle back to a rotated-space rect
     * ([rotatedRectToSensorRect] then takes it the rest of the way to sensor
     * space for the actual pixel crop). Solved by hand from
     * [rotatedRectToViewRect]'s own equations (front-camera branch: for each
     * output edge, invert `view = offset + (srcWidth - rect) * scale` /
     * `view = offset + rect * scale`) -- verified by a round-trip property
     * test ([CoordinateMapperTest]: forward then inverse returns the
     * original rect, for random rects/dimensions), same discipline as
     * [sensorRectToRotatedRect]'s own round-trip test, since no physical
     * device was available this session to verify it any other way.
     */
    fun viewRectToRotatedRect(
        rect: RectF,
        srcWidth: Int,
        srcHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean
    ): Rect {
        if (srcWidth <= 0 || srcHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return Rect()

        val scale = minOf(viewWidth.toFloat() / srcWidth, viewHeight.toFloat() / srcHeight)
        val offsetX = (viewWidth - srcWidth * scale) / 2f
        val offsetY = (viewHeight - srcHeight * scale) / 2f
        if (scale <= 0f) return Rect()

        val top = (rect.top - offsetY) / scale
        val bottom = (rect.bottom - offsetY) / scale

        return if (isFrontCamera) {
            val right = srcWidth - (rect.left - offsetX) / scale
            val left = srcWidth - (rect.right - offsetX) / scale
            Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        } else {
            val left = (rect.left - offsetX) / scale
            val right = (rect.right - offsetX) / scale
            Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        }
    }
}
