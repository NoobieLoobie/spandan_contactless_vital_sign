package com.spandan.app.oximetry

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [Segment 34] Pure math for the oximetry-grade capture mode -- no Android
 * types, so every function here is plain-JUnit testable (see
 * OximetryMathTest.kt). The camera plumbing that uses it lives in
 * [OximetryCaptureController]; the reasoning behind each step is in
 * android/docs/Segment34_SpO2_Oximetry_Capture.md.
 */
object OximetryMath {

    /** A pixel counts as clipped when ANY of its three channels is at or
     *  above this 8-bit value (the Segment 34 brief's own definition). 250,
     *  not 255: the YUV->RGB step rounds/clamps, so a saturated sensor
     *  channel does not always land exactly on 255. */
    const val CLIP_THRESHOLD = 250.0

    /** 8-bit full scale of the YUV_420_888 -> RGB values RoiPixelAverager produces. */
    const val FULL_SCALE = 255.0

    /** Exposure target: forehead-ROI mean of the brightest channel at 40-60 %
     *  of full scale (Segment 34 brief; leaves headroom so the pulse peaks and
     *  bright skin patches do not clip, while keeping well clear of the
     *  8-bit quantisation floor). */
    const val TARGET_LOW_FRACTION = 0.40
    const val TARGET_HIGH_FRACTION = 0.60
    const val TARGET_FRACTION = 0.50

    /** Half-period of 50 Hz mains (Bangladesh): lamps flicker at 100 Hz, so an
     *  exposure time that is an integer multiple of 10 ms integrates whole
     *  flicker cycles and cancels it. Auto-exposure's antibanding normally
     *  does this for us; once AE is off we have to do it ourselves. */
    const val FLICKER_PERIOD_50HZ_NS = 10_000_000L

    fun isClipped(r: Double, g: Double, b: Double, threshold: Double = CLIP_THRESHOLD): Boolean =
        r >= threshold || g >= threshold || b >= threshold

    /** Mean of the brightest channel as a fraction of full scale. */
    fun brightestChannelFraction(r: Double, g: Double, b: Double, fullScale: Double = FULL_SCALE): Double =
        max(r, max(g, b)) / fullScale

    fun isWithinTarget(fraction: Double): Boolean =
        fraction >= TARGET_LOW_FRACTION && fraction <= TARGET_HIGH_FRACTION

    data class ExposureSetting(val exposureNs: Long, val iso: Int)

    /**
     * One linear exposure-correction step, valid ONLY once the tone curve is
     * linear (Branch A): pixel value is then proportional to
     * exposureTime x ISO, so scaling that product by
     * `targetFraction / measuredFraction` moves the ROI to target.
     *
     * Allocation of the new product between time and ISO: prefer the longest
     * exposure time allowed by [maxExposureNs] (lower read noise per unit of
     * signal than raising ISO), quantised DOWN to a multiple of
     * [flickerPeriodNs] when it is at least one period long, then put the
     * remainder into ISO. Everything is clamped to the sensor's own ranges.
     */
    fun correctExposure(
        current: ExposureSetting,
        measuredFraction: Double,
        exposureRangeNs: LongRange,
        isoRange: IntRange,
        maxExposureNs: Long,
        targetFraction: Double = TARGET_FRACTION,
        flickerPeriodNs: Long = FLICKER_PERIOD_50HZ_NS
    ): ExposureSetting {
        if (measuredFraction <= 0.0 || measuredFraction.isNaN()) return current
        val gain = targetFraction / measuredFraction
        val targetProduct = current.exposureNs.toDouble() * current.iso * gain

        val expCap = minOf(maxExposureNs, exposureRangeNs.last).coerceAtLeast(exposureRangeNs.first)
        var exposure = (targetProduct / isoRange.first).toLong().coerceIn(exposureRangeNs.first, expCap)
        exposure = quantiseToFlicker(exposure, flickerPeriodNs).coerceIn(exposureRangeNs.first, expCap)

        val iso = (targetProduct / exposure).roundToInt().coerceIn(isoRange.first, isoRange.last)
        return ExposureSetting(exposure, iso)
    }

    /**
     * Branch B's exposure step: with AE still running (before AE_LOCK) the
     * only lever is CONTROL_AE_EXPOSURE_COMPENSATION, in steps of
     * [stepEv] EV. With a linear tone curve, output scales as 2^EV, so the
     * needed change is log2(target / measured) EV, rounded to whole steps and
     * clamped to [compRange]. Returns [currentComp] unchanged for an invalid
     * measurement or step size.
     */
    fun nextAeCompensation(
        currentComp: Int,
        measuredFraction: Double,
        stepEv: Double,
        compRange: IntRange,
        targetFraction: Double = TARGET_FRACTION
    ): Int {
        if (measuredFraction <= 0.0 || measuredFraction.isNaN() || stepEv <= 0.0) return currentComp
        val deltaEv = kotlin.math.ln(targetFraction / measuredFraction) / kotlin.math.ln(2.0)
        val steps = kotlin.math.round(deltaEv / stepEv).toInt()
        return (currentComp + steps).coerceIn(compRange.first, compRange.last)
    }

    /** Rounds [exposureNs] DOWN to a whole number of flicker periods when it
     *  is at least one period long; shorter exposures are left alone (they
     *  cannot cancel flicker anyway, and rounding them to 0 would be worse). */
    fun quantiseToFlicker(exposureNs: Long, flickerPeriodNs: Long = FLICKER_PERIOD_50HZ_NS): Long {
        if (flickerPeriodNs <= 0 || exposureNs < flickerPeriodNs) return exposureNs
        return (floor(exposureNs.toDouble() / flickerPeriodNs) * flickerPeriodNs).toLong()
    }

    /** Subtracts the per-phone zero-light (dark) offset, Xuan et al. 2023's
     *  sense: value recorded with the lens covered under the same locked
     *  settings. The CSV logs RAW means; this is for offline use / future
     *  on-device use, not applied to anything displayed in Segment 34. */
    fun subtractZeroOffset(value: Double, offset: Double): Double = value - offset

    // ---------------------------------------------------------------- branch

    enum class CaptureBranch {
        /** MANUAL_SENSOR + MANUAL_POST_PROCESSING: AE/AWB off, fixed
         *  exposure/ISO/gains, identity CCM, linear tone curve. */
        A_MANUAL,
        /** No full manual control: AE_LOCK/AWB_LOCK after convergence. */
        B_LOCK,
        /** Neither manual control nor any lock -- stays fully automatic. */
        NONE
    }

    data class CapturePlan(
        val branch: CaptureBranch,
        /** True only if TONEMAP_MODE_CONTRAST_CURVE is actually available. */
        val linearToneCurve: Boolean
    )

    /** Decides which Segment 34 capture branch the camera supports. Branch B
     *  gets the linear curve only if CONTRAST_CURVE is advertised on its own
     *  (possible without MANUAL_POST_PROCESSING); otherwise the tone curve
     *  stays non-linear and that is reported, not faked. */
    fun decideCapturePlan(
        hasManualSensor: Boolean,
        hasManualPostProcessing: Boolean,
        hasContrastCurve: Boolean,
        aeLockAvailable: Boolean,
        awbLockAvailable: Boolean
    ): CapturePlan {
        if (hasManualSensor && hasManualPostProcessing && hasContrastCurve) {
            return CapturePlan(CaptureBranch.A_MANUAL, linearToneCurve = true)
        }
        if (aeLockAvailable || awbLockAvailable) {
            return CapturePlan(CaptureBranch.B_LOCK, linearToneCurve = hasContrastCurve)
        }
        return CapturePlan(CaptureBranch.NONE, linearToneCurve = false)
    }

    /** Identity 3x3 colour-correction matrix as the row-major rational
     *  numerator/denominator pairs Camera2's ColorSpaceTransform(int[])
     *  constructor expects (18 ints). */
    fun identityCcmRationals(): IntArray = intArrayOf(
        1, 1, 0, 1, 0, 1,
        0, 1, 1, 1, 0, 1,
        0, 1, 0, 1, 1, 1
    )

    /** Linear tone-curve control points (Pin, Pout) = (0,0),(1,1). */
    fun linearToneCurvePoints(): FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
}
