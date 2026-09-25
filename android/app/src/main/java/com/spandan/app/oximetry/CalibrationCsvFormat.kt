package com.spandan.app.oximetry

import java.util.Locale

/**
 * [Segment 34] Pure formatting for the calibration-recording CSV (see
 * [CalibrationRecorder] for the file I/O and
 * android/docs/Segment34_SpO2_Oximetry_Capture.md for the schema contract).
 *
 * Every number goes through [Locale.US]: on a phone set to Bengali (or any
 * locale with a decimal comma / non-ASCII digits) the default-locale
 * `String.format` would silently corrupt a CSV.
 */
object CalibrationCsvFormat {

    const val SCHEMA_VERSION = 1

    /** The first 17 columns are exactly the Segment 34 brief's schema, in its
     *  order. The trailing four are extras that make the file self-checking
     *  (fraction of ROI clipped, time since record start, whether the camera
     *  was actually locked on that frame, which tone curve the hardware
     *  reported). */
    val COLUMNS = listOf(
        "timestamp_ns", "R_mean", "G_mean", "B_mean", "clipped_px",
        "roi_left", "roi_top", "roi_right", "roi_bottom",
        "exposure_ns", "iso", "ae_state", "awb_state",
        "hr_bpm_current", "spo2_current_displayed", "pi_red", "pi_blue",
        "sampled_px", "elapsed_ms", "lock_state", "tonemap_mode"
    )

    enum class Event(val label: String) {
        BREATH_HOLD_START("breath_hold_start"),
        BREATH_HOLD_END("breath_hold_end"),
        NOTE("note")
    }

    data class FrameRow(
        val timestampNs: Long,
        val rMean: Double,
        val gMean: Double,
        val bMean: Double,
        val clippedPx: Int,
        val roiLeft: Int,
        val roiTop: Int,
        val roiRight: Int,
        val roiBottom: Int,
        val exposureNs: Long?,
        val iso: Int?,
        val aeState: Int?,
        val awbState: Int?,
        val hrBpm: Double?,
        val spo2Displayed: Double?,
        val piRed: Double?,
        val piBlue: Double?,
        val sampledPx: Int,
        val elapsedMs: Long,
        val lockState: String,
        val tonemapMode: Int?
    )

    fun columnHeaderLine(): String = COLUMNS.joinToString(",")

    fun frameRow(r: FrameRow): String = listOf(
        r.timestampNs.toString(),
        num(r.rMean, 4), num(r.gMean, 4), num(r.bMean, 4),
        r.clippedPx.toString(),
        r.roiLeft.toString(), r.roiTop.toString(), r.roiRight.toString(), r.roiBottom.toString(),
        r.exposureNs?.toString() ?: "",
        r.iso?.toString() ?: "",
        r.aeState?.toString() ?: "",
        r.awbState?.toString() ?: "",
        num(r.hrBpm, 2), num(r.spo2Displayed, 3), num(r.piRed, 6), num(r.piBlue, 6),
        r.sampledPx.toString(),
        r.elapsedMs.toString(),
        sanitize(r.lockState),
        r.tonemapMode?.toString() ?: ""
    ).joinToString(",")

    /** `EVENT,<timestamp_ns>,<label>` -- with a 4th free-text field only for
     *  [Event.NOTE] when text was given. */
    fun eventRow(timestampNs: Long, event: Event, text: String? = null): String {
        val base = "EVENT,$timestampNs,${event.label}"
        val clean = text?.let { sanitize(it) }
        return if (clean.isNullOrEmpty()) base else "$base,$clean"
    }

    /** Header block: one `# key=value` line per entry, in the given order. */
    fun headerBlock(entries: List<Pair<String, String>>): String =
        entries.joinToString("\n") { (k, v) -> "# ${sanitize(k)}=${sanitize(v)}" }

    /** Free text must not break the row/line structure: commas become
     *  semicolons, any line break becomes a space. */
    fun sanitize(text: String): String =
        text.replace(',', ';').replace('\r', ' ').replace('\n', ' ').trim()

    fun num(value: Double?, decimals: Int): String =
        if (value == null || value.isNaN() || value.isInfinite()) "" else String.format(Locale.US, "%.${decimals}f", value)
}
