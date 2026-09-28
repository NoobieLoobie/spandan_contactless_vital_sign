package com.spandan.app.signal

/**
 * Rolling time-windowed buffer of ROI-averaged RGB samples. Real, permanent
 * code -- this is bookkeeping (drop samples older than the window),
 * independent of whichever algorithm eventually consumes the buffer.
 *
 * [Segment 35 Phase 2, item 2] Previously a lost-face gap was invisible here:
 * MainActivity only cleared the overlay on NoFace and added nothing to the
 * buffer, so when the face was reacquired the pre-gap and post-gap samples
 * sat adjacent in the deque with no record of the real time gap between
 * them. [RealHeartRateEstimator]'s `fs = (N-1)/windowSeconds` then silently
 * treated that gap as ordinary uniform sampling -- fewer real pulse cycles
 * over the same wall-clock span biases HR low, and the splice point is a
 * phase jump that adds broadband noise (see docs/Segment35_Accuracy_
 * Research_and_Plan.md finding H2). Fix: [add] now uses each sample's real
 * [RgbSample.sensorTimestampNs] (already plumbed through FaceAnalysisResult,
 * previously unused for this) to detect the gap since the last sample.
 * A gap under [GAP_CLEAR_THRESHOLD_SECONDS] is left alone -- normal
 * detection-skip/ML-Kit-callback jitter, which [ResampleUniform]'s uniform
 * grid (Phase 2 item 3) already accounts for using the same real timestamps.
 * A gap at or above that threshold means the face was really lost and
 * reacquired: the buffer is cleared (so the window never mixes pre-gap and
 * post-gap data) and [isReacquiring] is set so the UI can show
 * "re-acquiring" instead of a spliced HR/SpO2 value until enough fresh
 * samples have accumulated.
 *
 * Backward compatible by construction: [RgbSample.sensorTimestampNs]
 * defaults to 0, and gap detection is skipped whenever either the new or the
 * previous sample's timestamp is 0 -- so any caller/test that never sets it
 * (there are several, all synthetic-timing unit tests) behaves exactly as
 * before this change.
 */
class SignalBuffer(private val windowSeconds: Double = WINDOW_DURATION_SECONDS) {

    private val samples = ArrayDeque<RgbSample>()
    private var lastSensorTimestampNs: Long = 0L

    /** [Segment 35 Phase 2] True from the moment a >= [GAP_CLEAR_THRESHOLD_SECONDS]
     *  gap is detected until the buffer has refilled past [MIN_SAMPLES_TO_CLEAR_REACQUIRING]
     *  samples again. Read this AFTER [add]; it never changes what [snapshot] returns. */
    var isReacquiring: Boolean = false
        private set

    @Synchronized
    fun add(sample: RgbSample) {
        val prevTimestampNs = lastSensorTimestampNs
        val thisTimestampNs = sample.sensorTimestampNs

        if (thisTimestampNs > 0 && prevTimestampNs > 0) {
            val gapSeconds = (thisTimestampNs - prevTimestampNs) / 1_000_000_000.0
            if (gapSeconds >= GAP_CLEAR_THRESHOLD_SECONDS) {
                samples.clear()
                isReacquiring = true
            }
            // gapSeconds < threshold (including a negative/zero gap from an
            // out-of-order or duplicate timestamp): left as ordinary jitter,
            // same sample added below either way -- ResampleUniform is what
            // actually "bridges" a sub-threshold gap, using this same
            // timestamp, once it runs downstream.
        }
        if (thisTimestampNs > 0) lastSensorTimestampNs = thisTimestampNs

        samples.addLast(sample)
        val cutoffMs = sample.timestampMs - (windowSeconds * 1000).toLong()
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoffMs) {
            samples.removeFirst()
        }

        if (isReacquiring && samples.size >= MIN_SAMPLES_TO_CLEAR_REACQUIRING) {
            isReacquiring = false
        }
    }

    @Synchronized
    fun snapshot(): List<RgbSample> = samples.toList()

    @Synchronized
    fun clear() {
        samples.clear()
        lastSensorTimestampNs = 0L
        isReacquiring = false
    }

    companion object {
        /**
         * The single, tunable knob for how much wall-clock history the rolling
         * buffer keeps. Everything downstream (FFT bin resolution, on-screen
         * jitter, responsiveness to a changing HR) is a function of this one
         * number -- see android/README.md's "Diagnostic" and window-length
         * verification sections for the measurements behind the current value.
         *
         * Was 10.0 (~134 samples at the measured ~13.4Hz on-device throughput).
         * Raised to 25.0 (~335 samples) after the timing diagnostic ruled out
         * bursty/irregular sampling (CV=0.063, zero gaps >2x mean) as the cause
         * of on-screen jitter, leaving "too few samples per window" as the
         * better-supported explanation. 25s is a deliberate middle ground, not
         * an attempt to match MATLAB's ~80s/~2400-sample validated clips
         * (which would need ~3 minutes of steady holding at this throughput --
         * impractical for a live demo).
         */
        const val WINDOW_DURATION_SECONDS: Double = 25.0

        /** [Segment 35 Phase 2] A real face-loss gap, per docs/Segment35_
         *  Accuracy_Research_and_Plan.md Phase 2 item 2's own instruction. Not
         *  re-derived from an on-device measurement this session (no phone
         *  available) -- flagged for Phase 4's real re-acquisition test. */
        const val GAP_CLEAR_THRESHOLD_SECONDS: Double = 0.5

        /** How many fresh samples must accumulate after a gap-clear before
         *  [isReacquiring] clears itself -- small and arbitrary (not
         *  on-device-measured this session), just enough that a single
         *  post-gap sample doesn't immediately flip the UI back to "OK"
         *  before RealHeartRateEstimator's own MIN_SAMPLES/MIN_WINDOW_SECONDS
         *  warm-up gates would show a real value anyway. */
        const val MIN_SAMPLES_TO_CLEAR_REACQUIRING: Int = 3
    }
}
