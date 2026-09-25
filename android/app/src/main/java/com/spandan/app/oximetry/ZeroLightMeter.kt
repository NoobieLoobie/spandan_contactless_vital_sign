package com.spandan.app.oximetry

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.spandan.app.camera.RoiPixelAverager

/**
 * [Segment 34] Task 3 -- zero-light offset measurement. While [active],
 * averages the CENTRAL half of every analysis frame (no face is needed: the
 * lens is covered) and accumulates per-frame R/G/B means. Called from the
 * analysis thread before the face analyzer sees the frame; a no-op when not
 * active, so it costs nothing in normal use.
 */
class ZeroLightMeter {

    data class Result(val frames: Int, val red: Double, val green: Double, val blue: Double)

    @Volatile var active = false
        private set
    private var sumR = 0.0
    private var sumG = 0.0
    private var sumB = 0.0
    private var frames = 0

    @Synchronized
    fun start() {
        sumR = 0.0; sumG = 0.0; sumB = 0.0; frames = 0
        active = true
    }

    @Synchronized
    fun stop(): Result {
        active = false
        return if (frames == 0) Result(0, Double.NaN, Double.NaN, Double.NaN)
        else Result(frames, sumR / frames, sumG / frames, sumB / frames)
    }

    @ExperimentalGetImage
    fun offer(imageProxy: ImageProxy) {
        if (!active) return
        val w = imageProxy.width
        val h = imageProxy.height
        val rect = Rect()
        rect.left = w / 4; rect.top = h / 4; rect.right = w * 3 / 4; rect.bottom = h * 3 / 4
        val s = RoiPixelAverager.averageRgb(imageProxy, rect) ?: return
        synchronized(this) {
            if (!active) return
            sumR += s.red; sumG += s.green; sumB += s.blue; frames++
        }
    }
}
