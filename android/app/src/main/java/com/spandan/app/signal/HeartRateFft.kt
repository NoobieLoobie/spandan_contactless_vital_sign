package com.spandan.app.signal

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.hypot

/**
 * Real port of matlab/src/heartrate/fftHeartRate.m (read directly from source
 * before writing this): FFT magnitude spectrum -> restrict to the 0.7-4Hz
 * physiological band (masking BEFORE peak search, matching the MATLAB source's
 * explicit safety-net comment -- a global max could otherwise land outside the
 * valid band) -> peak bin -> Hz -> bpm.
 *
 * frameRate/fs is always the caller's runtime-measured value -- never hardcoded,
 * same as MATLAB's `freqResolution = frameRate / signalLength`.
 */
object HeartRateFft {

    const val LOW_BAND_HZ = 0.7
    const val HIGH_BAND_HZ = 4.0

    // Harmonic-ambiguity guard (classical rPPG correction, not a new algorithm).
    const val SUBHARMONIC_TOLERANCE_HZ = 0.05
    const val SUBHARMONIC_MIN_POWER_RATIO = 0.375
    const val SUBHARMONIC_MIN_HZ = 0.75
    const val SUBHARMONIC_MAX_HZ = 2.2

    data class Result(val bpm: Double, val peakFreqHz: Double)

    /** Returns null if no FFT bin falls inside the 0.7-4Hz band for this fs/window
     *  length (mirrors fftHeartRate.m's `error('fftHeartRate:emptyBand', ...)`,
     *  translated to a soft null since this runs live on-device rather than as an
     *  offline batch script). */
    fun estimateBpm(pulseSignal: DoubleArray, fs: Double): Result? {
        val n = pulseSignal.size
        if (n < 4) return null

        val complexData = DoubleArray(2 * n)
        for (i in 0 until n) complexData[2 * i] = pulseSignal[i]
        DoubleFFT_1D(n.toLong()).complexForward(complexData)

        // fft() of a real signal is symmetric -- keep only 0Hz..Nyquist, same as
        // fftHeartRate.m's `numPositiveBins = floor(signalLength/2) + 1`.
        val numPositiveBins = n / 2 + 1
        val freqResolution = fs / n

        val mags = DoubleArray(numPositiveBins) { k -> hypot(complexData[2 * k], complexData[2 * k + 1]) }

        var peakBin = -1
        var peakMag = -1.0
        for (k in 0 until numPositiveBins) {
            val freq = k * freqResolution
            if (freq < LOW_BAND_HZ || freq > HIGH_BAND_HZ) continue
            if (mags[k] > peakMag) {
                peakMag = mags[k]
                peakBin = k
            }
        }
        if (peakBin < 0) return null

        val bestBin = preferSubharmonic(mags, freqResolution, peakBin)
        val peakFreqHz = bestBin * freqResolution
        return Result(bpm = peakFreqHz * 60.0, peakFreqHz = peakFreqHz)
    }

    /**
     * If the spectrum has a secondary peak within +/-[SUBHARMONIC_TOLERANCE_HZ]
     * of half the top peak's frequency, with power (mag^2) >=
     * [SUBHARMONIC_MIN_POWER_RATIO] of the top peak's, and that subharmonic is in
     * the resting-HR-adjacent range [SUBHARMONIC_MIN_HZ, SUBHARMONIC_MAX_HZ]
     * (and inside the 0.7-4Hz band), returns that subharmonic's bin; otherwise
     * returns [peakBin] unchanged.
     */
    internal fun preferSubharmonic(mags: DoubleArray, freqResolution: Double, peakBin: Int): Int {
        val peakFreq = peakBin * freqResolution
        val half = peakFreq / 2.0
        var subBin = -1
        var subMag = -1.0
        for (k in mags.indices) {
            val f = k * freqResolution
            if (f < half - SUBHARMONIC_TOLERANCE_HZ || f > half + SUBHARMONIC_TOLERANCE_HZ) continue
            if (f < LOW_BAND_HZ || f > HIGH_BAND_HZ) continue
            if (f < SUBHARMONIC_MIN_HZ || f > SUBHARMONIC_MAX_HZ) continue
            if (mags[k] > subMag) {
                subMag = mags[k]
                subBin = k
            }
        }
        if (subBin < 0) return peakBin
        val ratio = (subMag * subMag) / (mags[peakBin] * mags[peakBin])
        return if (ratio >= SUBHARMONIC_MIN_POWER_RATIO) subBin else peakBin
    }
}
