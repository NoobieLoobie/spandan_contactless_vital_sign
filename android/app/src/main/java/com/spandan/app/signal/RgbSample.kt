package com.spandan.app.signal

/** One spatially-averaged RGB reading from the ROI, at a point in time.
 *  [clippedPixels]/[sampledPixels] ([Segment 34]): of the stride-sampled
 *  pixels averaged, how many had any channel >= OximetryMath.CLIP_THRESHOLD.
 *  [sensorTimestampNs] ([Segment 35 Phase 2]): the frame's REAL sensor
 *  capture timestamp (`ImageInfo.getTimestamp`, same value FaceAnalysisResult.
 *  FaceDetected.sensorTimestampNs already carried but that [SignalBuffer]/
 *  [com.spandan.app.signal.RealHeartRateEstimator] never read) -- carried on
 *  the sample itself so gap detection ([SignalBuffer]) and uniform resampling
 *  ([ResampleUniform], via [RealHeartRateEstimator]/[LiveSpo2Estimator]) don't
 *  need a second parallel array. Defaulted to 0 so every pre-existing
 *  construction site (including unit tests using synthetic timing) is
 *  unchanged; both consumers below treat 0/absent as "no real timestamp
 *  available" and fall back to their pre-Segment-35 behavior. */
data class RgbSample(
    val timestampMs: Long,
    val red: Float,
    val green: Float,
    val blue: Float,
    val clippedPixels: Int = 0,
    val sampledPixels: Int = 0,
    val sensorTimestampNs: Long = 0L
)
