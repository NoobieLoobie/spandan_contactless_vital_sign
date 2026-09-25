package com.spandan.app.oximetry

import com.spandan.app.oximetry.OximetryMath.CaptureBranch
import com.spandan.app.oximetry.OximetryMath.ExposureSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OximetryMathTest {

    @Test
    fun clipped_whenAnyChannelAtOrAboveThreshold() {
        assertFalse(OximetryMath.isClipped(249.9, 249.9, 249.9))
        assertTrue(OximetryMath.isClipped(250.0, 10.0, 10.0))
        assertTrue(OximetryMath.isClipped(10.0, 250.0, 10.0))
        assertTrue(OximetryMath.isClipped(10.0, 10.0, 255.0))
        assertFalse(OximetryMath.isClipped(0.0, 0.0, 0.0))
    }

    @Test
    fun brightestChannelFraction_usesMaxChannel() {
        assertEquals(0.5, OximetryMath.brightestChannelFraction(127.5, 60.0, 40.0), 1e-9)
        assertEquals(0.5, OximetryMath.brightestChannelFraction(40.0, 60.0, 127.5), 1e-9)
    }

    @Test
    fun targetWindow_isInclusive40to60Percent() {
        assertTrue(OximetryMath.isWithinTarget(0.40))
        assertTrue(OximetryMath.isWithinTarget(0.60))
        assertFalse(OximetryMath.isWithinTarget(0.399))
        assertFalse(OximetryMath.isWithinTarget(0.601))
    }

    @Test
    fun flickerQuantisation_roundsDownToWhole10msPeriods() {
        assertEquals(30_000_000L, OximetryMath.quantiseToFlicker(33_333_333L))
        assertEquals(20_000_000L, OximetryMath.quantiseToFlicker(25_000_000L))
        assertEquals(10_000_000L, OximetryMath.quantiseToFlicker(10_000_000L))
        // Shorter than one period: left alone, never rounded to zero.
        assertEquals(8_000_000L, OximetryMath.quantiseToFlicker(8_000_000L))
    }

    @Test
    fun correctExposure_hitsTargetProduct_prefersLongFlickerSafeExposure() {
        val current = ExposureSetting(exposureNs = 10_000_000L, iso = 400)
        val next = OximetryMath.correctExposure(
            current, measuredFraction = 0.25,
            exposureRangeNs = 10_000L..500_000_000L, isoRange = 50..3200,
            maxExposureNs = 33_333_333L
        )
        // Target product = 10ms * 400 * (0.5/0.25) = 8e9 ns*ISO.
        assertEquals(30_000_000L, next.exposureNs)          // capped at frame time, quantised to 3 x 10 ms
        assertEquals(267, next.iso)                          // 8e9 / 30e6 = 266.7
        val product = next.exposureNs.toDouble() * next.iso
        assertEquals(8e9, product, 8e9 * 0.01)
    }

    @Test
    fun correctExposure_brighteningStaysWithinSensorLimits() {
        val next = OximetryMath.correctExposure(
            ExposureSetting(30_000_000L, 3000), measuredFraction = 0.05,
            exposureRangeNs = 10_000L..40_000_000L, isoRange = 50..3200,
            maxExposureNs = 33_333_333L
        )
        assertEquals(30_000_000L, next.exposureNs)
        assertEquals(3200, next.iso) // clamped at max ISO
    }

    @Test
    fun correctExposure_darkeningShortensExposureAtBaseIso() {
        // Scene too bright (90 % of full scale) at 30 ms / ISO 100 -> product must fall.
        val next = OximetryMath.correctExposure(
            ExposureSetting(30_000_000L, 100), measuredFraction = 0.9,
            exposureRangeNs = 10_000L..40_000_000L, isoRange = 50..3200,
            maxExposureNs = 33_333_333L
        )
        val product = next.exposureNs.toDouble() * next.iso
        assertEquals(30_000_000.0 * 100 * 0.5 / 0.9, product, 30_000_000.0 * 100 * 0.02)
        assertEquals(0L, next.exposureNs % OximetryMath.FLICKER_PERIOD_50HZ_NS)
    }

    @Test
    fun correctExposure_invalidMeasurementReturnsCurrent() {
        val current = ExposureSetting(20_000_000L, 200)
        assertEquals(current, OximetryMath.correctExposure(current, 0.0, 1L..1_000_000_000L, 50..3200, 33_333_333L))
        assertEquals(current, OximetryMath.correctExposure(current, Double.NaN, 1L..1_000_000_000L, 50..3200, 33_333_333L))
    }

    @Test
    fun aeCompensation_stepsLog2OfTargetOverMeasured() {
        // 0.125 -> 0.5 is +2 EV = +12 steps of 1/6 EV.
        assertEquals(12, OximetryMath.nextAeCompensation(0, 0.125, 1.0 / 6, -24..24))
        // Too bright: 0.9 -> 0.5 is -0.85 EV = -5 steps of 1/6 EV.
        assertEquals(-5, OximetryMath.nextAeCompensation(0, 0.9, 1.0 / 6, -24..24))
        // Clamped to the camera's range; relative to the current value.
        assertEquals(4, OximetryMath.nextAeCompensation(2, 0.01, 1.0 / 3, -4..4))
        // Invalid measurement or step -> unchanged.
        assertEquals(3, OximetryMath.nextAeCompensation(3, 0.0, 1.0 / 6, -24..24))
        assertEquals(3, OximetryMath.nextAeCompensation(3, 0.2, 0.0, -24..24))
    }

    @Test
    fun zeroOffsetSubtraction() {
        assertEquals(98.0, OximetryMath.subtractZeroOffset(100.0, 2.0), 1e-12)
        assertEquals(-1.5, OximetryMath.subtractZeroOffset(0.5, 2.0), 1e-12)
    }

    @Test
    fun capturePlan_branches() {
        assertEquals(
            OximetryMath.CapturePlan(CaptureBranch.A_MANUAL, true),
            OximetryMath.decideCapturePlan(true, true, true, true, true)
        )
        // Manual sensor without manual post-processing -> lock branch.
        assertEquals(
            OximetryMath.CapturePlan(CaptureBranch.B_LOCK, false),
            OximetryMath.decideCapturePlan(true, false, false, true, true)
        )
        // Lock branch keeps a linear curve only if CONTRAST_CURVE is actually offered.
        assertEquals(
            OximetryMath.CapturePlan(CaptureBranch.B_LOCK, true),
            OximetryMath.decideCapturePlan(false, false, true, true, false)
        )
        assertEquals(
            OximetryMath.CapturePlan(CaptureBranch.NONE, false),
            OximetryMath.decideCapturePlan(false, false, true, false, false)
        )
    }

    @Test
    fun identityCcm_andLinearCurve() {
        val ccm = OximetryMath.identityCcmRationals()
        assertEquals(18, ccm.size)
        for (row in 0 until 3) for (col in 0 until 3) {
            val num = ccm[(row * 3 + col) * 2]
            val den = ccm[(row * 3 + col) * 2 + 1]
            assertEquals(1, den)
            assertEquals(if (row == col) 1 else 0, num)
        }
        assertTrue(OximetryMath.linearToneCurvePoints().contentEquals(floatArrayOf(0f, 0f, 1f, 1f)))
    }
}
