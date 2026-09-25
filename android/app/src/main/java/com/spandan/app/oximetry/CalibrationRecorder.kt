package com.spandan.app.oximetry

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * [Segment 34] Task 4 -- writes one calibration-session CSV (header block,
 * column line, one row per analyzed frame, EVENT rows) to
 * `<externalFilesDir>/calibration/`, i.e.
 * `/sdcard/Android/data/com.spandan.app/files/calibration/` for `adb pull`.
 * No video frame is ever stored -- only ROI means and metadata. All disk I/O
 * runs on one background thread; callers never block on it.
 */
class CalibrationRecorder(private val baseDir: File) {

    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null

    @Volatile var file: File? = null
        private set
    @Volatile var isRecording = false
        private set
    @Volatile var rowsWritten = 0L
        private set

    fun start(header: List<Pair<String, String>>): File {
        val dir = File(baseDir, "calibration").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "spandan_cal_$stamp.csv")
        file = f
        rowsWritten = 0
        isRecording = true
        io.execute {
            try {
                val w = BufferedWriter(FileWriter(f))
                w.write(CalibrationCsvFormat.headerBlock(header)); w.newLine()
                w.write(CalibrationCsvFormat.columnHeaderLine()); w.newLine()
                writer = w
                Log.i(TAG, "RECORD start ${f.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "could not open ${f.absolutePath}", e)
            }
        }
        return f
    }

    fun writeFrame(row: CalibrationCsvFormat.FrameRow) {
        if (!isRecording) return
        val line = CalibrationCsvFormat.frameRow(row)
        io.execute { writeLine(line); rowsWritten++ }
    }

    fun writeEvent(timestampNs: Long, event: CalibrationCsvFormat.Event, text: String? = null) {
        if (!isRecording) return
        val line = CalibrationCsvFormat.eventRow(timestampNs, event, text)
        io.execute { writeLine(line); writer?.flush() }
        Log.i(TAG, "EVENT $line")
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        io.execute {
            try {
                writer?.flush(); writer?.close()
                Log.i(TAG, "RECORD stop ${file?.absolutePath} rows=$rowsWritten")
            } catch (e: Exception) {
                Log.e(TAG, "close failed", e)
            }
            writer = null
        }
    }

    fun shutdown() {
        stop()
        io.shutdown()
    }

    private fun writeLine(line: String) {
        try {
            writer?.apply { write(line); newLine() }
            if (rowsWritten % 100 == 0L) writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    companion object {
        private const val TAG = "SPANDAN_CAL"
    }
}
