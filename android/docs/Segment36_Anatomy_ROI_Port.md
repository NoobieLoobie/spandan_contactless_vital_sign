# Segment 36 — MediaPipe FaceLandmarker anatomy-ROI port + on/off toggle

Date: 2026-09-29–30 · Status: **code complete, unit tests pass, `assembleDebug`
succeeds, ON-DEVICE VERIFIED** (Galaxy A35, side-by-side install as
`com.spandan.app.anatomy`). Two real bugs found and fixed via on-device testing (§6);
one reliability characteristic identified and mitigated but not fully root-caused (§6.3).

---

## 0. Why this exists

`matlab/src/roi/faceMeshAnatomyROIExtraction.m` (Segment 35 Phase 1) — a MediaPipe
FaceMesh landmark-driven forehead + both-malar (cheekbone) ROI — was promoted to the
MATLAB pipeline's own default despite a non-significant motion-pool test (Segment 35),
and then found to regress POS accuracy on an independent dataset (Segment 36's own MATLAB
half, `matlab/docs/Segment36_Own_Dataset_Evaluation.md`). Rather than a fixed on/off
default, Abrar asked for a real user-facing toggle in the app so the two ROI strategies
can be compared live, on real hardware, rather than only in MATLAB batch numbers.

## 1. Read first (per instruction: check prior Android work before building)

- **`android_segment30_mediapipe/docs/Segment30_MediaPipe_Migration.md`** — the only
  prior MediaPipe-on-Android work in this project. Two findings this port had to
  address directly:
  1. **MediaPipe Tasks Vision's Android packet creator rejects a raw YUV_420_888
     `android.media.Image`** (`UnsupportedOperationException: Android media image must
     use RGBA_8888 config`) — confirmed on a real device by Segment 30, not re-tested
     here. `camera/MediaPipeImageConverter.kt` (copied verbatim from the Segment 30
     experiment into production `android/`) does the YUV→ARGB8888 Bitmap conversion
     this requires. **Real, disclosed cost**: Segment 30 measured ~71ms of a ~87ms
     bundled detection cost as this conversion alone, on a Galaxy A35. This toggle is
     opt-in specifically because that cost is real and not eliminated here either.
  2. **Segment 30 deliberately avoided FaceLandmarker (landmarks), staying on
     FaceDetector (a single bounding box)**, citing
     `matlab/docs/Segment7_Task_D_Landmark_ROI.md`'s negative finding that a
     **KLT-tracked** landmark ROI regressed 2/5 UBFC subjects vs. a plain box. **This
     port uses FaceLandmarker anyway, deliberately** — Task D's negative result is about
     optical-flow TRACKING of one frame's landmarks across many subsequent frames, a
     different technique from what this file does (a real per-detection-frame
     FaceLandmarker call, frozen only between the same every-3rd-frame skip window every
     other analyzer in this app already uses). This port is the direct Android analogue
     of `faceMeshAnatomyROIExtraction.m` specifically, which already showed a real,
     Holm-significant HR effect on MATLAB (Segment 35) — a materially different, more
     applicable precedent than the KLT-tracking result Segment 30 was avoiding. Stated
     explicitly so a future reader doesn't wonder why this port "un-does" Segment 30's
     own decision — it doesn't; it addresses a different premise.
- **`android/docs/Segment35_Android_Fix_Pack_And_Oximeter_Box.md`** — confirmed the
  existing multi-region (forehead+cheeks) face-BOX-fraction ROI and its
  `RoiPixelAverager.averageRgbMultiRect` pooling convention, which this port's landmark
  boxes are conceptually parallel to (concatenate-then-average across 3 regions) but
  does not reuse directly — landmark boxes come from an already-converted upright
  Bitmap, not raw YUV sensor planes, so a separate pixel-averaging path
  (`AnatomyRoiPixelAverager`) was written instead of extending
  `RoiPixelAverager.averageRgbMultiRect`.

## 2. What was built

| File | Purpose |
|---|---|
| `camera/AnatomyRoiCalculator.kt` | Pure port of `faceMeshAnatomyROIExtraction.m`'s `landmarksToBoxes`/`centeredFallbackBoxes`/`clampBox`. Same 11 MediaPipe landmark indices (10, 105, 334, 111, 216, 137, 129, 340, 436, 366, 358) as the MATLAB source. |
| `camera/AnatomyRoiPixelAverager.kt` | Pure port of `poolSkinPixels`/`ycbcrSkinMask` — ITU-R BT.601 `rgb2ycbcr` (Cb∈[77,127], Cr∈[133,173]), same "skip the filter for ALL boxes if ANY box is under the 10% skin-pixel floor" fallback as the MATLAB source. Operates on a plain `IntArray` of ARGB pixels, not a `Bitmap` object, so it stays unit-testable. |
| `camera/AnatomyRoiFaceAnalyzer.kt` | `ImageAnalysis.Analyzer` wiring MediaPipe `FaceLandmarker` (LIVE_STREAM) into the camera pipeline. Emits the SAME `FaceAnalysisResult` sealed type `FaceAnalyzer.kt` does, so `MainActivity`'s existing result-handling code needed zero changes. |
| `camera/MediaPipeImageConverter.kt` | Copied verbatim from `android_segment30_mediapipe/` (not previously in production `android/`). |
| `app/src/main/assets/face_landmarker.task` | MediaPipe's official FaceLandmarker model bundle (float16, 3,758,596 bytes), downloaded from `storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task` and verified a real ZIP/task bundle (PK magic bytes, content-length matches the server's own report) before being committed — same discipline as Segment 30's own `.tflite` asset check. |
| `build.gradle.kts` | Added `com.google.mediapipe:tasks-vision:1.0.0` (same artifact/version Segment 30 already verified against the real Maven metadata). |
| `MainActivity.kt` | New `useAnatomyRoi` state (default `false`), `anatomyRoiSwitch` (top-end of the camera preview, always visible — NOT hidden in the developer calibration panel, since this is a user-facing comparison control, not a debug-only flag), `bindUseCases()` branches between `FaceAnalyzer` and `AnatomyRoiFaceAnalyzer` and explicitly closes the previous `AnatomyRoiFaceAnalyzer`'s native MediaPipe resources on every rebind (toggling repeatedly must not leak one task graph per flip). |
| `activity_main.xml` / `strings.xml` / `bg_anatomy_roi_toggle.xml` | The toggle's own small translucent badge UI. |

## 3. A real design decision: pre-rotate the Bitmap, don't post-correct coordinates

Segment 30's own FaceDetector branch passes the RAW sensor-orientation Bitmap +
a rotation HINT (`ImageProcessingOptions.setRotationDegrees`), which MediaPipe documents
as affecting inference only, NOT the returned coordinate space — so Segment 30 had to
build `CoordinateMapper.mediaPipeSensorBoxToRotatedRect` to un-rotate the ONE box
`FaceDetector` returns. FaceLandmarker returns **9 independent landmark points**, not one
box — applying that same post-hoc fix nine times over is more surface area for a
coordinate-space bug. This port instead **physically rotates the converted Bitmap to
upright BEFORE calling FaceLandmarker** (`AnatomyRoiFaceAnalyzer.rotateBitmap`, a
`Matrix.postRotate` + `Bitmap.createBitmap`), so landmarks come back already relative to
the upright image — directly matching `faceMeshAnatomyROIExtraction.m`'s own convention
(MATLAB decodes an already-upright `VideoReader` frame). No landmark-specific coordinate
mapper was needed as a result — a real simplification, not just a style preference.

## 4. Two real bugs found and fixed while writing this (both would have been silent on a
   real device — device behavior is correct; only the LOCAL unit-test harness is affected)

Both trace back to this project's own long-documented gotcha
(`android/app/build.gradle.kts`'s `isReturnDefaultValues = true`): **the real
`android.graphics.Rect` class's methods are stubbed to return default values under this
project's plain-JUnit test harness, but its public FIELDS (`left`/`top`/`right`/`bottom`)
are real, unstubbed field access** — a distinction Segment 28/30 already found for
`Rect`'s 4-arg constructor (`CroppedDetectionStrategy.makeRect`), and this segment found
two more instances of:

1. **`Rect.equals()` is ALSO stubbed** (confirmed directly: two genuinely field-equal
   `Rect` objects both printed as `Rect<null>` in a failed `assertEquals` yet compared
   unequal). `AnatomyRoiCalculatorTest` originally used `assertEquals(rect1, rect2)`;
   fixed to compare `.left`/`.top`/`.right`/`.bottom` individually.
2. **`Rect.width()`/`Rect.height()` are ALSO stubbed to 0** (unlike the plain field
   access `.left` etc.) — this one was a REAL PRODUCTION BUG, not just a test-authoring
   mistake: `AnatomyRoiPixelAverager.averageRgb`'s own 10%-skin-pixel-floor check used
   `box.width() * box.height()`, which silently always computed `total=0` under the test
   harness, making the whole "fall back to raw average when any region is too sparse"
   branch untestable (it would still work correctly on a real device, where these
   methods are not stubbed — but this session's unit tests could not have caught a real
   regression in that logic without this fix). Fixed to
   `(box.right - box.left) * (box.bottom - box.top)`, field arithmetic only. Caught by a
   test that initially failed for the RIGHT reason (`expected 200 but was 101`) rather
   than silently passing — worth recording so a future session recognizes the same
   symptom immediately instead of re-deriving it.

## 5. Honest status before on-device testing (superseded by §6)

Section 4/5 above described the pre-device state. The open items listed there
(crash-on-launch risk, real fps cost, landmark coordinate correctness, toggle rebind
safety) were resolved by the on-device session below — **except** the full structured
A/B HR/SpO2 accuracy capture, which still needs a dedicated session (see §6.4).

## 6. On-device verification (2026-09-29, Galaxy A35, side-by-side `com.spandan.app.anatomy`)

App installed and launched cleanly alongside the existing `com.spandan.app` build with no
collision (separate `applicationId`, separate icon/name — "Spandan Anatomy", orange pulse
icon). `assembleDebug` + `testDebugUnitTest` both passed before every reinstall.

### 6.1 Two real bugs found and fixed

1. **Overlay showed only 1 of 3 anatomy boxes.** `AnatomyRoiFaceAnalyzer.toFaceDetected()`
   was written against an **older, cached copy** of `FaceAnalyzer.kt`'s
   `FaceAnalysisResult.FaceDetected` (from the `android_segment30_mediapipe/` research
   copy), which predates the real production data class gaining a
   `roiBoxesRotated: List<Rect>` field. Without it, `MainActivity`'s overlay code fell
   back to its single-box default (`listOf(roiBoxRotated)` = forehead only), even though
   `AnatomyRoiPixelAverager.averageRgb` was already correctly pooling pixels from all 3
   regions (forehead + both cheeks) — a pure visualization gap, not a signal bug. Fixed by
   setting `roiBoxesRotated = listOf(boxes.forehead, boxes.leftCheek, boxes.rightCheek)`
   explicitly. Verified via device screenshot showing all 3 boxes drawn.
2. **`FaceDetected.sensorTimestampNs` (the top-level field, distinct from
   `RgbSample.sensorTimestampNs`) always defaulted to `0L`** — never threaded through from
   `AnatomyRoiFaceAnalyzer`. This field feeds `MainActivity`'s oximetry per-frame
   metadata/calibration CSV, not `SignalBuffer` (which reads `RgbSample`'s own,
   correctly-set timestamp) — so this was NOT the cause of the freeze in §6.2, but a real,
   separate bug affecting oximetry capture bookkeeping. Fixed by threading a
   `sensorTimestampNs: Long` parameter through both `emitBoxesResultFromBitmap` and
   `emitBoxesResult` call sites into `toFaceDetected`.

### 6.2 A real, more serious bug: MediaPipe LIVE_STREAM silent freeze on camera hiccup

After roughly 26 seconds of healthy operation, `RealHeartRateEstimator` / `LiveSpo2Estimator`
/ `MorphologyWaveformEstimator` stopped logging entirely for 40+ seconds, while
camera/`SPANDAN_OXI` logs continued normally. A full logcat dump pinpointed a genuine
Camera2 session hiccup at that exact moment — `CameraManagerGlobal` briefly reported
`STATUS_NOT_AVAILABLE`, then CameraX transparently reopened the same camera and reused the
same `AnatomyRoiFaceAnalyzer` instance (ordinary CameraX self-recovery, not triggered by any
app code / rebind). This reset `imageProxy.imageInfo.timestamp` to a lower value on the
reopened session, violating MediaPipe Tasks Vision's **undocumented** LIVE_STREAM
requirement that timestamps passed to `detectAsync` be strictly increasing across the whole
`FaceLandmarker` instance's lifetime. FaceLandmarker responded by silently ceasing to invoke
`onLandmarkResult`/`onLandmarkError` for the rest of the app session — no crash, no error
callback — freezing the HR/SpO2 display at stale values. The classical `FaceAnalyzer.kt`
path never hits this because ML Kit's `InputImage.fromMediaImage` is a stateless per-call
`Task` with no cross-call timestamp contract; this is specific to MediaPipe's LIVE_STREAM
API.

**Fix applied**: `AnatomyRoiFaceAnalyzer` now tracks `lastSentTimestampMs` and clamps every
timestamp sent to `detectAsync` via `maxOf(rawTimestampMs, lastSentTimestampMs + 1)`,
regardless of what the camera reports. This fix's effectiveness against the *exact* original
trigger (a real Camera2 `STATUS_NOT_AVAILABLE` hiccup) was **not independently
re-confirmed** — that hiccup could not be forced on demand — but subsequent monitoring
windows did not reproduce the permanent-freeze symptom, only the milder cycling described
next.

### 6.3 More frequent "Warming up" cycling than classical mode (RESOLVED 2026-09-30, see §6.5)

Even after both fixes above, sustained monitoring windows showed the UI cycling through
"No face detected" / "Re-acquiring signal" more often than the classical `FaceAnalyzer`
path does, which kept `RealHeartRateEstimator` from holding a sustained "Live" (OK) status
for long. This is **not** the same bug as §6.2 — targeted diagnostic logging (since removed;
see below) confirmed the analyzer itself stayed healthy throughout these cycles: `faces=1`
detected continuously, `rgbSample` non-null with ~19,000 sampled pixels, unbroken
`detectAsync`→`onLandmarkResult` pairs. The pipeline always recovered on its own — this is a
reliability/sensitivity gap, not a crash or a permanent freeze. Leading hypothesis (not
confirmed): MediaPipe FaceLandmarker's face-presence signal may be more sensitive to head
angle/pose than ML Kit's detector, and/or the shared buffer's gap-detection logic is
trigger-happy for either ROI mode but only visible here because FaceLandmarker itself drops
"face present" more readily. **Not fixed this session** — documented as a known limitation
of the anatomy-ROI toggle relative to the classical default.

The diagnostic `Log.d(TAG, "DIAG ...")` lines added to `analyze()`, the `detectAsync` call
site, `onLandmarkResult()`, `emitBoxesResultFromBitmap()`, and `emitBoxesResult()` during
this investigation have been **removed** now that the two real bugs above are fixed and
this characteristic is documented — normal operation no longer logs per-frame diagnostics
from this analyzer (only the pre-existing `onLandmarkError` warning path remains).

### 6.5 Follow-up session (2026-09-30): root cause of §6.3 fixed, plus a native crash

(Placed before §6.4 so the "still needed" list stays last.)

**Root cause of the §6.3 cycling.** The analyzer's own design put ML inference on the rPPG
sampling path. The analysis stream is 1280x720 (MainActivity's `ResolutionSelector`), and on
**every** frame, including skipped-detection frames, the analyzer did the following just to
average about 19k ROI pixels:
- converted the whole frame to an ARGB Bitmap in Kotlin;
- made a second full-size rotated copy;
- ran a full `getPixels`.

On detection frames it also held the `ImageProxy` open until FaceLandmarker's callback
fired. Under `STRATEGY_KEEP_ONLY_LATEST` that means the camera delivered no new frame while
inference ran. Any slow inference or GC pause then pushed the sample gap past
`SignalBuffer.GAP_CLEAR_THRESHOLD_SECONDS` (0.5 s), which cleared the buffer and showed
"Re-acquiring signal". A LIVE_STREAM frame that MediaPipe's flow limiter drops without
calling back would also have stalled the camera permanently. That is a plausible second
route to the §6.2 freeze.

**Fix (running the ML model properly on the phone):**
1. **Inference is decoupled from sampling.** Every `ImageProxy` is closed synchronously in
   `analyze()`. The rPPG sample for every frame is pooled from the most recent landmark
   boxes, so the sample rate is the camera rate, whatever the inference latency.
2. **No full-frame Bitmap on the sampling path.** The new
   `AnatomyRoiPixelAverager.averageRgbFromYuv` reads YUV_420_888 over only the three ROI
   boxes, which are mapped to sensor space by `CoordinateMapper.rotatedRectToSensorRect`.
   It uses the same BT.601 conversion and truncation as before, and the same YCbCr skin
   mask with the same "any box < 10% skin -> unmasked for all boxes" rule. Both paths now
   share one single-pass accumulator. `clippedPixels` is now real instead of 0.
3. **The landmarker gets a small upright image.** The new
   `MediaPipeImageConverter.yuv420ToUprightArgb8888Bitmap` rotates and subsamples 2x
   (640x360) in one pass. Landmarks are normalized and get rescaled to full resolution, so
   ROI precision is unaffected. The rotation index math is covered by
   `MediaPipeImageConverterTest`.
4. **At most one `detectAsync` in flight,** plus a 1 s watchdog that frees the slot if a
   callback never arrives.
5. **Miss grace.** After a detection that finds no face, the last boxes stay in use for
   400 ms before NoFace is reported. The classical path's stale-box/Kalman reuse gives it
   the same tolerance.
6. **Lower confidence thresholds.** Detection is 0.4, and presence/tracking are 0.3
   (library defaults are 0.5). This is a single-face selfie use case, and the skin mask
   still rejects non-skin pixels.
7. **Throttled `STATS` log** (every 5 s): fps, samples/s, detections/s, inference latency,
   misses, max inter-sample gap, buffer-clearing gaps, watchdog resets. To view it:
   `adb logcat -s AnatomyRoiFaceAnalyzer`.

**Native crash found and fixed.** Flipping the Anatomy ROI switch a few times quickly
crashed the app with a SIGSEGV (null dereference inside `libmediapipe_tasks_jni.so`, from
`startDetection`). `MainActivity` calls `close()` on the main thread while the analysis
thread can be inside `detectAsync`. This race predates today's changes. Now `close()` and
`detectAsync` share a lock, and `detectAsync` is skipped once the analyzer is closed. After
the fix, 9 rapid flips caused no crash.

**On-device result (Galaxy A35, seated, indoor light, battery saver on).** Over a 90 s
window after the rebind stress test, 5 s STATS lines showed:
- 17.7-19.0 fps, with samples/s equal to fps;
- inference about 130-137 ms mean on CPU (max 175 ms), at about 6 detections/s;
- max inter-sample gap 80-120 ms and **0 buffer-clearing gaps**;
- 0 landmark misses and 0 watchdog resets.

The HR pill stayed "Live" at every 15 s UI poll, for the whole window.

**Not tested this session:**
- walking out of frame and back (face-loss/recovery);
- a forced Camera2 hiccup;
- the classical-vs-anatomy fps comparison under identical battery-saver conditions.

### 6.4 Still needed

A structured A/B HR/SpO2/Branch-2 accuracy capture (same person, same lighting, toggle
on vs. off), mirroring Segment 35 Phase 4's protocol — this toggle's actual reason for
existing — has not been run yet. The on-device work this session was bug-fixing/
reliability diagnosis, not an accuracy comparison.
