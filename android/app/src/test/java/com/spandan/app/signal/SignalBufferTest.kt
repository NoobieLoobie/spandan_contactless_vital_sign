package com.spandan.app.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Segment 35 Phase 2 item 2] Unit tests for SignalBuffer's lost-face gap
 *  detection (docs/Segment35_Accuracy_Research_and_Plan.md finding H2). Only
 *  the sensor-timestamp-based gap logic is new here; the window/cutoff
 *  logic these tests also touch already existed and is exercised
 *  incidentally. */
class SignalBufferTest {

    private fun sample(timestampMs: Long, sensorNs: Long) =
        RgbSample(timestampMs = timestampMs, red = 100f, green = 100f, blue = 100f, sensorTimestampNs = sensorNs)

    @Test
    fun `no gap detection when sensorTimestampNs is unset (backward compatibility)`() {
        val buffer = SignalBuffer(windowSeconds = 100.0)
        // Old-style construction (no sensorTimestampNs) -- must behave exactly
        // as before this change: no clearing, no isReacquiring, ever.
        buffer.add(RgbSample(timestampMs = 0L, red = 1f, green = 1f, blue = 1f))
        buffer.add(RgbSample(timestampMs = 10_000L, red = 1f, green = 1f, blue = 1f)) // 10s gap in wall clock
        assertEquals(2, buffer.snapshot().size)
        assertFalse(buffer.isReacquiring)
    }

    @Test
    fun `small gap under threshold is not cleared`() {
        val buffer = SignalBuffer(windowSeconds = 100.0)
        buffer.add(sample(0L, 1_000_000_000L))
        // 0.3s gap, real timestamps -- under GAP_CLEAR_THRESHOLD_SECONDS (0.5s)
        buffer.add(sample(300L, 1_300_000_000L))
        buffer.add(sample(600L, 1_600_000_000L))
        assertEquals(3, buffer.snapshot().size)
        assertFalse(buffer.isReacquiring)
    }

    @Test
    fun `gap at or above threshold clears the buffer and sets isReacquiring`() {
        val buffer = SignalBuffer(windowSeconds = 100.0)
        buffer.add(sample(0L, 1_000_000_000L))
        buffer.add(sample(100L, 1_100_000_000L))
        assertEquals(2, buffer.snapshot().size)

        // 0.5s gap exactly -- at the threshold, should clear.
        buffer.add(sample(600L, 1_600_000_000L))
        assertEquals(1, buffer.snapshot().size) // only the new post-gap sample remains
        assertTrue(buffer.isReacquiring)
    }

    @Test
    fun `isReacquiring clears once enough fresh samples accumulate after a gap`() {
        val buffer = SignalBuffer(windowSeconds = 100.0)
        buffer.add(sample(0L, 1_000_000_000L))
        buffer.add(sample(2_000L, 3_000_000_000L)) // 2s gap -- clears, isReacquiring=true
        assertTrue(buffer.isReacquiring)

        buffer.add(sample(2_050L, 3_050_000_000L))
        assertTrue(buffer.isReacquiring) // 2 fresh samples, still below MIN_SAMPLES_TO_CLEAR_REACQUIRING (3)
        buffer.add(sample(2_100L, 3_100_000_000L))
        assertFalse(buffer.isReacquiring) // 3rd fresh sample clears it
    }

    @Test
    fun `a long gap followed by another long gap keeps only the latest post-gap data`() {
        val buffer = SignalBuffer(windowSeconds = 100.0)
        buffer.add(sample(0L, 1_000_000_000L))
        buffer.add(sample(5_000L, 6_000_000_000L)) // clears
        buffer.add(sample(5_050L, 6_050_000_000L))
        buffer.add(sample(15_000L, 16_000_000_000L)) // clears again
        assertEquals(1, buffer.snapshot().size)
        assertTrue(buffer.isReacquiring)
    }

    @Test
    fun `clear() resets gap-tracking state too`() {
        val buffer = SignalBuffer(windowSeconds = 100.0)
        buffer.add(sample(0L, 1_000_000_000L))
        buffer.add(sample(2_000L, 3_000_000_000L)) // gap -> isReacquiring=true
        assertTrue(buffer.isReacquiring)

        buffer.clear()
        assertFalse(buffer.isReacquiring)
        assertTrue(buffer.snapshot().isEmpty())

        // After a manual clear(), the next add() must not see a stale
        // lastSensorTimestampNs and spuriously detect a "gap" against it.
        buffer.add(sample(100_000L, 999_000_000_000L))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `existing window-cutoff behavior is unaffected by gap detection`() {
        val buffer = SignalBuffer(windowSeconds = 5.0)
        buffer.add(sample(0L, 1_000_000_000L))
        buffer.add(sample(1_000L, 1_100_000_000L)) // 0.1s real gap, fine
        buffer.add(sample(2_000L, 1_200_000_000L)) // 0.1s real gap, fine
        buffer.add(sample(7_000L, 1_300_000_000L)) // 0.1s real gap, fine -- but wall-clock cutoff (7000-5000=2000ms) drops samples strictly before 2000ms
        val snap = buffer.snapshot()
        assertEquals(2, snap.size) // samples at 2000/7000ms remain; 0ms and 1000ms fell outside the 5s window
        assertFalse(buffer.isReacquiring)
    }
}
