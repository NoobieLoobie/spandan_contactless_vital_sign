package com.spandan.app.oximetry

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * [Segment 34] Task 4 -- writes one calibration-session CSV (header block,
 * column line, one row per analyzed frame, EVENT rows) to
 * `<externalFilesDir>/calibration/`, i.e.
 * `/sdcard/Android/data/com.spandan.app/files/calibration/` for `adb pull`.
 * No video frame is ever stored -- only ROI means and metadata. All disk I/O
 * runs on one background thread; callers never block on it.
 *
 * [Segment 35 Phase 3 item 4] Also saves the oximeter guide-box crop
 * ([OximeterInset]) as a JPEG once per second via [saveOximeterCrop] --
 * extends this SAME recorder/session (one folder per `start()` call, same
 * session stamp) rather than building a second recording mechanism, per
 * the plan's own instruction. Crop JPEGs are the only per-frame IMAGE data
 * this app ever writes to disk; everything else stays ROI means/metadata.
 */
class CalibrationRecorder(private val baseDir: File) {

    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    private var cropDir: File? = null
    private val lastCropSaveElapsedMs = AtomicLong(0L)

    @Volatile var file: File? = null
        private set
    @Volatile var isRecording = false
        private set
    @Volatile var rowsWritten = 0L
        private set
    @Volatile var cropsWritten = 0L
        private set

    fun start(header: List<Pair<String, String>>): File {
        val dir = File(baseDir, "calibration").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "spandan_cal_$stamp.csv")
        file = f
        rowsWritten = 0
        cropsWritten = 0
        lastCropSaveElapsedMs.set(0L)
        cropDir = File(dir, "spandan_cal_${stamp}_oximeter_crops")
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

    /** [Segment 35 Phase 3 item 4] Saves [bitmap] (the oximeter guide-box
     *  crop, already upright/un-mirrored -- see [OximeterInset]) as
     *  `frame_<timestampNs>.jpg` in this session's own crop folder, once per
     *  second (throttled here on [android.os.SystemClock.elapsedRealtime],
     *  not by the caller -- callers may offer a crop every analysis frame).
     *  A no-op while not recording, matching [writeFrame]/[writeEvent]'s own
     *  contract. [timestampNs] is the frame's sensor timestamp, the SAME
     *  clock the CSV's own `timestamp_ns` column uses, so a crop and its
     *  contemporaneous CSV row can be joined offline. */
    fun saveOximeterCrop(bitmap: Bitmap, timestampNs: Long) {
        if (!isRecording) return
        val now = SystemClock.elapsedRealtime()
        val last = lastCropSaveElapsedMs.get()
        if (now - last < CROP_SAVE_INTERVAL_MS) return
        if (!lastCropSaveElapsedMs.compareAndSet(last, now)) return
        val dir = cropDir ?: return
        io.execute {
            try {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "frame_$timestampNs.jpg")
                FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                cropsWritten++
            } catch (e: Exception) {
                Log.e(TAG, "oximeter crop save failed", e)
            }
        }
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

        /** [Segment 35 Phase 3 item 4] "once per second," per the plan's own
         *  instruction. */
        private const val CROP_SAVE_INTERVAL_MS = 1000L
    }
}
