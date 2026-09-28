package com.spandan.app.oximetry

import android.graphics.RectF

/**
 * [Segment 35 Phase 3] Single source of truth for the oximeter guide
 * rectangle's VIEW-SPACE geometry: [OverlayView] draws this exact rect on
 * the preview, and MainActivity maps this exact rect back into analysis-
 * frame space (via CoordinateMapper.viewRectToRotatedRect +
 * rotatedRectToSensorRect) to crop the oximeter's screen out of each
 * frame -- both must agree on the same numbers, so they read them from
 * here rather than each hard-coding their own copy.
 *
 * Lower-third of the screen, away from the face/forehead+cheeks ROI (which
 * sits in the upper-middle of frame for a normally-framed selfie shot) --
 * per docs/Segment35_Accuracy_Research_and_Plan.md Phase 3 item 1's own
 * instruction. Not on-device tuned this session (no physical device
 * available) -- Phase 4 should check whether this position/size is
 * comfortable to hold an oximeter's display against in practice.
 */
object OximeterGuideBox {
    private const val X_LO = 0.20f
    private const val X_HI = 0.80f
    private const val Y_LO = 0.68f
    private const val Y_HI = 0.90f

    fun inView(viewWidth: Int, viewHeight: Int): RectF = RectF(
        X_LO * viewWidth, Y_LO * viewHeight, X_HI * viewWidth, Y_HI * viewHeight
    )
}
