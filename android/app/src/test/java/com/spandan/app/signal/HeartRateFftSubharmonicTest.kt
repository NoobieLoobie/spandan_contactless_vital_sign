package com.spandan.app.signal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HeartRateFftSubharmonicTest {

    private val fs = 30.0
    private fun sig(n: Int, vararg comps: Pair<Double, Double>) =
        DoubleArray(n) { i -> comps.sumOf { (f, a) -> a * sin(2 * PI * f * i / fs) } }

    @Test fun strongDoublePeak_prefersSubharmonic() {
        // 1.35Hz (81bpm) at 0.7 amplitude (~49% power) plus 2.7Hz at 1.0
        val r = HeartRateFft.estimateBpm(sig(750, 1.35 to 0.7, 2.7 to 1.0), fs)
        assertNotNull(r)
        assertEquals(81.0, r!!.bpm, 3.0)
    }

    @Test fun weakSubharmonic_keepsTopPeak() {
        val r = HeartRateFft.estimateBpm(sig(750, 1.35 to 0.3, 2.7 to 1.0), fs)
        assertNotNull(r)
        assertEquals(162.0, r!!.bpm, 3.0)
    }

    @Test fun subharmonicBelowRestingRange_keepsTopPeak() {
        // top 1.4Hz -> half 0.7Hz is below SUBHARMONIC_MIN_HZ (0.75), so no correction
        val r = HeartRateFft.estimateBpm(sig(750, 0.7 to 0.9, 1.4 to 1.0), fs)
        assertNotNull(r)
        assertEquals(84.0, r!!.bpm, 3.0)
    }

    @Test fun singleCleanPeak_unchanged() {
        val r = HeartRateFft.estimateBpm(sig(750, 1.2 to 1.0), fs)
        assertEquals(72.0, r!!.bpm, 2.0)
        assertEquals(true, abs(r.peakFreqHz - 1.2) < 0.05)
    }
}
