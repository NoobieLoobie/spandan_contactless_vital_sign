# Paste-ready prompt for a new local Claude Code CLI session — Segment 34 (SpO2 acquisition + calibration recorder)

Start Claude Code with the repo as the working directory:

```
cd "H:\EEE 312 project\Contactless Vital Sign\spandan"
claude
```

Then paste everything between the lines below.

---

You are continuing the Spandan project (BUET EEE 312, contactless HR/SpO2 from face video; MATLAB reference pipeline + Android app in Kotlin/CameraX + iOS port). Work as **Segment 34**.

## Read first (in this order, directly from disk — do not rely on memory)
1. `H:\EEE 312 project\Contactless Vital Sign\spandan\SESSION_HANDOFF.md` — follow its MAINTENANCE PROTOCOL exactly (strike through, never delete; dated entries; changelog line).
2. `H:\EEE 312 project\Contactless Vital Sign\spandan\matlab\docs\Segment33_SpO2_Research_and_New_Pipeline.md` — the diagnosis and design this segment implements. Key facts: the shipped SpO2 formula (`SpO2 = 96.476 + 0.416·R`) is effectively a constant; VIPL-HR cannot calibrate SpO2; the camera runs fully automatic; the literature's largest measured gain is locking the camera and forcing a linear tone curve (Xuan et al. 2023, Front. Digit. Health 5:1301019); the estimation design is an anchored trend `SpO2 = S0 + β·(L − L0)` whose β must be learned from our own breath-hold recordings.
3. `H:\EEE 312 project\Contactless Vital Sign\spandan\android\app\src\main\java\com\spandan\app\MainActivity.kt` (camera setup around the `ImageAnalysis.Builder()` call), `...\signal\LiveSpo2Estimator.kt`, `...\RoiPixelAverager.kt`, `...\FaceAnalyzer.kt`, `...\ProfilingFaceAnalyzer.kt`, `...\signal\SignalBuffer.kt`, `...\signal\RealHeartRateEstimator.kt`.
4. The camera-oximetry papers that motivate this segment (PDFs — read the method/camera-setup sections, not just abstracts), all in `H:\EEE 312 project\Contactless Vital Sign\Research Paper\`:
   - `Camera-based pulse-oximetry - validated risks and opportunities from theoretical analysis.pdf` (van Gastel, Stuijk & de Haan 2018) — motion changes all channels equally (common-mode); two channel pairs give a reliability index.
   - `Non-Contact Measurement of Blood Oxygen Saturation Using Facial Video Without Reference Values.pdf` (Sasaki et al. 2024) — log-domain amplitude; green as shallow-layer reference; face SpO2 lags the finger by seconds; ~+1 % error under uncontrolled ambient light.
   - `Calibration of Contactless Pulse Oximetry.pdf` (Verkruysse et al. 2017) — low-SNR data must be gated out before calibrating.
   - `Analysis and improvement of non-contact SpO2 extraction using an RGB webcam.pdf` (Wei et al. 2021) — breath-hold protocol with webcams; std-based AC is noise-dominated.
   The camera-locking recipe itself (Xuan et al. 2023, Front. Digit. Health 5:1301019, open access) is summarised in the Segment 33 doc §3.1.
5. `android\docs\Segment28_Throughput_Improvement.md` and `android\docs\Segment29_MinFaceSize_And_Kalman.md` — for this project's on-device measurement discipline (bracketing baselines, session-to-session noise band, `adb logcat` captures, revert temporary swaps before committing).

## Before starting: commit Segment 33 separately
Segment 33 (a Cowork research session) left these **uncommitted** in the working tree: `matlab/docs/Segment33_SpO2_Research_and_New_Pipeline.md`, `matlab/experiments/segment33_spo2_research/` (Python scripts + results CSVs + this prompt), and edits to `matlab/docs/Literature_Review_Master.md` and `SESSION_HANDOFF.md`. Run `git status` and `git diff` on those, then commit them alone as `Segment 33: SpO2 diagnosis, literature search, offline VIPL evaluation, v2 design` (same precedent as Segment 17's commit `a1ec43e`). `SPANDAN_MASTER_HANDOFF.md` also shows as modified but was **not** touched by Segment 33 — inspect its diff and ask me before committing it.

## Goal of this segment
Make the app capture **oximetry-grade, linear, locked** RGB and add a **calibration recording mode** that logs everything needed to fit β later. **Do not change the displayed SpO2 formula in this segment** — there is no β yet; changing the number now would just be a different unvalidated guess.

## Tasks
1. **Capability probe (first, before any design decision).** On the connected phone (Samsung Galaxy A35, adb serial previously `RFCXC0FFFSN`; check `adb devices`), log for the FRONT camera via `CameraCharacteristics`: `REQUEST_AVAILABLE_CAPABILITIES` (is `MANUAL_SENSOR` / `MANUAL_POST_PROCESSING` present?), `TONEMAP_AVAILABLE_TONE_MAP_MODES` (is `CONTRAST_CURVE` available?), `TONEMAP_MAX_CURVE_POINTS`, `CONTROL_AE_LOCK_AVAILABLE`, `CONTROL_AWB_LOCK_AVAILABLE`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_SENSITIVITY_RANGE`, `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. Write the results into the segment doc. The design below branches on them — report which branch applies.
2. **New gated camera mode `useOximetryCapture` (default `false`)** in `MainActivity.kt`, via CameraX `Camera2Interop.Extender` on the `ImageAnalysis` use case (and Preview if needed for consistency):
   - Pre-roll ≈ 2 s in normal auto mode, read back the converged exposure time / ISO / colour gains from `TotalCaptureResult`, then freeze them.
   - Branch A (MANUAL_SENSOR + MANUAL_POST_PROCESSING available): `CONTROL_AE_MODE_OFF` with the frozen `SENSOR_EXPOSURE_TIME`/`SENSOR_SENSITIVITY`, `CONTROL_AWB_MODE_OFF` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` with identity `COLOR_CORRECTION_TRANSFORM` and the frozen `COLOR_CORRECTION_GAINS`, `TONEMAP_MODE_CONTRAST_CURVE` with linear curve points `(0,0),(1,1)` on all three channels, AF locked/off, fixed target FPS range.
   - Branch B (no manual control): `CONTROL_AE_LOCK = true` and `CONTROL_AWB_LOCK = true` after convergence, and if `CONTRAST_CURVE` is unavailable, log that the tone curve stays non-linear (flag it in the doc; do not fake linearity).
   - Keep ALL three channels linear and unclipped — the offline pipeline needs green as well as red/blue (green is the shallow-layer reference in Sasaki's PCA method and the second channel pair for the reliability index).
   - Exposure target: forehead-ROI mean of the brightest channel at ~40–60 % of full scale, and **count clipped pixels (any channel ≥ 250)** inside the ROI per frame; expose that count.
   - Verify on device by reading `TotalCaptureResult` each frame that the keys actually took effect (log AE/AWB state, exposure, ISO, tonemap mode). Report what the hardware really applied, not what was requested.
3. **Zero-light offset.** Add a one-off developer action: cover the front camera, record 5 s in `useOximetryCapture`, log mean R,G,B (the per-model zero-light offset in Xuan 2023's sense). Store the constants in a small Kotlin object with a comment giving the measurement date and phone model.
4. **Calibration recording mode** (developer screen or long-press toggle): writes a CSV to the app's external files dir with one row per analyzed frame: `timestamp_ns, R_mean, G_mean, B_mean, clipped_px, roi_left, roi_top, roi_right, roi_bottom, exposure_ns, iso, ae_state, awb_state, hr_bpm_current, spo2_current_displayed, pi_red, pi_blue`, plus a header block with phone model, camera capability branch (A/B), zero-light offset, fps range, and a free-text **lighting condition** field (e.g. "single desk LED lamp", "ceiling tube + window") entered before recording. Add an **event-marker button** that writes a row `EVENT,<timestamp_ns>,breath_hold_start|breath_hold_end|note` so each breath-hold is marked in the log. Also add a big on-screen elapsed-time counter so the separately-filmed pulse-oximeter display can be time-aligned (the face-vs-finger SpO2 lag of several seconds is estimated offline, per Sasaki 2024 — do not try to correct it on device). Pull files with `adb pull`. Do not store any video frames.
5. **Regression check on HR (mandatory).** Locking exposure/linear tone curve changes the pulse amplitude and can change HR accuracy. Using the Segment 28/29 method (bracketing baseline captures, same session, same person), compare `useOximetryCapture=false` vs `true`: effective fps, HR stability (tick-to-tick jitter), and a manual HR check against a finger pulse count or the oximeter's pulse reading. Keep `useOximetryCapture` default `false` unless HR is demonstrably not worse.
6. **Unit tests** for any pure math you add (e.g. clip counting, CSV row formatting, zero-offset subtraction). Beware this project's known plain-JUnit trap: `android.graphics.Rect` constructors are no-ops under `isReturnDefaultValues=true` (see Segment 28/30 docs) — use the existing `makeRect`-style helper.
7. **Docs + bookkeeping.** New doc `android\docs\Segment34_SpO2_Oximetry_Capture.md` (capability table, which branch applied, what the hardware actually applied, HR regression numbers, CSV schema, known limitations). Update `SESSION_HANDOFF.md` (Current State + Active Work Queue + Changelog, dated 2026-09-xx), `android\README.md`. Commit on `main` as `Segment 34: ...` (commit-per-segment is this repo's standing practice). Revert any temporary `MainActivity`/profiling swaps before committing, and confirm with `git diff`.

## Out of scope for Segment 34 (do NOT do these)
- Changing `CALIBRATION_A/B` or the displayed SpO2 number.
- Touching `matlab/src/` or any MATLAB production file.
- iOS changes (a later segment will mirror this with AVFoundation's locked exposure/white balance).
- Deep-learning models.
- Implementing any new AC estimator (log-domain, PCA-with-green, narrow-band) or the anchored SpO2 formula on device — those are chosen offline from the breath-hold data this segment makes possible.

## When finished, report back
Capability branch (A/B) and which keys the Galaxy A35 front camera actually honoured; zero-light offsets; HR regression numbers with the bracketing baselines; the CSV path/schema; the commit hash. Then stop — the next step is human data collection (breath-hold sessions with the pulse oximeter visible on camera, protocol in Segment 33 §7), followed by fitting β offline.
