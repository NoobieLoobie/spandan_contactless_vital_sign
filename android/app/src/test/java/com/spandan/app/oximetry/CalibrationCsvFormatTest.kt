package com.spandan.app.oximetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CalibrationCsvFormatTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun row(hr: Double? = 72.346, exposure: Long? = 30_000_000L) = CalibrationCsvFormat.FrameRow(
        timestampNs = 123_456_789_012L,
        rMean = 120.12346, gMean = 80.5, bMean = 60.0,
        clippedPx = 3,
        roiLeft = 10, roiTop = 20, roiRight = 110, roiBottom = 70,
        exposureNs = exposure, iso = 267, aeState = 0, awbState = 0,
        hrBpm = hr, spo2Displayed = 96.8123, piRed = 0.0012346, piBlue = 0.0023456,
        sampledPx = 1250, elapsedMs = 4321L, lockState = "LOCKED", tonemapMode = 0
    )

    @Test
    fun columns_startWithTheBriefSchemaInOrder() {
        val brief = listOf(
            "timestamp_ns", "R_mean", "G_mean", "B_mean", "clipped_px",
            "roi_left", "roi_top", "roi_right", "roi_bottom",
            "exposure_ns", "iso", "ae_state", "awb_state",
            "hr_bpm_current", "spo2_current_displayed", "pi_red", "pi_blue"
        )
        assertEquals(brief, CalibrationCsvFormat.COLUMNS.take(brief.size))
        assertEquals(CalibrationCsvFormat.COLUMNS.joinToString(","), CalibrationCsvFormat.columnHeaderLine())
    }

    @Test
    fun frameRow_hasOneFieldPerColumn_andFormatsNumbers() {
        val fields = CalibrationCsvFormat.frameRow(row()).split(",")
        assertEquals(CalibrationCsvFormat.COLUMNS.size, fields.size)
        assertEquals("123456789012", fields[0])
        assertEquals("120.1235", fields[1])
        assertEquals("3", fields[4])
        assertEquals("30000000", fields[9])
        assertEquals("72.35", fields[13])
        assertEquals("96.812", fields[14])
        assertEquals("0.001235", fields[15])
        assertEquals("LOCKED", fields[19])
    }

    @Test
    fun frameRow_nullsBecomeEmptyFields() {
        val fields = CalibrationCsvFormat.frameRow(row(hr = null, exposure = null)).split(",")
        assertEquals(CalibrationCsvFormat.COLUMNS.size, fields.size)
        assertEquals("", fields[9])
        assertEquals("", fields[13])
    }

    @Test
    fun frameRow_isLocaleIndependent() {
        val reference = CalibrationCsvFormat.frameRow(row())
        for (locale in listOf(Locale("bn", "BD"), Locale.GERMANY, Locale("ar", "EG"))) {
            Locale.setDefault(locale)
            assertEquals("locale $locale", reference, CalibrationCsvFormat.frameRow(row()))
        }
        assertTrue(reference.all { it.code < 128 })
    }

    @Test
    fun eventRows() {
        assertEquals("EVENT,42,breath_hold_start", CalibrationCsvFormat.eventRow(42L, CalibrationCsvFormat.Event.BREATH_HOLD_START))
        assertEquals("EVENT,43,breath_hold_end", CalibrationCsvFormat.eventRow(43L, CalibrationCsvFormat.Event.BREATH_HOLD_END))
        assertEquals("EVENT,44,note", CalibrationCsvFormat.eventRow(44L, CalibrationCsvFormat.Event.NOTE))
        assertEquals(
            "EVENT,45,note,zero_light R=1;G=2",
            CalibrationCsvFormat.eventRow(45L, CalibrationCsvFormat.Event.NOTE, "zero_light R=1,G=2\n")
        )
    }

    @Test
    fun headerBlock_isCommentedAndSingleLinePerEntry() {
        val block = CalibrationCsvFormat.headerBlock(
            listOf("lighting_condition" to "ceiling tube,\nplus window", "phone_model" to "SM-A356E")
        )
        val lines = block.split("\n")
        assertEquals(2, lines.size)
        assertEquals("# lighting_condition=ceiling tube; plus window", lines[0])
        assertEquals("# phone_model=SM-A356E", lines[1])
    }
}
