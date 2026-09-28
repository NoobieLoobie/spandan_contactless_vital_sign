package com.spandan.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.spandan.app.oximetry.OximeterGuideBox

/**
 * Draws the live face-detection box and the smaller ROI sub-box(es) on top of
 * the camera preview. Coordinates are expected to already be in this view's
 * own pixel space (see CoordinateMapper.rotatedRectToViewRect) -- this class
 * only draws, it doesn't do any coordinate math itself.
 *
 * [Segment 35 Phase 3 item 1] Also (optionally) draws the fixed oximeter
 * guide rectangle ([OximeterGuideBox]) with a short label -- unlike
 * [faceRect]/[roiRects], this box's geometry is fixed (computed purely from
 * this view's own current width/height, not passed in from a detection
 * result), so it is drawn every frame regardless of face state whenever
 * [showOximeterGuide] is true.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var faceRect: RectF? = null
    // [Segment 35 Phase 2 item 4] a list, not a single rect -- the live
    // pipeline now pools forehead+both cheeks (FaceAnalyzer.emitFaceDetected)
    // and this draws every region actually averaged, not just one box.
    private var roiRects: List<RectF> = emptyList()

    /** [Segment 35 Phase 3] Developer-panel toggle (off by default -- the
     *  guide box is a debug/calibration aid, not part of the normal HR/SpO2
     *  UI). Setting this calls [invalidate] itself. */
    var showOximeterGuide: Boolean = false
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private val facePaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // green
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val roiPaint = Paint().apply {
        color = Color.parseColor("#FFEB3B") // yellow
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val guidePaint = Paint().apply {
        color = Color.parseColor("#29B6F6") // light blue -- distinct from face (green) and ROI (yellow)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private val guideLabelPaint = Paint().apply {
        color = Color.parseColor("#29B6F6")
        textSize = 32f
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val guideLabelBackgroundPaint = Paint().apply {
        color = Color.parseColor("#B3000000") // translucent black, for label legibility over any background
        style = Paint.Style.FILL
    }

    /** Pass null face / an empty rois list to clear the overlay (e.g. when
     *  no face is found). */
    fun update(face: RectF?, rois: List<RectF>) {
        faceRect = face
        roiRects = rois
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        faceRect?.let { canvas.drawRect(it, facePaint) }
        roiRects.forEach { canvas.drawRect(it, roiPaint) }

        if (showOximeterGuide && width > 0 && height > 0) {
            val guide = OximeterGuideBox.inView(width, height)
            canvas.drawRect(guide, guidePaint)

            val label = "Keep oximeter screen here"
            val textWidth = guideLabelPaint.measureText(label)
            val labelLeft = guide.left
            val labelTop = guide.top - 44f
            canvas.drawRect(
                labelLeft - 8f, labelTop - 32f, labelLeft + textWidth + 8f, labelTop + 12f,
                guideLabelBackgroundPaint
            )
            canvas.drawText(label, labelLeft, labelTop, guideLabelPaint)
        }
    }
}
