package com.spandan.app.signal

/**
 * Segment 19 (Branch 2 morphology port) -- real port of
 * `matlab/src/morphology/resampleUniform.m` (read directly from source
 * before writing this): resamples a pulse signal onto a uniform high-rate
 * time grid using its REAL per-sample timestamps (never assuming uniform
 * `1/fs` spacing) via [PchipInterpolator], for exactly the reason the
 * MATLAB header gives: camera frame timing is irregular, and the dicrotic
 * notch is a fine (tens-of-ms) time-domain feature that irregular-spacing-
 * as-if-uniform would smear.
 *
 * `RgbSample.timestampMs` (already real per-frame acquisition times, the
 * same field [SignalBuffer]/[RealHeartRateEstimator] use for their own
 * runtime-measured `fs`) is this port's equivalent of
 * `roi/extractROISignals.m`'s `roiTimestamps` output -- converted to
 * seconds here since MATLAB's own convention is seconds throughout.
 */
object ResampleUniform {

    const val DEFAULT_TARGET_FS = 250.0

    /** [Segment 35 Phase 2 item 3] Target grid for [resampleRgbSamples] --
     *  30Hz, per docs/Segment35_Accuracy_Research_and_Plan.md's own Phase 2
     *  item 3 instruction (this app's real camera fps is typically well
     *  under this on a mid-range phone, so 30Hz is an upsample in practice,
     *  same direction Branch 2's own [DEFAULT_TARGET_FS]=250Hz upsamples --
     *  the point is a UNIFORM grid, not a particular rate). */
    const val TARGET_FS_RGB = 30.0

    data class Result(val sigUniform: DoubleArray, val timeUniform: DoubleArray, val targetFs: Double)

    fun apply(sig: DoubleArray, timestampsSeconds: DoubleArray, targetFs: Double = DEFAULT_TARGET_FS): Result {
        require(sig.size == timestampsSeconds.size) { "sig and timestampsSeconds must have the same number of elements" }
        require(sig.size >= 2) { "need at least 2 samples to resample" }

        val startSec = timestampsSeconds.first()
        val endSec = timestampsSeconds.last()
        val stepSec = 1.0 / targetFs
        val numSteps = ((endSec - startSec) / stepSec).toInt() + 1
        val timeUniform = DoubleArray(numSteps) { startSec + it * stepSec }

        val sigUniform = PchipInterpolator.interpolate(timestampsSeconds, sig, timeUniform)
        return Result(sigUniform, timeUniform, targetFs)
    }

    /** [Segment 35 Phase 2 item 3] R/G/B result of [resampleRgbSamples]: all
     *  three channels resampled onto the SAME uniform time grid (so they stay
     *  aligned index-for-index), plus that grid's [fs] for the caller's
     *  downstream filtering chain to use in place of a naive
     *  `(N-1)/windowSeconds` estimate. */
    data class RgbChannelsResult(val r: DoubleArray, val g: DoubleArray, val b: DoubleArray, val fs: Double)

    /**
     * Resamples a live [RgbSample] window's R/G/B onto a uniform [targetFs]
     * grid using each sample's REAL [RgbSample.sensorTimestampNs] --
     * [RealHeartRateEstimator]/[LiveSpo2Estimator]'s equivalent of the
     * offline pipeline's `roi/extractROISignals.m` -> `resampleUniform.m`
     * stage, and the fix for finding H3 (docs/Segment35_Accuracy_Research_
     * and_Plan.md): on every 3rd frame, the real detection happens inside
     * ML Kit's async callback, so `RgbSample.timestampMs` (set at averaging
     * time) lands 19-90ms late and irregularly -- periodic jitter at
     * frame-rate/3 that this uniform PCHIP resample removes by construction,
     * the same reason Branch 2 ([MorphologyWaveformEstimator]) already calls
     * [apply] and Branch 1 previously did not.
     *
     * Returns null -- the caller's signal to fall back to its own pre-
     * Segment-35 naive-fs path -- when fewer than 2 samples carry a real,
     * strictly increasing sensor timestamp: every unit test that constructs
     * [RgbSample] without setting [RgbSample.sensorTimestampNs] (default 0L),
     * or (defensively) a device/build where `ImageInfo.timestamp` is never
     * populated. A non-increasing timestamp (out-of-order or duplicate
     * frames, which [PchipInterpolator] requires to not happen) is treated
     * the same way, never crashes the caller.
     */
    fun resampleRgbSamples(samples: List<RgbSample>, targetFs: Double = TARGET_FS_RGB): RgbChannelsResult? {
        if (samples.size < 2) return null
        if (samples.any { it.sensorTimestampNs <= 0L }) return null

        val timestampsSeconds = DoubleArray(samples.size) { samples[it].sensorTimestampNs / 1_000_000_000.0 }
        for (i in 1 until timestampsSeconds.size) {
            if (timestampsSeconds[i] <= timestampsSeconds[i - 1]) return null
        }

        val rawR = DoubleArray(samples.size) { samples[it].red.toDouble() }
        val rawG = DoubleArray(samples.size) { samples[it].green.toDouble() }
        val rawB = DoubleArray(samples.size) { samples[it].blue.toDouble() }

        val rResult = apply(rawR, timestampsSeconds, targetFs)
        val gResult = apply(rawG, timestampsSeconds, targetFs)
        val bResult = apply(rawB, timestampsSeconds, targetFs)
        return RgbChannelsResult(rResult.sigUniform, gResult.sigUniform, bResult.sigUniform, targetFs)
    }
}
