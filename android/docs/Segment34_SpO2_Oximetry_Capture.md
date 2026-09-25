# Segment 34 — Oximetry-grade (locked, linear) camera capture + calibration recorder

Session date **2026-09-25**. Device: Samsung Galaxy A35 (`SM-A356E`, adb serial
`RFCXC0FFFSN`), Android 16 (SDK 36), front camera id `1`. Implements the acquisition half of
`matlab/docs/Segment33_SpO2_Research_and_New_Pipeline.md` §6 (camera lock + linear tone
curve, Xuan et al. 2023) plus the data logger that §7's breath-hold protocol needs.

**The displayed SpO2 formula is unchanged** (`SpO2 = 96.476 + 0.416·R`,
`LiveSpo2Estimator.kt` untouched). No β exists yet, so any new number would just be a
different unvalidated guess. No MATLAB, iOS or estimator code was touched.

## 0. Bottom line

| Question | Answer |
|---|---|
| Capability branch | **B (lock)**: the front camera is `LIMITED`, with no `MANUAL_SENSOR` and no `MANUAL_POST_PROCESSING`, so there is no manual exposure/ISO/CCM. It **does** offer `TONEMAP_MODE_CONTRAST_CURVE` (128 points), AE lock, AWB lock and a fixed `[30,30]` fps range. |
| Linear tone curve | **Applied and reported by the HAL**: every `TotalCaptureResult` has `TONEMAP_MODE=CONTRAST_CURVE` and reads back the curve `(0,0)–(1,1)`. Three independent checks agree with a linear response (§3.3). |
| AE / AWB lock | **Honoured**: `aeState=LOCKED`, `awbState=LOCKED`, exposure/ISO/WB gains constant for the whole lock (drift monitor never fired after the fix in §6). |
| Exposure target (40–60 %) | **Met on every lock** via AE exposure compensation before locking: 43–50 % of full scale on the forehead ROI's brightest channel (red). |
| Zero-light offset | R **1.997**, G **1.744**, B **2.821** (8-bit, 143 frames, locked at 30 ms / ISO 2500). Stored in `oximetry/ZeroLightOffset.kt`. |
| HR regression (mandatory) | **HR not worse; better in this bracket.** A-B-A-B, same person, same session: locked fps 29.1/29.2 vs auto 19.4/24.4; HR MAE vs the oximeter's pulse 7.1/5.5 bpm locked vs 13.8/12.0 bpm auto; tick-to-tick jitter 6.6/4.4 vs 11.8/10.6 bpm (§5). |
| `useOximetryCapture` default | **Stays `false`.** HR passed, but promotion is deferred for the reasons in §5.3 (one subject, one dim room, effect confounded with fps, no re-lock when lighting changes). It is a runtime switch in the developer panel. |
| Calibration CSV | `/sdcard/Android/data/com.spandan.app/files/calibration/spandan_cal_YYYYMMDD_HHMMSS.csv`. Schema in §4. |

## 1. Capability probe (Task 1)

Logged at app start under logcat tag `SPANDAN_CAPS` (`oximetry/CameraCapabilityProbe.kt`),
read with `adb logcat -s SPANDAN_CAPS`. Raw values from the A35 front camera:

| Key | Value |
|---|---|
| `INFO_SUPPORTED_HARDWARE_LEVEL` | `LIMITED` |
| `REQUEST_AVAILABLE_CAPABILITIES` | `BACKWARD_COMPATIBLE`, `BURST_CAPTURE`, `STREAM_USE_CASE` (19). **No `MANUAL_SENSOR`, no `MANUAL_POST_PROCESSING`, no `READ_SENSOR_SETTINGS`, no `RAW`** |
| `TONEMAP_AVAILABLE_TONE_MAP_MODES` | `CONTRAST_CURVE`, `FAST`, `HIGH_QUALITY` |
| `TONEMAP_MAX_CURVE_POINTS` | 128 |
| `CONTROL_AE_LOCK_AVAILABLE` | true |
| `CONTROL_AWB_LOCK_AVAILABLE` | true |
| `SENSOR_INFO_EXPOSURE_TIME_RANGE` | 20 µs – 100 ms |
| `SENSOR_INFO_SENSITIVITY_RANGE` | ISO 40 – 2500 |
| `SENSOR_INFO_MAX_FRAME_DURATION` | 142.9 ms |
| `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | [15,15] [15,20] [20,20] [24,24] [8,30] [10,30] [15,30] **[30,30]** |
| `CONTROL_AE_COMPENSATION_RANGE` / `_STEP` | [−30, +20] steps of **0.1 EV** (−3.0 … +2.0 EV) |
| `CONTROL_AF_AVAILABLE_MODES` | `[OFF]` only, `LENS_INFO_MINIMUM_FOCUS_DISTANCE` = 0 (fixed focus) |
| `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` | `[OFF]` only |
| `SENSOR_INFO_TIMESTAMP_SOURCE` | `REALTIME` (same clock as `SystemClock.elapsedRealtimeNanos`) |

Branch rule (`OximetryMath.decideCapturePlan`, unit-tested): A needs `MANUAL_SENSOR` +
`MANUAL_POST_PROCESSING` + `CONTRAST_CURVE`; otherwise B if AE or AWB lock exists, and B
keeps the linear curve only if `CONTRAST_CURVE` is advertised on its own; otherwise
NONE. **The A35 is B with a linear curve.** Branch A (AE/AWB off, frozen exposure/ISO/gains,
identity CCM, 50 Hz flicker-quantised exposure) is implemented and unit-tested but
**never ran on hardware**, since this phone doesn't support it.

## 2. What was built

All new code is in `android/app/src/main/java/com/spandan/app/oximetry/`:

| File | Role |
|---|---|
| `CameraCapabilityProbe.kt` | Task 1: reads/logs `CameraCharacteristics`, chooses the fps range, feeds the branch decision |
| `OximetryCaptureController.kt` | CameraX `Camera2Interop` state machine: read-only `TotalCaptureResult` callback on every frame; when enabled, pre-roll → metering → lock; verification logging (`SPANDAN_OXI`) |
| `OximetryMath.kt` | Pure math: clip test, brightest-channel fraction, 40–60 % target, Branch-A exposure correction with 50 Hz anti-flicker quantisation, Branch-B AE-compensation step, branch decision, identity CCM, linear curve points, zero-offset subtraction |
| `ZeroLightOffset.kt` | Measured per-model dark offset (Task 3) |
| `ZeroLightMeter.kt` | Averages the centre half of each frame while the zero-light action runs |
| `CalibrationCsvFormat.kt` | Pure CSV formatting (Locale.US, sanitised free text, `EVENT` rows) |
| `CalibrationRecorder.kt` | Background-thread CSV writer |

Changes to existing files:
- `MainActivity.kt`: builds the use cases through `oximetry.configure(...)`; calls
  `onCameraBound`; runtime `useOximetryCapture` (constant `USE_OXIMETRY_CAPTURE_DEFAULT =
  false`); developer panel wiring; per-frame CSV rows; zero-light action. **No temporary
  profiling swap was made this segment**: the HR regression used the calibration CSV and
  the estimator's own logcat lines, not `ProfilingFaceAnalyzer`.
- `RoiPixelAverager.averageRgb` counts clipped pixels (any channel ≥ 250) among the sampled
  pixels; `RgbSample` gains `clippedPixels`/`sampledPixels` (defaulted, so other call sites
  are unchanged).
- `FaceAnalysisResult.FaceDetected` gains `sensorTimestampNs` (`ImageInfo.timestamp`,
  defaulted to 0; `ProfilingFaceAnalyzer` doesn't set it and doesn't need to).
- `activity_main.xml`/`strings.xml`: developer calibration panel, hidden by default.

**When `useOximetryCapture` is false the camera is configured exactly as before.** The
controller attaches a read-only session capture callback (so the CSV can log AE state
and exposure in auto mode too) and calls `Camera2CameraControl.clearCaptureRequestOptions()`.
That call matters: `Camera2CameraControl` options survive an unbind/rebind of the same
camera, so turning the switch off would otherwise leave the camera locked.

### 2.1 Branch B sequence as implemented

1. **Pre-roll (2 s, up to 5 s until AE/AWB leave `SEARCHING`)**: AE and AWB run; set are
   `CONTROL_AE_TARGET_FPS_RANGE=[30,30]`, `TONEMAP_MODE_CONTRAST_CURVE` with `(0,0),(1,1)`
   on R/G/B, `CONTROL_AF_MODE_OFF`, OIS off, video stabilisation off. The linear curve goes
   on *before* metering so the level we meter is the level we lock at.
2. **Metering**: the median brightest-channel fraction over 12 ROI frames, counted only
   from frames whose `TotalCaptureResult` shows the requested compensation and
   `aeState=CONVERGED`. If it's outside 40–60 %, step `CONTROL_AE_EXPOSURE_COMPENSATION`
   by `round(log2(0.5/f)/stepEV)` (with a linear curve, output ∝ 2^EV). At most 4
   iterations; 15 s timeout if there's no face.
3. **Lock**: `CONTROL_AE_LOCK=true`, `CONTROL_AWB_LOCK=true` with the same compensation.
   The first result reporting the lock in effect becomes the drift reference; later
   exposure/ISO changes > 2 % or WB gain changes > 1 % log `DRIFT ... lock NOT fully
   honoured`.

The request goes to the one repeating request CameraX shares between Preview and
ImageAnalysis, so **the preview is also linear/locked** (it looks darker and flatter than
normal in this mode).

## 3. What the hardware actually applied (read back from `TotalCaptureResult`)

### 3.1 Requested vs applied

| Key | Requested (Branch B) | Applied (HAL result, every locked frame) |
|---|---|---|
| AE | lock after metering | `aeMode=ON`, `aeState=LOCKED`, `aeLock=true` |
| AWB | lock after metering | `awbMode=AUTO`, `awbState=LOCKED`, `awbLock=true`, gains constant (e.g. 2.192/1.000/1.000/1.965 RGGB) |
| Tone map | `CONTRAST_CURVE`, (0,0)–(1,1) ×3 | `tonemapMode=CONTRAST_CURVE`, curve `n=2 (0,0)…(1,1)` |
| Frame rate | `[30,30]` | `SENSOR_FRAME_DURATION=33.333 ms` |
| Exposure | (AE decides) | 30 ms in every lock = 3 × 10 ms (AE antibanding `AUTO` already flicker-safe for 50 Hz mains) |
| ISO | (AE decides) | 1856–2500 depending on room light (2500 = sensor max in most runs) |
| AE compensation | +9…+14 steps (0.1 EV) | reported back identically |
| Colour correction | (cannot set on LIMITED) | `COLOR_CORRECTION_MODE=FAST`: the ISP's own 3×3 matrix is still applied. With AWB locked it should be fixed, but it is **not identity**, so R/G/B are a fixed linear mix of sensor channels, not raw sensor channels |
| `CONTROL_POST_RAW_SENSITIVITY_BOOST` | — | 100 (no *reported* boost) |
| AF / OIS | OFF | `afMode=OFF` (fixed-focus lens, no OIS) |

### 3.2 Exposure metering: hidden ISP gain

| Run | Before (comp 0) | ISO before → after | Requested comp | After | Output ratio | 2^EV |
|---|---|---|---|---|---|---|
| 1 | 25.3 % | 1437 → 2500 | +10 (1.0 EV) | 45.4 % | ×1.79 | ×2.00 |
| 2 | 19.3 % | 1566 → 2500 | +14 (1.4 EV) | 50.1 % | ×2.60 | ×2.64 |
| 3 | 19.1 % | 1742 → 2500 | +14 (1.4 EV) | 47.3 % | ×2.48 | ×2.64 |

The reported ISO caps at 2500 and `postRawBoost` stays 100, yet the output keeps scaling
with the requested EV. **Samsung applies extra digital gain that it doesn't report once
ISO hits its cap.** Consequence for the offline analysis: the CSV's `iso` column is **not
the total gain**. Within one locked recording that doesn't matter (the gain is frozen), but
don't compare absolute brightness across sessions using ISO. Practical consequence for
data collection: this room was dim (auto mode used 40–70 ms exposures at ISO ~1000). A
brighter lamp would give a lower gain and less noise.

### 3.3 Is the output actually linear? Three independent checks

This isn't a controlled linearity test (that needs a static target and a
compensation sweep; flagged in §7). The HAL reports the curve, and three pieces of
evidence are consistent with a linear response:
1. **Readback**: `TONEMAP_CURVE` in every result is the 2-point identity curve.
2. **EV tracking** (§3.2): output/2^EV = 0.90, 0.98, 0.94. Under a gamma-2.2-like default
   curve the same 1.4 EV would move the output only ×1.55, and 1.0 EV only ×1.37.
3. **Channel ratios between modes** (from the HR regression captures): raising the auto-mode
   ROI ratios to the power 2.2 predicts the locked-mode ratios within about 5–10 %:

| | auto R/G → predicted (^2.2) | locked R/G | auto B/G → predicted | locked B/G |
|---|---|---|---|---|
| bracket 1 (c1→c2) | 1.292 → 1.76 | 1.664 | 0.845 → 0.69 | 0.709 |
| bracket 2 (c3→c4) | 1.317 → 1.83 | 1.866 | 0.810 → 0.63 | 0.663 |

Check 3 is approximate: the sRGB-like curve is not a pure 2.2 power law, and WB gains
drift a little between auto sessions.

### 3.4 Clipping

The locked ROI (brightest channel at 43–50 %) clipped **0 %** of sampled pixels in one
locked capture and **1.18 %** in the other (a few specular pixels on the forehead, 3–5
of ~520 sampled). Auto mode showed 0 % in these captures even though its red channel
sat at 63–68 % of full scale, and briefly around 90 % (RGB 230/199/177) in an earlier
screenshot before the subject settled. The per-frame `clipped_px`/`sampled_px` columns let
the offline analysis drop frames above a chosen threshold (Segment 33 §6 suggests
> 1 %).

## 4. Calibration recording mode (Task 4)

### 4.1 How to use it (for the Segment 33 §7 breath-hold sessions)

1. Long-press the HEART RATE / BLOOD OXYGEN card. The developer panel appears over the
   top of the preview.
2. Turn on **Locked linear**. The status line goes `PREROLL` → `METERING` → `LOCKED` (about
   3–8 s with a face in frame). Keep your face in frame while it meters. The status line
   shows exposure, ISO, compensation, AE/AWB state, tone curve, ROI RGB and the clip count.
3. Type the **lighting condition** (e.g. "single desk LED lamp") and **wait ~25 s** so the
   HR window fills with locked data.
4. **Start rec.** The big counter (`mm:ss.t`) starts, so a second phone filming the oximeter
   display can be aligned to it. Tap **Hold start** / **Hold end** at each breath-hold and
   **Mark** for anything else. **Stop rec** closes the file.
5. `adb pull /sdcard/Android/data/com.spandan.app/files/calibration/`

The switch and lighting field are disabled while recording, so one file can't mix modes.
No video frame is stored anywhere. Only ROI means and metadata are written.

Zero-light action (Task 3): with the camera `LOCKED`, tap **Zero-light 5 s**, then within
3 s lay the phone **face-down on a table** (a finger is not dark enough: light passes
through it). The result is logged as `SPANDAN_ZERO_LIGHT`, shown in the panel, and written
as a `note` event if a recording is running.

### 4.2 File format

Header: `# key=value` lines. It includes schema version, app version, wall-clock start,
`recording_start_timestamp_ns`, timestamp source, phone manufacturer/model/device, Android
version, camera id and hardware level, capability branch, active capture mode, whether the
linear curve is active, lock state and note at start, requested AE compensation, applied
exposure/ISO/WB gains/tone mode at start, ROI brightest fraction at lock, fps range,
zero-light offset (constant and this-session measurement), **lighting condition**, and
notes on RGB, clipping, ROI coordinates, the HR/SpO2 columns and event rows. Then one
column line, then rows.

| # | Column | Meaning |
|---|---|---|
| 1 | `timestamp_ns` | frame sensor timestamp (`SENSOR_TIMESTAMP`, REALTIME clock on the A35) |
| 2–4 | `R_mean`,`G_mean`,`B_mean` | forehead-ROI means, 8-bit, BT.601 from YUV_420_888, stride-2 sampled, **raw** (zero-light offset NOT subtracted) |
| 5 | `clipped_px` | sampled ROI pixels with any channel ≥ 250 |
| 6–9 | `roi_left/top/right/bottom` | ROI rect in the upright (rotated) 480×640 analysis frame |
| 10–11 | `exposure_ns`,`iso` | from that frame's `TotalCaptureResult` (ISO is not total gain; §3.2) |
| 12–13 | `ae_state`,`awb_state` | Camera2 enums (2 CONVERGED, 3 LOCKED, 1 SEARCHING) |
| 14 | `hr_bpm_current` | displayed HR (after display smoothing), 1 Hz recompute |
| 15 | `spo2_current_displayed` | displayed SpO2 (**unchanged formula**) |
| 16–17 | `pi_red`,`pi_blue` | `LiveSpo2Estimator` perfusion indices of the latest window |
| 18 | `sampled_px` | pixels averaged (for `clipped_px` as a fraction) |
| 19 | `elapsed_ms` | sensor time since the Start tap. The first 3–4 rows are slightly negative (frames exposed up to ~150 ms before the tap: pipeline latency) |
| 20 | `lock_state` | `AUTO` / `PREROLL` / `METERING` / `LOCKED` / `UNSUPPORTED` |
| 21 | `tonemap_mode` | from the result (0 = CONTRAST_CURVE, 1 = FAST) |

Event rows: `EVENT,<timestamp_ns>,breath_hold_start|breath_hold_end|note[,text]`. They use
the same clock as `timestamp_ns`. On-device check: taps at about 3, 6 and 8.5 s were written at
3.14, 6.30 and 8.42 s after start.

Numbers are formatted with `Locale.US` (unit-tested under Bengali, German and Arabic
default locales). Commas and newlines in free text are replaced so a note can't break a row.

**Row/metadata join, a real bug found on device and fixed**: writing each row
immediately left **21–32 % of rows without exposure/ISO/AE state**. On skipped-detection
frames the ROI sample reaches the main thread before the camera has delivered that frame's
`TotalCaptureResult`. Rows are now held (in order) up to 300 ms for their metadata.
After the fix a 25 s locked recording had **0 / 731** rows without metadata, all in
timestamp order. The four HR-regression CSVs below were recorded **before** this fix, so
some of their rows have empty `exposure_ns`/`iso`/`ae_state`. The lock state and the
per-second `SPANDAN_OXI` logs show the camera stayed locked throughout.

## 5. HR regression check (Task 5, mandatory)

### 5.1 Method

Segment 28/29 discipline: same person, same session, same chair and room light,
**bracketed A-B-A-B** (auto → locked → auto → locked), 60 s per capture. Each mode switch
clears the signal buffer; each recording started only after a 35 s (auto) or 40 s (locked)
settle, so the 25 s HR window held data from that mode only. Measurements:
- **fps**: from the calibration CSV's frame timestamps.
- **HR**: the raw switched CHROM/POS value logged by `RealHeartRateEstimator` at each
  1 Hz recompute (before display smoothing), plus the displayed value from the CSV.
- **Reference**: the subject's finger pulse oximeter, pulse-rate (PR) range read off its
  display during each minute and reported right after.

Battery 60–62 %, battery temperature 32.6–33.3 °C. Raw data, analysis script and summary are in
`android/docs/segment34_hr_regression/` (`c1_auto.csv` … `c4_locked.csv`, the matching
filtered `logcat_*.txt`, `analyze_hr_regression.py`, `segment34_hr_regression_summary.csv`).

### 5.2 Result

| Capture | Mode | fps | Exposure / ISO | HR raw mean ± SD | Oximeter PR | **HR MAE vs PR mid** | Raw HR within PR ±3 | **Mean tick-to-tick jump** (raw / displayed) |
|---|---|---|---|---|---|---|---|---|
| c1 | auto | **19.44** | 50 ms / 1033 | 63.2 ± 11.8 | 72–76 | **13.8** | 21 % | 11.8 / 3.7 |
| c2 | locked | **29.09** | 30 ms / 2500 | 70.4 ± 9.4 | 71–75 | **7.1** | 62 % | 6.6 / 2.4 |
| c3 | auto | **24.39** | 40 ms / 759–936 | 74.0 ± 12.2 | 77–84 | **12.0** | 22 % | 10.6 / 4.6 |
| c4 | locked | **29.17** | 30 ms / 2119 | 76.2 ± 8.9 | 76–81 | **5.5** | 67 % | 4.4 / 0.9 |

(Displayed-HR MAE vs PR mid: 13.2 / 5.6 / 10.6 / 4.7 bpm.) Both locked captures beat both
auto captures on every HR metric, and the two auto captures bracket the locked ones in time,
so steady drift in one direction (warm-up, fatigue, HR rising from 74 to 79 over the
session) can't explain it. Occasional single-tick harmonic jumps (~45–53 bpm max) happen
in both modes.

### 5.3 Verdict: HR is not worse, and was better here. Default stays `false`

The Segment 34 bar ("keep the default `false` unless HR is demonstrably not worse") is met.
Promotion to default-on is **still deferred**, for four stated reasons:
1. **Confounded with frame rate.** In this dim room auto-exposure chose 40–50 ms
   exposures, which caps the sensor at 20–25 fps. The locked mode's fixed `[30,30]` range
   forces ≤ 33 ms and full 30 fps. Much of the HR gain may come from fps, not from
   linearity or locking. In a bright room auto mode would also run at 30 fps and the gap may
   shrink. Not separated this session.
2. **n = 1 subject, 1 session, 1 lighting condition, 4 minutes.**
3. **No re-lock logic.** The lock is taken once. If the light changes, or no face is present
   when metering times out, the camera stays at a stale setting until the switch is
   toggled. That's fine for a supervised calibration session, not for an unattended default.
4. **The reference is coarse**: a self-reported PR range per minute, not a logged oximeter trace.

Two decisions for the team: (a) whether to promote `USE_OXIMETRY_CAPTURE_DEFAULT` after a
repeat in bright light and ideally a second person; (b) independently of that, whether the
fixed 30 fps range alone should become the auto-mode default (a separate, smaller change
that this data can't isolate).

## 6. Problems found and fixed during this session

- **False `DRIFT` warning**: the drift reference was taken from the frame that *requested*
  the lock, whose WB gains still moved by 0.001. The reference is now the first frame that
  reports the lock in effect, with a 1 % gain tolerance. No drift was logged after the fix.
- **Missing row metadata** (21–32 % of rows), fixed with the in-order pending-row queue (§4.2).
- **Developer-panel buttons moved** when the status text wrapped, and an adb-driven Stop tap
  missed. The status line is now a fixed 4 lines. That first capture was discarded and the
  full A-B-A-B bracket re-run on the fixed build, so all four captures used the same APK.
- **Log throttle race**: `TotalCaptureResult`s arrive on several camera threads.
  `record()` is now synchronised and the 1 Hz throttle uses an atomic compare-and-set.

## 7. Known limitations / open items

- **Branch A was never exercised on hardware** (the A35 lacks it). Its code path (AE/AWB off,
  identity CCM, flicker-quantised exposure) is unit-tested only for the math.
- **CCM is not identity** in Branch B (`COLOR_CORRECTION_MODE=FAST`). The logged channels are
  a fixed linear mix of the sensor channels, which is fine for a within-session anchored
  trend (Segment 33 §6) but not a physical R/G/B.
- **Hidden ISP gain** above ISO 2500 (§3.2): `iso` isn't total gain.
- **Linearity** rests on the HAL readback plus the two indirect checks in §3.3. A controlled
  test is still open: a static matte target, then an AE-compensation sweep in both tone
  modes, comparing output/2^EV.
- **Zero-light offset measured at maximum gain only.** Re-measure at the ISO a brighter
  recording room gives.
- **YUV is 8-bit and BT.601-converted.** Quantisation is ~0.2–0.4 % of the ROI level at
  43–50 % full scale; spatial averaging over ~500 pixels reduces it further. RAW isn't
  available on this camera.
- **Face-vs-finger SpO2 lag** is not corrected on device, by design (Sasaki 2024: estimate it
  offline per session).
- The CSV logs the **displayed** HR/SpO2, which lag the frame by up to ~1 s (1 Hz recompute).

## 8. Unit tests

`OximetryMathTest` (12) and `CalibrationCsvFormatTest` (6). They cover the clip threshold,
brightest fraction, target window, flicker quantisation, the Branch-A exposure correction
(product, capping, ISO clamping, darkening, invalid input), the Branch-B compensation step,
zero-offset subtraction, the branch decision, identity CCM and linear curve, the column
order vs the brief, the field count, nulls, locale independence, event rows and header
sanitising. No test constructs `android.graphics.Rect`, so this project's known 4-arg
`Rect` constructor no-op trap (Segment 28) can't make these tests pass vacuously.
**83 / 83 unit tests pass**: 65 before this segment (counted from the committed `@Test`
annotations; Segment 31's doc says 66) plus 18 new.

## 9. Next step (human data collection, not code)

Run Segment 33 §7's breath-hold protocol with this mode: a fixed lamp, the oximeter display
filmed together with the phone's elapsed counter, **Hold start/Hold end** marked, 2 sessions
per person on different days. Then fit β offline on the CSVs (ln of AC ratios after
zero-offset subtraction and clip gating) before any change to `LiveSpo2Estimator.kt`.
