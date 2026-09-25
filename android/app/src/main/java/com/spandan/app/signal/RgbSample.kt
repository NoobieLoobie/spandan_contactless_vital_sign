package com.spandan.app.signal

/** One spatially-averaged RGB reading from the ROI, at a point in time.
 *  [clippedPixels]/[sampledPixels] ([Segment 34]): of the stride-sampled
 *  pixels averaged, how many had any channel >= OximetryMath.CLIP_THRESHOLD.
 *  Defaulted so every pre-existing construction site is unchanged. */
data class RgbSample(
    val timestampMs: Long,
    val red: Float,
    val green: Float,
    val blue: Float,
    val clippedPixels: Int = 0,
    val sampledPixels: Int = 0
)
