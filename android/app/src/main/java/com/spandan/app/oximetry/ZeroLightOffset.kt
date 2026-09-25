package com.spandan.app.oximetry

/**
 * [Segment 34] Per-phone-model zero-light (dark) offset, in Xuan, Barry,
 * Antipa & Wang 2023's sense (Front. Digit. Health 5:1301019): the mean
 * 8-bit R/G/B the camera reports with the lens fully covered, under the SAME
 * locked oximetry-capture settings used for recording. Subtract it from the
 * ROI means before any ratio is taken ([OximetryMath.subtractZeroOffset]).
 *
 * MEASURED 2026-09-25 on a Samsung Galaxy A35 (SM-A356E), front camera id 1,
 * with the developer "Zero-light 5 s" action (MainActivity's calibration
 * panel): phone laid face-down on a table, 143 frames, Branch B lock in effect
 * (AE/AWB locked, linear CONTRAST_CURVE, 30 fps, exposure 30 ms, reported ISO
 * 2500 = the sensor maximum, AE compensation +14 x 0.1 EV so extra
 * unreported ISP gain was also active), centre half of the frame, logcat tag
 * `SPANDAN_ZERO_LIGHT`. At this maximum gain the value is dominated by the
 * dark-noise floor clamped at 0. Green (white-balance gain 1.00) is lowest,
 * but blue > red is NOT explained by the gains alone (R 2.19 > B 1.97) --
 * the ISP colour matrix and the YUV->RGB step also mix the noise, so treat
 * the per-channel values as measured, not derived. At lower gain (a brighter room) it is
 * expected to be smaller. Re-measure if the lock settles at a very different
 * ISO. Other phone models need their own measurement.
 */
object ZeroLightOffset {
    const val MEASURED = true
    const val PHONE_MODEL = "samsung SM-A356E (Galaxy A35), front camera id 1"
    const val MEASUREMENT_DATE = "2026-09-25"
    const val RED = 1.997
    const val GREEN = 1.744
    const val BLUE = 2.821
    /** Locked settings the offset was measured at (it can depend on ISO). */
    const val AT_EXPOSURE_NS = 30_000_000L
    const val AT_ISO = 2500

    fun describe(): String =
        if (!MEASURED) "not_measured"
        else "R=$RED;G=$GREEN;B=$BLUE;model=$PHONE_MODEL;date=$MEASUREMENT_DATE;exposure_ns=$AT_EXPOSURE_NS;iso=$AT_ISO"
}
