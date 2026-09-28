# Segment 35 — Accuracy research, diagnosis and test plan (pipeline + Android app)

Date: 2026-09-28 · Status: **PLAN — nothing implemented yet** · Author: Cowork session

Scope asked by Abrar: raise the accuracy of the *whole* pipeline (PPG extraction, processing,
jitter, HR, SpO2, both branches), deep learning now allowed for the pre-DSP stage (teacher's
permission); and fix the app, whose HR/SpO2 disagree with Abrar's finger oximeter and whose
face box flickers when he holds the phone himself (unplugged), even though it looked smooth
during Claude's USB-connected test sessions. Also: a live, readable view of the oximeter
screen inside the app for debugging.

Read before this: `matlab/docs/Literature_Review_Master.md` (everything already tried),
`matlab/docs/Segment33_SpO2_Research_and_New_Pipeline.md`,
`android/docs/Segment29_MinFaceSize_And_Kalman.md`, `android/docs/Segment34_SpO2_Oximetry_Capture.md`.

---

## ⚠ SCOPE CORRECTION (2026-09-28, same day, from Abrar)

The teacher permits ML/DL **only for the ROI extraction stage** (face detection, landmarks,
skin segmentation / which pixels to average). **Every DSP step — pulse extraction
(CHROM/POS), detrending, filtering, HR read-out, SpO2 — must stay classical, no ML/DL.**

Consequences for this plan:
- End-to-end DL rPPG models (FacePhys, ME-rPPG, PhysMamba, RhythmMamba, PhysFormer,
  PhysNet, EfficientPhys, TSCAN) replace the DSP stage → **OUT of scope as pipeline
  components.** Tests 3 and 7 are dropped. Test 1 is repurposed: at most an *external
  reference line* in the report ("how far is classical DSP from a DL state of the art"),
  clearly labelled not-our-pipeline, and only if Abrar wants it.
- **In scope and now the main DL work:** MediaPipe Face Landmarker (478 points) ROIs,
  multi-ROI selection by facial anatomy/blood supply (Kim et al. 2021, *Assessment of ROI
  Selection for Facial Video-Based rPPG*, Sensors 21:7923 — the ROI method MCD-rPPG uses),
  DL skin segmentation masks, and better face tracking for handheld use.
- The MCD-rPPG paper (Egorov et al., ACM MM 2025) supports this direction: POS gets 1.17 bpm
  HR MAE on UBFC and 3.80 on their 600-subject set (frontal view), while supervised models
  trained on another dataset often collapse cross-dataset (PhysFormer trained on UBFC →
  43.65 bpm on MCD-rPPG). Their Table 5: their model's SpO2 MAE equals the constant-value
  baseline (0.98 vs 0.98) — the same "SpO2 cannot beat the mean" finding as Segment 33.
- MCD-rPPG is the right big test set for our *classical* pipeline: 600 subjects, 3-minute
  videos at 640×480, 24/30 fps, one camera is a **Samsung A3s phone**, 100 Hz finger PPG,
  resting + after-squats (HR 49–153 bpm). Correct link: `huggingface.co/datasets/kyegorov/mcd_rppg`
  (the `wengziheng` link in the first version was a stale mirror → 404). The API returns
  401 = gated: log in and accept the terms on the dataset page first.

## 0. Bottom line (original, see correction above)

1. **The biggest lever we have never tried is deep learning for the pulse extraction step.**
   The Literature Master's §8 marked all DL "out of scope". That rule is now lifted. A
   pip-installable toolbox (**open-rppg**, MIT code) ships pretrained weights for 9 models,
   including **FacePhys** (Wang, Tang, … McDuff, arXiv:2512.06275, Dec 2025): 719K
   parameters, 0.15 GFLOPs, 9.46 ms latency, runs in real mobile browsers, and processes
   **one 36×36 face crop per frame with a recurrent state** — a natural fit for a live
   Android stream. Its reported cross-dataset HR MAE (trained on RLAP) is 0.43 bpm on
   UBFC, 0.24 on PURE, 5.30 on MMPD (hard, motion/lighting/dark skin). Our production POS
   is 7.38 bpm MAE on VIPL. Those are different test sets, so this does not prove FacePhys
   wins on *our* data. Test 1 below measures that directly.
2. **The app has real bugs that explain the "worked when Claude tested it, fails in my
   hand" observation.** None of them needs research. They are listed in §2 (H1–H6). The
   two most likely ones: `minFaceSize = 0.35` (the face must fill ≥35% of the frame width,
   and that fails at handheld arm's length), and the fact that when the face is lost, the
   missing seconds are silently spliced out of the HR window. That splice biases HR
   **low** and adds phase jumps.
3. **SpO2 cannot become accurate from public data** (Segment 33 proved VIPL's labels are
   effectively constant). It can only improve with **our own paired data from this phone
   plus Abrar's oximeter**. That is exactly what the oximeter viewing box will collect, so
   the debug feature is also the SpO2 calibration tool.

---

## 1. What is already settled (not re-tested)

| Area | Settled result | Source |
|---|---|---|
| Classical combiners | CHROM/POS beat a*, Cb/Cr, 2SR, LGI, PBV, OMIT, cPACE on our pools, native forms included | Segments 18, 21–23 |
| ROI shape (classical) | Box vs KLT vs face-mesh vs hybrid → identical CHROM/POS HR | Segment 7 H/I/J |
| Kalman/RAKF on HR | Not adopted (also native Eq. 12 form) | Segments 6, 9, 23 T7 |
| Windowed read-out (0.7–4 Hz, median over windows) | CANDIDATE, validated only in degraded conditions (dark / low fps) | Segments 24–26 |
| Wavelet denoise | Default for Branch 1; harmful for Branch 2 (pinned off) | Segments 8, 27 |
| SpO2 on VIPL | No estimator beats the train-mean baseline; per-person/camera R offsets are ~4–5× the physiological range | Segment 33 |
| Locked AE/AWB + linear tone curve (A35) | HR MAE vs oximeter 5.5–7.1 (locked) vs 12.0–13.8 bpm (auto); jitter roughly halved; n=1, confounded with fps, no re-lock | Segment 34 |

A phone held in a hand is a "degraded signal" condition. That is where the windowed read-out
*did* validate, so it becomes relevant again for the app (Test 5).

---

## 2. App diagnosis — why it fails in Abrar's hand (from reading the current code)

| # | Finding (code-verified) | Why it hurts | Fix |
|---|---|---|---|
| **H1** | `FaceAnalyzer.minFaceSize = 0.35f` (Segment 29). ML Kit's meaning: the smallest face to detect, as the head's width relative to the image's width. The analysis stream is the CameraX default of 640×480, so the face must be ≥168 px wide. Segment 29 tested only "normal" and "increased" distance, on a fixed setup. | Handheld at arm's length, the face is often narrower than that. The detector returns *no face*, `lastFaceBoxRotated` is cleared, the Kalman tracker is reset, and the box disappears. That matches "at times there is the box, at times there is not". | Drop to 0.15–0.20. Detection cost can be recovered by H5's RGBA path or by detect-every-N. Measure missed-face % handheld. |
| **H2** | On `NoFace`, `MainActivity` only clears the overlay. Nothing is added to `SignalBuffer`, and the window keeps the samples on both sides of the gap. `fs` is then computed as `(N−1)/window_seconds`. | Seconds of signal are cut out and the two ends are glued together. There are fewer pulse cycles over the same wall-clock span, so HR is **biased low**, and the glue point is a phase jump that adds broadband noise. | Use real timestamps plus a uniform resampling grid (H3). Linearly bridge gaps under 0.5 s; for longer gaps, clear the window and show "re-acquiring". |
| **H3** | `RgbSample.timestampMs = System.currentTimeMillis()` is taken *when the ROI is averaged*. On every 3rd frame (the real detection) that happens inside ML Kit's async callback, 19–90 ms late. `KEEP_ONLY_LATEST` also drops frames irregularly. Branch 1's FFT assumes uniform sampling and never looks at timestamps (Branch 2 does resample). | Periodic timing jitter of up to about one frame on every 3rd sample, plus uneven drops, adds noise at the frame-rate/3 pattern. It gets worse when the CPU is slower (unplugged, thermal). | Use `imageInfo.timestamp` (sensor clock; it already exists as `sensorTimestampNs`), then resample R/G/B to a uniform 30 Hz grid before any DSP (reuse `ResampleUniform.kt`). |
| **H4** | Default capture is full auto (AE/AWB free-running). | Handheld means the background, and so the metering, keeps changing. Every AE/AWB step lands in all channels at once. Segment 34's locked mode already halved the HR error. | Promote locked capture with **re-lock logic**: lock after face metering; re-lock when face brightness drifts >15% or after a lost face is reacquired; clear the window on every re-lock. |
| **H5** | Forehead ROI = 40%×20% of the face box, averaged with `SAMPLE_STRIDE = 2` at 640×480. For a 170 px face that is about 68×34 px → **~580 sampled pixels**. | Few pixels means large quantization/sensor noise per sample, and small hand shakes move a big fraction of the ROI off skin. | Analysis at 1280×720 with RGBA output (`setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)`, which also removes Segment 30's 71 ms conversion tax). Stride 1. Forehead + both cheeks. |
| **H6** | Plugged vs unplugged was never compared. | Charging can change the CPU governor and thermals, which changes fps and drop pattern (H3). A tripod/stand vs hand changes distance (H1) and motion (H5). Claude's tests were all USB + fixed placement. | Log fps, missed faces and drops to the calibration CSV so an unplugged session can be analysed afterwards. Run the A/B in Test 6. |

HR read-out weaknesses, also in the code: FFT argmax over a 25 s window gives 2.4 bpm bins,
with no zero-padding or peak interpolation, no harmonic check, and no signal-quality gate on
what is displayed.

---

## 3. New candidates found in this session (ranked)

### A. Deep-learning pulse extraction (both MATLAB-side evaluation and app)

Source: `github.com/KegangWangCCNU/open-rppg` (cloned and inspected locally). `pip install
open-rppg`, Python 3.9–3.13, JAX backend, CPU works. Code is MIT. The **pretrained weights
remain their authors' IP under each paper's terms**. That is fine for an academic course
project; cite each model.

Weights present in the repo (`rppg/weights/`), with their input shapes from `models.py`:

| Model | Input | Notes |
|---|---|---|
| **FacePhys.rlap** | 1×36×36 per frame, streaming state, 30 fps | Default model of the toolbox; best numbers in its paper; best fit for Android |
| ME-chunk / ME-flow (.rlap/.pure) | 1×36×36 streaming | Same group, arXiv:2504.01774 (memory-efficient low-latency rPPG) |
| RhythmMamba (.rlap/.pure) | 160×128×128 | AAAI 2025 |
| PhysMamba (.rlap/.pure) | 160×128×128 | CCBR 2024 |
| PhysFormer, PhysNet | 160/128-frame clips | Heavier |
| EfficientPhys, TSCAN | 160×72×72 / 160×36×36 | TSCAN was designed for on-device |

FacePhys paper numbers (arXiv:2512.06275, Tables 1, 4, 5 — MAE bpm):

| Train → Test | MMPD | VitalVideo | PURE | UBFC |
|---|---|---|---|---|
| RLAP → | 5.30 | 0.77 | 0.24 | 0.43 |
| PURE → | 8.45 | 0.90 | — | 0.48 |
| RhythmMamba, RLAP → | 9.62 | 2.85 | 1.63 | 0.44 |
| PhysNet, RLAP → | 11.2 | 0.89 | 0.63 | 0.66 |

Caveats: these are the authors' own numbers; VIPL-HR is not in their table; RLAP, PURE and
UBFC subjects are mostly East Asian/European. Our own Bangladeshi dataset is the real
generalisation test.

Sub-ideas to test on top of a DL pulse:
- **A1** DL pulse → our existing HR read-out and windowed read-out (is the read-out still the bottleneck?)
- **A2** DL pulse → Branch 2 (notch/morphology). DL models are trained to reproduce contact
  PPG, so they may keep the dicrotic notch *better* than CHROM+comb filtering, or they may
  smooth it away. Measure with the existing notch-confidence metric on the 5 UBFC GT subjects.
- **A3** Per-window fusion: pick CHROM, POS or DL per window by signal-quality index
  (open-rppg ships an `SQI()` spectral-concentration score).

### B. App-side pre-DSP with DL
- **MediaPipe Face Landmarker** (478 landmarks, LIVE_STREAM mode) for a skin-only polygon ROI
  (forehead + cheeks) and steadier tracking than a box. Classical HR was insensitive to ROI
  shape offline (Segment 7), but handheld video differs: here the question is *staying on
  skin*, not shape. Segment 30's cost problem was the YUV→Bitmap conversion, and
  `OUTPUT_IMAGE_FORMAT_RGBA_8888` removes it. That exact fix was flagged in Segment 30's
  own "future re-attempt" note but never tried.
- The FacePhys input (36×36 face crop) needs only the face box, so it can run on today's
  ML Kit box.

### C. SpO2
- **Deep learning on public data will not fix it.** Cheng et al. 2024 (VIPL, EfficientNet on
  spatial-temporal maps) report 0.98% MAE in the normal range, but Segment 33 showed that
  predicting the VIPL mean already gets ≈1.15, because the labels barely move.
- The only credible route is **device-specific calibration from our own paired data**: the
  locked linear capture (Segment 34) plus the Segment 33 anchored-trend model
  `SpO2 = S0 + β·(L − L0)`, fitted from breath-hold sessions with the oximeter in view (§4).
- Possible external data to check (flagged, access not tried): **MCD-rPPG** (ACM MM 2025,
  HuggingFace `wengziheng/mcd_rppg`): 600 subjects, 3600 videos, 100 Hz PPG, and SpO2 among
  its metrics. Useful for HR training and testing; SpO2 variation is probably narrow again
  (healthy people), so same caveat.
- Honest expectation to state in the report: after per-device calibration, a phone RGB
  camera can at best **track** SpO2 *changes* (for example a breath-hold dip). It cannot give
  clinical-grade absolute values.

---

## 4. Oximeter viewing box (debug + ground-truth recorder)

Problem: the front-camera preview (`PreviewView`) is shown mirrored, like a selfie, so the
oximeter's digits read backwards.

Key fact: the **analysis frames are not mirrored** (raw sensor orientation). Only the preview
is. So:

1. Draw a fixed guide rectangle on the preview (lower part of the screen, away from the
   forehead) labelled "Keep oximeter screen here".
2. Map that rectangle from view coordinates to analysis-frame coordinates (inverse of
   `CoordinateMapper.rotatedRectToViewRect`, including the mirror flip).
3. Crop that region from each analysis frame, rotate it upright, and show it as a
   **magnified inset** (for example top-right, ~2× zoom), un-mirrored, so the digits read correctly.
4. A developer toggle "un-mirror whole preview" (`previewView.scaleX = -1f`) as a fallback.
5. Save the inset as a small JPEG once per second, with its sensor timestamp, next to the
   calibration CSV (the Segment 34 recorder). Afterwards the SpO2/PR digits are read from
   those crops (seven-segment OCR in Python, or read by Claude) → a **paired ground-truth
   dataset with no manual writing-down**.
6. Optional: live ML Kit Text Recognition v2 on the inset to show "Oximeter: 97% / 72" next
   to our values. Seven-segment digits are hard for general OCR, so this is optional and step 5 is the reliable path.

Practical cautions for recording:
- A finger oximeter updates about once a second and averages over ~4–8 s, so compare with a
  few seconds of lag tolerance, not frame by frame.
- Keep the oximeter away from the forehead/cheek ROI: its red/IR LEDs and its screen glow can
  light the skin and leak into the signal.
- Keep the oximeter hand resting (on a table or the chest). If the same arm also holds the
  phone, both move together.

---

## 5. Test plan (pre-registered order; subject-level paired stats, Holm-corrected)

Metrics everywhere: MAE, RMSE, Pearson r, % of windows within ±5 bpm, tick-to-tick jitter
(app), missed-face % (app). Baseline = production POS/CHROM as currently shipped.

| Test | What | Needs | Decision rule |
|---|---|---|---|
| **1** | open-rppg models (FacePhys, ME, RhythmMamba, PhysMamba, EfficientPhys, TSCAN, PhysNet) vs production CHROM/POS on MAIN_112 (UBFC 5 + VIPL 107) and the VIPL v2 motion pool (20) | Abrar's PC (Python 3.12 present), existing videos, ~hours of CPU | Adopt as "DL branch" if subject-level MAE is better with Holm p<0.05 on MAIN_112 and not worse on motion |
| **2** | Same on the Own Dataset (9 cases with GT; HLG→SDR tone-map first) | Test 1 script | Reported, not a decision gate (n=9, weak GT) |
| **3** | A2: DL pulse through Branch 2, notch confidence on the 5 UBFC GT subjects | Test 1 outputs | Adopt for Branch 2 only if the pass count ≥ current 4/5 and mean confidence rises |
| **4** | App fix pack 1: H1, H2, H3, H5 (+ peak interpolation) | Code only, unit tests | Must pass tests + no crash; measured in Test 6 |
| **5** | App read-out: whole-window FFT vs windowed read-out vs SQI-gated display | Test 6 recordings replayed offline | Lowest MAE vs oximeter |
| **6** | On-device A/B with the oximeter box: {old build, fix pack 1, + locked AE with re-lock} × {phone on stand, handheld} × {USB, unplugged}, 3 min each, recorder on | Abrar + phone + oximeter, ~1 h | Promote defaults that beat baseline handheld/unplugged |
| **7** | FacePhys on Android: convert Keras→TFLite (state as explicit in/out tensors), 36×36 crop from the face box, resample to 30 Hz; A/B vs fixed classical build | Test 1 win first | Promote if it beats the classical build in Test 6 conditions |
| **8** | SpO2: 5–10 breath-hold sessions (locked capture + oximeter box) → fit S0/β → leave-one-session-out check | Tests 4/6 infrastructure | Show SpO2 only as trend/relative unless LOSO error beats "constant 97%" |

Order: **1 → 4 (in parallel with 1, it's offline code) → 6 → 3/5 → 7 → 8.** Test 1 and
fix pack 1 need no phone; Test 6 is the first session that needs Abrar holding the phone.

---

## 6. Sources

- Wang K., Tang J., Wang Y., Liu X., Fan Y., Ji J., Shi Y., McDuff D. *FacePhys: State of the Heart Learning.* arXiv:2512.06275 (2025). https://arxiv.org/abs/2512.06275
- Wang K. et al. *Memory-efficient Low-latency Remote Photoplethysmography through Temporal-Spatial State Space Duality.* arXiv:2504.01774 (2025)
- open-rppg toolbox: https://github.com/KegangWangCCNU/open-rppg (weights and input shapes inspected from a local clone, 2026-09-28)
- Joshi J., Cho Y. *FactorizePhys.* NeurIPS 2024, arXiv:2411.01542. https://github.com/PhysiologicAILab/FactorizePhys (reference; not in open-rppg)
- Cheng C.-H. et al. *Contactless Blood Oxygen Saturation Estimation from Facial Videos Using Deep Learning.* Bioengineering 11:251 (2024). https://pmc.ncbi.nlm.nih.gov/articles/PMC10968547/
- Egorov K. et al. *Gaze into the Heart: A Multi-View Video Dataset for rPPG and Health Biomarkers Estimation.* ACM MM 2025, doi:10.1145/3746027.3758255. Dataset: https://huggingface.co/datasets/kyegorov/mcd_rppg (gated); code: https://github.com/ksyegorov/mcd_rppg
- Kim D.-Y., Lee K., Sohn C.-B. *Assessment of ROI Selection for Facial Video-Based rPPG.* Sensors 21:7923 (2021)
- Google AI Edge, Face Landmarker guide: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- Internal: Segments 7, 18, 21–30, 33, 34 docs (paths in the header).
