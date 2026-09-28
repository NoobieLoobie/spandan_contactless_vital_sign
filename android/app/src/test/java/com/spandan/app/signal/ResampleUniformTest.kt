package com.spandan.app.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class ResampleUniformTest {

    @Test
    fun upsamplesIrregularlyTimedSamplesOntoAUniformHighRateGrid() {
        val durationSec = 10.0
        val f = 1.3
        val rng = Random(3)

        // Irregular timestamps (jittered around a nominal ~15Hz), same kind
        // of irregularity resampleUniform.m's own header describes for real
        // camera frame timing.
        val timestamps = mutableListOf(0.0)
        while (timestamps.last() < durationSec) {
            timestamps.add(timestamps.last() + (1.0 / 15.0) * (0.7 + rng.nextDouble() * 0.6))
        }
        val timestampsArr = timestamps.toDoubleArray()
        val sig = DoubleArray(timestampsArr.size) { sin(2 * PI * f * timestampsArr[it]) }

        val result = ResampleUniform.apply(sig, timestampsArr, targetFs = 200.0)

        assertEquals(200.0, result.targetFs, 1e-9)
        assertTrue(result.sigUniform.size > sig.size) // genuine upsampling

        // Check the resampled signal still tracks the known sine well away
        // from the very edges (PCHIP end-condition effects are largest there).
        var maxErr = 0.0
        for (i in result.timeUniform.indices) {
            val t = result.timeUniform[i]
            if (t < 0.5 || t > durationSec - 0.5) continue
            val expected = sin(2 * PI * f * t)
            maxErr = maxOf(maxErr, abs(result.sigUniform[i] - expected))
        }
        assertTrue("resampled signal drifted too far from the known sine (maxErr=$maxErr)", maxErr < 0.08)
    }

    @Test
    fun timeUniformSpansTheOriginalTimestampRange() {
        val timestamps = doubleArrayOf(0.0, 0.3, 0.9, 1.5, 2.0)
        val sig = doubleArrayOf(0.0, 1.0, 0.5, -0.5, 0.0)
        val result = ResampleUniform.apply(sig, timestamps, targetFs = 50.0)
        assertEquals(timestamps.first(), result.timeUniform.first(), 1e-9)
        assertTrue(result.timeUniform.last() <= timestamps.last() + 1e-9)
    }

    // [Segment 35 Phase 2 item 3] resampleRgbSamples -- the live-app entry
    // point RealHeartRateEstimator/LiveSpo2Estimator call.

    private fun rgbSample(sensorNs: Long, r: Float, g: Float, b: Float) =
        RgbSample(timestampMs = sensorNs / 1_000_000L, red = r, green = g, blue = b, sensorTimestampNs = sensorNs)

    @Test
    fun resampleRgbSamples_returnsNull_whenNoSensorTimestampsSet() {
        // Every existing RgbSample(...) call site in this test suite (and in
        // production code before Segment 35) omits sensorTimestampNs -- must
        // fall back cleanly, not throw.
        val samples = listOf(
            RgbSample(timestampMs = 0L, red = 1f, green = 2f, blue = 3f),
            RgbSample(timestampMs = 33L, red = 1f, green = 2f, blue = 3f)
        )
        assertEquals(null, ResampleUniform.resampleRgbSamples(samples))
    }

    @Test
    fun resampleRgbSamples_returnsNull_withFewerThanTwoSamples() {
        val samples = listOf(rgbSample(1_000_000_000L, 1f, 2f, 3f))
        assertEquals(null, ResampleUniform.resampleRgbSamples(samples))
    }

    @Test
    fun resampleRgbSamples_returnsNull_onNonIncreasingTimestamps() {
        val samples = listOf(
            rgbSample(2_000_000_000L, 1f, 2f, 3f),
            rgbSample(2_000_000_000L, 4f, 5f, 6f), // duplicate timestamp
            rgbSample(2_100_000_000L, 7f, 8f, 9f)
        )
        assertEquals(null, ResampleUniform.resampleRgbSamples(samples))
    }

    @Test
    fun resampleRgbSamples_producesThreeEqualLengthChannelsOnAUniform30HzGrid() {
        // Jittered ~20fps samples over 3s, real nanosecond sensor timestamps.
        val rng = Random(7)
        val timestamps = mutableListOf(1_000_000_000L)
        while ((timestamps.last() - timestamps.first()) < 3_000_000_000L) {
            val jitterNs = (1_000_000_000L / 20) * (0.7 + rng.nextDouble() * 0.6)
            timestamps.add(timestamps.last() + jitterNs.toLong())
        }
        val samples = timestamps.map { ts ->
            val tSec = (ts - timestamps.first()) / 1_000_000_000.0
            rgbSample(ts, (100 + 5 * sin(2 * PI * 1.2 * tSec)).toFloat(), 120f, 90f)
        }

        val result = ResampleUniform.resampleRgbSamples(samples)
        assertTrue(result != null)
        result!!
        assertEquals(30.0, result.fs, 1e-9)
        assertEquals(result.r.size, result.g.size)
        assertEquals(result.r.size, result.b.size)
        assertTrue(result.r.size > 60) // ~3s at 30Hz
        // G/B were constant in the raw samples -- resampling a constant
        // signal must still return that same constant, not drift.
        assertTrue(result.g.all { abs(it - 120.0) < 1e-6 })
        assertTrue(result.b.all { abs(it - 90.0) < 1e-6 })
    }
}
