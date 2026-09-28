# Segment 35 Phase 2/3 — Android fix pack + oximeter viewing box

Date: 2026-09-28/29 · Status: **partially on-device verified (Galaxy A35, RFCXC0FFFSN,
same-day follow-up session).** Written same-day as the code (2026-09-28) when no physical
device was available; a device became available later the same day/into 2026-09-29 and a
real on-device smoke test immediately caught and fixed **one real crash and two real bugs**
(§4) — exactly the kind of thing this doc's original text warned could exist. All 96 unit
tests pass (`./gradlew testDebugUnitTest`, 83 pre-existing + 13 new); `assembleDebug`
succeeds. No MATLAB/iOS file touched.

**Read first**: `docs/Segment35_Accuracy_Research_and_Plan.md` §2 (the H1-H6 diagnosis this
phase fixes) and §4 (the oximeter box's original design). `android/docs/Segment29_
MinFaceSize_And_Kalman.md` (minFaceSize/Kalman context) and `Segment34_SpO2_Oximetry_
Capture.md` (the capture controller/recorder this phase extends).

---

## 0. Bottom line

**§4 below is the important update**: an on-device smoke test found a real crash (RGBA_8888
output breaks ML Kit's face detector) and two real bugs (a re-lock oscillation cascade, and
a per-frame throughput collapse while the oximeter guide box is visible), all now fixed and
re-verified live on the device. Everything below this table is otherwise as originally
written (2026-09-28, before that test).

| Item | What changed | On-device status |
|---|---|---|
| 1. minFaceSize | 0.35 → 0.18 | Not dropping face in a ~10min stationary desk session (§4.4); still **NOT** a real handheld/motion measurement — Phase 4 |
| 2. Lost-face gap handling | SignalBuffer now clears + "re-acquiring" UI on a real >=0.5s gap (real timestamps), instead of silently splicing | Unit-tested; **real gap-loss/reacquire scenario not exercised on-device** (my face never left frame during the smoke test) — Phase 4 |
| 3. Uniform resample | Branch 1 (HR + SpO2) now resamples to a uniform 30Hz grid via real per-sample sensor timestamps before filtering, same as Branch 2 already did | **CONFIRMED ACTIVE on real device** — every log line shows `resampled=true`, real fs 30.00Hz from ~15-30 raw fps depending on mode (§4.4) |
| 4. Widened ROI (RGBA_8888 REVERTED) | 1280x720, stride 1 (was 2), forehead+both cheeks pooled (was forehead only) — the RGBA_8888 output format itself was reverted to YUV_420_888 after a real crash (§4.1) | **Multi-region ROI confirmed rendering correctly live** (§4.4 screenshot: 3 yellow boxes, forehead + both cheeks); resolution/stride change not separately fps-profiled |
| 5. Oximetry auto re-lock | Re-locks on >15% ROI brightness drift or face reacquired after loss; clears signal buffer on every re-lock | **Real bug found + fixed on-device** (§4.2): an immediate re-lock cascade (1-2.6s apart) — added a 3s post-lock settle window. Confirmed real Camera2 PREROLL→METERING→LOCKED sequence completes and holds; **RELOCK_SETTLE_MS=3000/RELOCK_DRIFT_FRACTION=0.15 still not properly tuned** (§4.2's honest caveat) |
| Oximeter viewing box (Phase 3) | Guide box + labeled overlay, view→sensor crop mapping, live upright un-mirrored inset, once/sec JPEG saved into the existing calibration recorder, full-preview un-mirror fallback toggle | **Real throughput bug found + fixed** (§4.3): the crop was computed on every frame, not just its outputs throttled — fps collapsed 29.5→8.5/s while the panel was open; fixed by throttling the extraction itself. Guide box/inset render (partially covered by the dev panel's own layout, §4.5); **not tried with an actual oximeter** |

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

### Item 4 — 1280x720, stride 1, forehead+cheeks (`RoiPixelAverager.kt`, `FaceAnalyzer.kt`, `MainActivity.kt`)

> **CORRECTION (§4.1, real on-device test, same day)**: this item originally also switched
> `ImageAnalysis` to `OUTPUT_IMAGE_FORMAT_RGBA_8888`. That crashed ML Kit's face detector on
> a real device (`InputImage.fromMediaImage()` only accepts JPEG/YUV_420_888) and was
> reverted back to YUV_420_888 before this doc's own on-device section was written. The text
> immediately below is left as originally written (describing the RGBA attempt) for an
> honest record of what was tried; §4.1 has the full story and the actual shipped state.

- `ImageAnalysis` now requests `OUTPUT_IMAGE_FORMAT_RGBA_8888` at a `ResolutionSelector`
  target of 1280x720 (`FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`), replacing the previous
  YUV_420_888 @ 640x480 default. **[REVERTED, see correction above — stayed on YUV_420_888,
  1280x720 target kept.]**
- `RoiPixelAverager` rewritten for RGBA's single interleaved plane (no more BT.601 YUV→RGB
  math) — `averageRgb` now just delegates to `averageRgbMultiRect` with a one-element list,
  removing the duplicate averaging loop the two functions previously had. **[REVERTED —
  `averageRgbMultiRect` still delegates the same way, but reads Y/U/V planes with BT.601
  conversion again, per the correction above.]**
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
multi-region ROI, `roiBoxesRotated`), `camera/RoiPixelAverager.kt` (multi-region pooling +
stride 1, still YUV_420_888 — see §4.1 for the RGBA_8888 attempt-and-revert),
`camera/CoordinateMapper.kt` (`viewRectToRotatedRect`), `camera/CoordinateMapperTest.kt`
(+2 tests), `ui/OverlayView.kt` (multi-rect ROI drawing, guide box), `oximetry/
OximetryCaptureController.kt` (re-lock + §4.2's settle-window fix), `oximetry/
CalibrationRecorder.kt` (`saveOximeterCrop`), `oximetry/OximeterInset.kt` (YUV_420_888, see
§4.1), `MainActivity.kt` (1280x720 analysis builder, gap→reacquire UI, oximeter crop wiring
+ §4.3's extraction-level throttle fix, un-mirror switch), `res/layout/activity_main.xml`,
`res/values/strings.xml`.

**Test count**: 96/96 pass (83 pre-existing + 13 new: 7 `SignalBufferTest`, 4
`ResampleUniformTest`, 2 `CoordinateMapperTest`). `assembleDebug` succeeds. All confirmed
again after the §4 on-device fixes, not just before them.

## 4. On-device smoke test (2026-09-28/29, Galaxy A35, RFCXC0FFFSN) — real bugs found and fixed

A device became available the same day this doc's code was written. Rather than wait for a
full Phase 4 session, a quick install-and-watch-logcat smoke test was run immediately — and
immediately justified itself: the very first launch crashed. What follows is every real
finding, in the order they were hit, each with the fix applied and re-verified live.

### 4.1 Real crash: RGBA_8888 breaks ML Kit's face detector

First launch (`adb install` + `am start`) crashed on the very first analysis frame:

```
FATAL EXCEPTION: pool-4-thread-1
java.lang.IllegalArgumentException: Only JPEG and YUV_420_888 are supported now
	at com.google.mlkit.vision.common.InputImage.fromMediaImage(...)
	at com.spandan.app.camera.FaceAnalyzer.analyze(FaceAnalyzer.kt:216)
```

Phase 2 item 4's `OUTPUT_IMAGE_FORMAT_RGBA_8888` (chosen to remove `RoiPixelAverager`'s own
YUV→RGB conversion) is incompatible with `InputImage.fromMediaImage()`, which `FaceAnalyzer.
analyze()` calls on every frame for ML Kit face detection — an on-device-only failure this
project's plain-JUnit test setup cannot catch (`ImageProxy`/`InputImage` aren't constructible
there; confirmed no such test exists anywhere in this project even for the pre-existing
camera pipeline). **Fixed**: reverted the analysis stream to YUV_420_888;
`RoiPixelAverager.kt` and `OximeterInset.kt` both rewritten back to read Y/U/V planes with
the standard BT.601 conversion (stride 1 and forehead+cheeks multi-region pooling, both
independent of pixel format, are kept). The 1280x720 target resolution is unaffected and
kept. Re-verified: app launches, runs, zero crashes for the remainder of the session
(confirmed via `adb logcat -d | grep -c "FATAL EXCEPTION"` → 0, and a stable `pidof` across
every subsequent test).

### 4.2 Real bug: re-lock oscillation cascade

With the crash fixed, toggling "Locked linear" in the developer panel completed a real
Camera2 PREROLL→METERING→LOCKED sequence cleanly (~5s, ROI settling at 47% of full scale,
within the 40-60% target). But watching `SPANDAN_OXI` logs over the next ~10s:

```
05:50:30.188  RELOCK triggered: ROI brightness drifted 47% -> 40%   (1.1s after lock)
05:50:31.318  STATE LOCKED (58%)
05:50:31.591  RELOCK triggered: ROI brightness drifted 58% -> 49%   (0.27s after lock)
05:50:32.804  STATE LOCKED (46%)
05:50:33.102  RELOCK triggered: ROI brightness drifted 46% -> 54%   (0.30s after lock)
05:50:34.278  STATE LOCKED (53%)
05:50:35.683  RELOCK triggered: ROI brightness drifted 53% -> 62%   (1.4s after lock)
```

A genuine cascade — re-locking every 0.3-1.4s, which would make `useOximetryCapture`
completely unusable in practice (the signal buffer clears on every re-lock, per design, so it
would never accumulate the ~4s minimum `RealHeartRateEstimator`/`LiveSpo2Estimator` need).
Root cause: the ROI reading immediately after a fresh lock is itself a transient (AE/AWB
still visually settling into the new exposure/gains), not yet a stable baseline — comparing
the very next sample against it as if it were stable is what triggers the immediate
re-drift. **Fixed**: added a 3-second post-lock settle window (`RELOCK_SETTLE_MS`,
`OximetryCaptureController.lockEnteredAtElapsedMs`) — no drift check runs until that window
passes. Re-verified on the same device: re-locking still happened 3 times over the next ~19s
(at 3.0s, 3.0s, and 6.8s after each prior lock — plausibly real instability while the AE
system found a stable point, not an artifact of the fix), then held LOCKED with zero further
re-locks for the rest of the observation window (19s+). **Honest caveat, not smoothed
over**: the cooldown fixes the sub-second cascade, but the fact that re-locks still landed
right at the 3.0s boundary twice in a row is suspicious — either the scene was genuinely
unstable during this specific ad-hoc test (plausible: talking, adjusting the phone, and
testing other features throughout), or 3s still isn't quite long enough for the ROI reading
to fully settle. `RELOCK_SETTLE_MS=3000`/`RELOCK_DRIFT_FRACTION=0.15` are NOT validated
defaults — Phase 4 needs a controlled (phone on a stand, steady lighting, person holding
still) measurement of the real re-lock rate before either constant is trusted.

### 4.3 Real bug: per-frame oximeter-crop cost collapses throughput

With the developer panel open (needed to see the guide box / trigger the lock), raw HR
sample throughput measured via `RealHeartRateEstimator`'s own log (`raw=N` over a 25s
window) was **~8.5 samples/sec** — versus ~29.5/s measured moments later with the panel
closed. Root cause: `MainActivity.offerOximeterGuideCrop` computed the full per-pixel
YUV→RGB crop+conversion on **every single analysis frame** whenever the panel was visible;
only its two consumers (the live inset's UI update, the once/sec JPEG save) were throttled
downstream — the expensive work itself ran unthrottled. **Fixed**: the throttle now gates
the extraction itself (checked first, before any geometry/crop work), not just what happens
with its result. Re-verified: with the panel open and locked capture active, raw throughput
recovered to **~22-24 samples/sec** — most of the way back to the panel-closed rate. This
also means: **every prior "not on-device verified" throughput claim about Phase 3 in this
doc's original text was, if anything, understating the problem** — the box wasn't just
untested, it had a real, severe cost that is now fixed.

### 4.4 Confirmed working correctly, live on the device

- **Multi-region ROI (Phase 2 item 4)**: a real screenshot shows all three yellow boxes
  (forehead + both cheeks) correctly placed on a real face, inside the green face box.
- **Uniform 30Hz resample (Phase 2 item 3)**: every `RealHeartRateEstimator`/
  `LiveSpo2Estimator` log line shows `resampled=true`; raw sample counts (213-750 depending
  on mode/panel state) confirm real, non-uniform camera timestamps are being fed through
  `ResampleUniform.resampleRgbSamples` for real, not falling back to the naive path.
- **`minFaceSize=0.18`**: no missed-face event observed across the whole session (multiple
  minutes, stationary desk framing, one face continuously in frame) — a weak positive signal
  only; this is not the handheld/arm's-length motion test Phase 4 still needs.
- **Developer panel + guide box + un-mirror switch**: panel opens/closes via long-press, the
  new "Un-mirror preview" switch is present and wired (not separately confirmed to flip the
  preview visually this session), the guide box's dashed outline and "Keep oximeter screen
  here" label render (partially obscured by the panel itself, see §4.5).
- **Oximetry lock (Segment 34 logic, unaffected by this session's changes)**: real
  PREROLL→METERING→LOCKED sequence, linear tone curve applied, AE/AWB genuinely lock.

### 4.5 New minor finding, not fixed this session: dev panel overlaps the guide box/inset

The calibration panel (`calibrationPanel`, opened for exactly the controls a real Phase 4
session needs) visually overlaps the same top region `OximeterGuideBox`'s guide rectangle
and `oximeterInsetImage`'s inset occupy — so while positioning an oximeter (which needs the
panel open, to see status / hit Start rec), the guide box is only partially visible and the
live inset is fully hidden behind the panel. Not fixed here (a design decision — move the
inset, shrink/reflow the panel, or accept glancing at the guide before opening the panel —
needs Abrar's input on what's actually usable in practice, not a guess). Flagged for Phase 4.

### 4.6 What is still NOT on-device verified after this smoke test

- A real lost-face gap / reacquire scenario (item 2) — the face never left frame.
- A real handheld/motion `minFaceSize` test — this was a stationary desk framing throughout.
- The oximeter guide box/inset with an actual oximeter in view — not tried.
- `RELOCK_SETTLE_MS`/`RELOCK_DRIFT_FRACTION` under controlled (not ad-hoc) conditions.
- Any accuracy comparison (HR/SpO2 vs a real oximeter) — this was a functional/crash/
  throughput smoke test only, not a Phase 4 accuracy session.

---

## 5. What Phase 4 must actually check (consolidated, updated after §4)

1. `minFaceSize` 0.18 vs 0.35 — missed-face % and fps, handheld, arm's length (§4.4's
   stationary desk test is not this).
2. The 0.5s gap-clear threshold, and the full lost-face → re-acquire UI path — not exercised
   at all yet (§4.6).
3. Whether the uniform-30Hz resample changes displayed HR/SpO2 accuracy, and in which
   direction (§4.4 confirms it's active; not confirmed to help or hurt accuracy).
4. Widened-ROI (forehead+cheeks, stride 1, 1280x720) throughput cost and accuracy, versus the
   pre-Segment-35 forehead-only 640x480 build. Format is YUV_420_888 again (§4.1), not RGBA.
5. `RELOCK_SETTLE_MS`/`RELOCK_DRIFT_FRACTION`'s real false-positive/negative rate under
   controlled conditions (§4.2's honest caveat — the ad-hoc test wasn't controlled).
6. Whether the oximeter guide box's position/size is actually usable one-handed with a real
   oximeter, whether the inset crop is legible enough to read digits from, and what to do
   about §4.5's panel/guide-box overlap.

This is exactly Segment 35 Phase 4's own test matrix — see `docs/Segment35_Phase4_
OnDevice_Test_Protocol.md` for the consolidated protocol (currently on hold per Abrar).
