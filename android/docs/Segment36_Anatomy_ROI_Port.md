# Segment 36 — MediaPipe FaceLandmarker anatomy-ROI port + on/off toggle

Date: 2026-09-29 · Status: **code complete, 111/111 unit tests pass, `assembleDebug`
succeeds. NOT on-device verified this session** (no physical Android device attached to
this environment, no `adb` found either — confirmed by search, not assumed).

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

## 5. Honest status and what a real device session must check

**Not done this session** (no device, no `adb`):
- Whether `AnatomyRoiFaceAnalyzer` actually runs without crashing on first launch
  (MediaPipe LIVE_STREAM setup, model asset loading, the Bitmap-rotate step).
- Real fps cost of FaceLandmarker + the Bitmap conversion + the rotate step combined —
  inherited estimate only (Segment 30's ~87ms bundled FaceDetector cost is a related but
  not identical measurement; FaceLandmarker does more work per call, not less).
- Whether the pre-rotate-then-detect coordinate convention actually lands landmarks in
  the right place on a real face (the unit tests verify the BOX MATH given a landmark
  map, not that MediaPipe's own returned landmarks + the rotation step compose
  correctly).
- Whether toggling `anatomyRoiSwitch` mid-session cleanly rebinds the camera (no
  double-bind crash, no leaked native resources beyond what `activeAnatomyAnalyzer?.close()`
  already targets).
- Real HR/SpO2/Branch-2 accuracy comparison between the two ROI modes on live video —
  the entire reason this toggle exists, and it needs a real capture session to answer.

**Do next, in order**: connect a device, launch the app, flip the toggle with a face in
frame, confirm no crash and a plausible HR reading, then a structured A/B capture
(same person, same lighting, toggle on vs. off) mirroring Segment 35 Phase 4's own
protocol.
