# Segment 35 Phase 2/3 — Android fix pack + oximeter viewing box

Date: 2026-09-28 · Status: **code complete, 0 physical device available this session — every
item below needs Phase 4's real on-device check before any new default is trusted.**
Executes `docs/Segment35_Accuracy_Research_and_Plan.md` Phases 2 and 3. All 96 unit tests
pass (`./gradlew testDebugUnitTest`, 83 pre-existing + 13 new); `assembleDebug` succeeds
(full debug APK builds clean). No MATLAB/iOS file touched.

**Read first**: `docs/Segment35_Accuracy_Research_and_Plan.md` §2 (the H1-H6 diagnosis this
phase fixes) and §4 (the oximeter box's original design). `android/docs/Segment29_
MinFaceSize_And_Kalman.md` (minFaceSize/Kalman context) and `Segment34_SpO2_Oximetry_
Capture.md` (the capture controller/recorder this phase extends).

---

## 0. Bottom line

| Item | What changed | On-device status |
|---|---|---|
| 1. minFaceSize | 0.35 → 0.18 | **CANDIDATE, NOT VALIDATED** — reasoned, not measured |
| 2. Lost-face gap handling | SignalBuffer now clears + "re-acquiring" UI on a real >=0.5s gap (real timestamps), instead of silently splicing | Unit-tested (pure logic); needs real gap capture |
| 3. Uniform resample | Branch 1 (HR + SpO2) now resamples to a uniform 30Hz grid via real per-sample sensor timestamps before filtering, same as Branch 2 already did | Unit-tested; needs real-device fs/jitter check |
| 4. RGBA_8888 + widened ROI | 1280x720 RGBA output (was 640x480 YUV), stride 1 (was 2), forehead+both cheeks pooled (was forehead only) | **Not measured on-device** — fps/throughput cost unknown |
| 5. Oximetry auto re-lock | Re-locks on >15% ROI brightness drift or face reacquired after loss; clears signal buffer on every re-lock | No new pure-math logic to unit-test (Camera2 types aren't mockable here, same as every existing untested class in `camera/`/`oximetry/`) |
| Oximeter viewing box (Phase 3) | Guide box + labeled overlay, view→sensor crop mapping, live upright un-mirrored inset, once/sec JPEG saved into the existing calibration recorder, full-preview un-mirror fallback toggle | Not run — needs a face + phone to actually try holding an oximeter in frame |

**Nothing here is promoted as a validated default.** Every numeric choice (0.18 minFaceSize,
0.5s gap threshold, 15% drift threshold, 30Hz resample target, the guide box's screen
fraction) is a reasoned candidate per the plan's own instructions, explicitly flagged
NOT on-device-verified, because no physical device was available this session (same
constraint every prior no-device segment in this project states plainly rather than
guessing past it).

---

## 1. Phase 2 — App fix pack

### Item 1 — `FaceAnalyzer.kt`: `minFaceSize` 0.35 → 0.18

Segment 29's 0.35 promotion was measured only with the phone on a stand at "normal" and
"increased" distance — never handheld at arm's length, where the face fills a smaller
fraction of the 640x480 (now 1280x720, see item 4) analysis frame. Segment 29's own finding
was that 0.1→0.35 was **entirely a detector-speed effect** (fewer pyramid levels scanned),
not an accuracy one, and Segment 18/28's every-Nth-frame detection skip already amortizes
that per-detection cost — so a smaller value's cost is bounded. Set to 0.18 (middle of the
plan's requested 0.15-0.20 range). **CANDIDATE, NOT VALIDATED**: Phase 4 must re-measure
missed-face % and fps handheld, bracketing 0.18 against 0.35 the same way Segment 29
bracketed 0.1 against 0.35.

### Item 2 — `SignalBuffer.kt` gap detection

`SignalBuffer.add()` now uses each sample's real `sensorTimestampNs` (already plumbed
through `FaceAnalysisResult`, previously never read for this) to detect the wall-clock gap
since the last sample:
- **< 0.5s**: left alone — ordinary jitter, which item 3's uniform resample already
  accounts for using the same real timestamps.
- **>= 0.5s**: buffer cleared, `isReacquiring = true` until 3 fresh samples accumulate.
  `MainActivity` shows a "Re-acquiring signal…" banner (distinct text from "No face
  detected" — a face IS present, the window was just cleared) and blanks HR/SpO2 rather
  than showing `RealHeartRateEstimator`'s own cached last value (which its own `update()`
  returns during warm-up, not null — a real trap this phase found: simply clearing the
  buffer is not enough on its own, the UI layer also has to know not to show the estimator's
  stale cache).
- Also wired to `OximetryCaptureController.onFaceReacquired()` (item 5) — a real gap is
  exactly "face reacquired after loss."
- Backward compatible by construction: `RgbSample.sensorTimestampNs` defaults to 0, and gap
  detection is skipped whenever either timestamp is 0 (every existing call site/test).

**7 new unit tests** (`SignalBufferTest.kt`): backward-compat no-op, sub-threshold gap kept,
threshold-and-above gap clears + sets `isReacquiring`, `isReacquiring` clears after enough
fresh samples, a second gap right after the first keeps only the latest data, `clear()`
resets gap-tracking state too, existing window-cutoff behavior unaffected.

**Needs Phase 4**: whether 0.5s is the right threshold (too eager would clear the buffer on
completely normal every-Nth-frame skip cycles; too lax would still let a real several-second
loss splice through) — no real handheld capture to check this against this session.

### Item 3 — Uniform 30Hz resample before filtering (`RealHeartRateEstimator.kt`, `LiveSpo2Estimator.kt`)

`ResampleUniform.resampleRgbSamples()` (new function) resamples R/G/B onto a uniform 30Hz
grid using each sample's real `sensorTimestampNs`, via the SAME `PchipInterpolator` Branch 2
(`MorphologyWaveformEstimator`) already validated — Branch 1 previously computed
`fs = (N-1)/windowSeconds` and fed raw, irregularly-timed samples straight into the filter
chain, exactly finding H3's "every-3rd-frame jitter from the async ML Kit callback" problem.
Both estimators fall back cleanly to the old naive-fs path when real timestamps aren't
available (every existing unit test, since none of them set `RgbSample.sensorTimestampNs`) —
verified this fallback is exercised, not silently broken, by the full existing test suite
still passing unchanged.

**4 new unit tests** (`ResampleUniformTest.kt`): null on missing timestamps, null on < 2
samples, null on non-increasing timestamps, and a real jittered-~20fps-over-3s case
confirming R/G/B all resample to the same length at exactly 30Hz and a constant channel
stays constant (no drift from the interpolation itself).

**Needs Phase 4**: real on-device fs/jitter measurement to confirm this actually changes the
HR/SpO2 numbers in the direction expected (H3 predicted a bias, not measured this session).

### Item 4 — RGBA_8888 @ 1280x720, stride 1, forehead+cheeks (`RoiPixelAverager.kt`, `FaceAnalyzer.kt`, `MainActivity.kt`)

- `ImageAnalysis` now requests `OUTPUT_IMAGE_FORMAT_RGBA_8888` at a `ResolutionSelector`
  target of 1280x720 (`FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`), replacing the previous
  YUV_420_888 @ 640x480 default.
- `RoiPixelAverager` rewritten for RGBA's single interleaved plane (no more BT.601 YUV→RGB
  math) — `averageRgb` now just delegates to `averageRgbMultiRect` with a one-element list,
  removing the duplicate averaging loop the two functions previously had.
- `SAMPLE_STRIDE` 2 → 1 (every pixel), since a single-plane RGBA read is cheaper per pixel
  than three separate YUV plane lookups were.
- `FaceAnalyzer.emitFaceDetected` now pools **forehead + both cheeks**
  (`RoiCalculator.foreheadRoiFrom` + the already-existing but previously debug-only
  `cheekRoisFrom`) into one `RgbSample`, instead of forehead alone — the same geometry the
  Field Guide Action 1 pilot (Segment 9) already measured as ~free on-device (13.43→13.35fps,
  a single-cheek-pass test; three-region pooling is not identical but the per-pixel cost
  should scale similarly). Phase 1's MediaPipe landmark-driven anatomy ROI is MATLAB-only
  this session (see the plan's own fallback instruction) — this uses the existing
  face-box-fraction geometry, not a ported landmark detector.
- `FaceAnalysisResult.FaceDetected` gained `roiBoxesRotated: List<Rect>` (defaulted to
  `listOf(roiBoxRotated)` — every pre-existing construction site in `ProfilingFaceAnalyzer.kt`
  is unchanged); `OverlayView` now draws every pooled region, not just one box.

**Needs Phase 4, explicitly not measured this session**: (a) whether 1280x720 RGBA costs more
per-frame than 640x480 YUV did — larger frame, but no YUV conversion and stride 1 vs 2 pull
in opposite directions, net effect unknown without a real capture; (b) whether the 3-region
average changes HR/SpO2 accuracy at all (Segment 7's own finding was that Branch 1 is
insensitive to ROI shape on *static* UBFC clips — this segment's whole premise is that
motion/handheld conditions might differ, untested here for the same reason Phase 1 exists).

### Item 5 — Oximetry auto re-lock (`OximetryCaptureController.kt`)

Two new triggers, both re-entering the SAME `METERING` state machine `onRoiSample`/
`onRoiSampleBranchB` already drive to a fresh `finishLock()` (not a second code path):
- **Brightness drift**: while `LOCKED`, every ROI sample's brightest-channel fraction is
  compared to `lockedBrightestFraction`; a >15% relative drift calls `startRelock()`.
- **Face reacquired**: `onFaceReacquired()` (called from `MainActivity` when `SignalBuffer.
  isReacquiring` — item 2 — is true) re-locks if currently `LOCKED`.
- `startRelock()` unlocks AE/AWB first for Branch B (exposure/WB compensation has no effect
  while locked), resets the metering iteration counter, and calls the new `onRelock`
  callback — wired in `MainActivity` to `signalBuffer.clear(); hrDisplaySmoother.reset()`,
  since an exposure step hits every channel at once (same reasoning the existing
  oximetrySwitch listener already uses when the capture MODE changes).
- Found and fixed along the way: `finishLock()` never reset `driftWarned`, so the
  post-lock drift-warning log could only ever fire once per app session, not once per lock —
  irrelevant before re-locking existed (`finishLock` only ever ran once), a real latent gap
  now that it can run again.

**No new unit test**: `OximetryCaptureController` (like every class in `camera/`/`oximetry/`
that touches `ImageProxy`/`TotalCaptureResult`/`CaptureResult`) has zero existing unit-test
coverage in this project — those are final Android framework classes this project's
plain-JUnit-only test setup (no Robolectric/Mockk) cannot construct. The re-lock logic reuses
`OximetryMath.brightestChannelFraction` (already covered by `OximetryMathTest`'s 12 tests);
nothing new and independently testable was added. **Needs Phase 4**: whether 15% drift fires
too eagerly (normal head/lighting micro-motion) or too rarely.

---

## 2. Phase 3 — Oximeter viewing box

New files: `oximetry/OximeterGuideBox.kt` (single source of truth for the guide rect's
VIEW-space geometry, x:[0.20,0.80] y:[0.68,0.90] — lower-third, away from the forehead+cheeks
ROI), `oximetry/OximeterInset.kt` (crops + rotates the region upright from an RGBA_8888
frame, never mirrored — analysis frames were never mirrored to begin with, only
`PreviewView`'s own rendering is).

1. **Guide rectangle**: `OverlayView` draws a dashed light-blue rect + "Keep oximeter screen
   here" label whenever `showOximeterGuide` is true (tied to the developer calibration panel's
   visibility — a debug/calibration aid, not part of the normal HR/SpO2 UI).
2. **View→analysis-frame mapping**: `CoordinateMapper.viewRectToRotatedRect` (new) is the
   hand-derived mathematical inverse of the already-relied-upon `rotatedRectToViewRect`
   (undoes both the FIT_CENTER scale/letterbox and the front-camera mirror flip), then the
   existing `rotatedRectToSensorRect` takes it the rest of the way to sensor space — the
   same two-step path every other ROI in this app already takes. Verified by a 500-iteration
   round-trip property test (`CoordinateMapperTest`, same discipline as the existing
   sensor↔rotated round-trip test), since no physical device was available to verify it any
   other way.
3. **Live inset**: `MainActivity.offerOximeterGuideCrop` (called once per analysis frame,
   gated on the calibration panel being open) crops + rotates the region, throttled to
   ~3/s for the on-screen `ImageView` (top-right corner, 168x112dp, `fitCenter` — a ~2x
   magnification of the guide box's own screen fraction).
4. **Once/sec JPEG save**: `CalibrationRecorder.saveOximeterCrop()` (new method, same class
   Segment 34 already built — not a second recording mechanism) writes
   `spandan_cal_<stamp>_oximeter_crops/frame_<sensorTimestampNs>.jpg`, throttled to 1 Hz
   internally, a no-op unless `isRecording`. The sensor timestamp is the same clock the CSV's
   own `timestamp_ns` column uses, so a crop and its contemporaneous CSV row join offline.
5. **Full-preview un-mirror fallback**: a new "Un-mirror preview" switch in the developer
   panel sets `previewView.scaleX = -1f` when checked (off by default) — does not touch
   analysis frames (never mirrored) or anything `OximeterInset` reads, purely cosmetic for
   whoever is holding the phone.

**On-device UI-automation note** (relevant to `spandan-android-device-testing` memory's own
"fixed tap coordinates" discipline): the new "Un-mirror preview" switch row shifts every
developer-panel button below it down by one row versus pre-Segment-35 builds — any fixed
tap-coordinate script needs re-measuring.

**Not run this session**: the whole viewing box is untested beyond compiling — no physical
device, no oximeter, no face. Phase 4 is the first real chance to confirm the guide box
lands somewhere actually reachable while also holding the phone steady on the face, and that
the crop is legible enough to read digits from (the design assumes it is, per the plan's own
"a finger oximeter updates ~once/s" pacing note, but this is untested).

---

## 3. Files touched

**New**: `signal/SignalBufferTest.kt` (test), `oximetry/OximeterGuideBox.kt`,
`oximetry/OximeterInset.kt`.

**Changed**: `signal/RgbSample.kt` (`sensorTimestampNs`), `signal/SignalBuffer.kt` (gap
detection), `signal/ResampleUniform.kt` (`resampleRgbSamples`), `signal/
RealHeartRateEstimator.kt`, `signal/LiveSpo2Estimator.kt` (both call the new resample),
`signal/ResampleUniformTest.kt` (+4 tests), `camera/FaceAnalyzer.kt` (`minFaceSize`,
multi-region ROI, `roiBoxesRotated`), `camera/RoiPixelAverager.kt` (RGBA rewrite),
`camera/CoordinateMapper.kt` (`viewRectToRotatedRect`), `camera/CoordinateMapperTest.kt`
(+2 tests), `ui/OverlayView.kt` (multi-rect ROI drawing, guide box), `oximetry/
OximetryCaptureController.kt` (re-lock), `oximetry/CalibrationRecorder.kt`
(`saveOximeterCrop`), `MainActivity.kt` (RGBA/1280x720 analysis builder, gap→reacquire UI,
oximeter crop wiring, un-mirror switch), `res/layout/activity_main.xml`,
`res/values/strings.xml`.

**Test count**: 96/96 pass (83 pre-existing + 13 new: 7 `SignalBufferTest`, 4
`ResampleUniformTest`, 2 `CoordinateMapperTest`). `assembleDebug` succeeds.

## 4. What Phase 4 must actually check (consolidated)

1. `minFaceSize` 0.18 vs 0.35 — missed-face % and fps, handheld, arm's length.
2. The 0.5s gap-clear threshold — too eager (normal skip cycles) vs too lax (a real loss
   still splicing through).
3. Whether the uniform-30Hz resample changes displayed HR/SpO2, and in which direction.
4. RGBA_8888@1280x720/stride-1/3-region throughput cost, and whether HR/SpO2 accuracy moves
   (better or worse) versus the pre-Segment-35 forehead-only 640x480 YUV build.
5. The 15% brightness re-lock threshold's false-positive/false-negative rate.
6. Whether the oximeter guide box's position/size is actually usable one-handed, and whether
   the inset crop is legible enough to read digits from.

This is exactly Segment 35 Phase 4's own test matrix — see `docs/Segment35_Summary.md` (once
written) for the consolidated protocol and the question of full vs. reduced matrix.
