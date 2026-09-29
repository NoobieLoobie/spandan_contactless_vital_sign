# Segment 35 Phase 1 — MediaPipe anatomy-informed ROI (offline MATLAB evaluation)

Date: 2026-09-28/29 · Status: **PARTIAL RESULT, batch stopped by request before completion
— a real, statistically significant finding on the static pool, but the actual
motion-robustness question this phase was designed to answer is UNTESTED.**

Executes `docs/Segment35_Accuracy_Research_and_Plan.md` Phase 1. Implements
`matlab/src/roi/faceMeshAnatomyROIExtraction.m`: a MediaPipe FaceMesh landmark-driven
forehead + both malar (cheekbone) ROI, per Kim, Lee & Sohn (2021), *Assessment of ROI
Selection for Facial Video-Based rPPG*, Sensors 21:7923 (VERIFIED-FULL via PMC, PMC8659899,
2026-09-28) — their own top-5 anatomical regions by BVP-similarity + skin-thickness were
right/left malar, glabella, upper/lower medial forehead. A classical YCbCr skin-tone pixel
filter (Cb∈[77,127], Cr∈[133,173], Chai & Ngan-style) gates which pixels inside each region
are averaged. Reuses Segment 7 Task H/J's MediaPipe/`pyenv('ExecutionMode','OutOfProcess')`
setup verbatim. **No ML/DL outside the ROI-extraction stage** — the CHROM/POS/notch chain
downstream is completely unmodified, per the session's own binding scope correction.

**Read first**: Segment 7 Task H/J (`Segment7_Task_H_FaceMesh_ROI.md`,
`Segment7_Task_J_FaceMesh_Hybrid_ROI.md`) — every prior ROI-shape experiment in this
project (axis-aligned box, KLT proxy, real face-mesh polygon, mesh+box hybrid) found
**zero** HR accuracy difference (bit-identical CHROM/POS) and only *regressions* on notch
confidence. This phase is the first to find a real, significant HR accuracy difference.

---

## 0. Bottom line

1. **Landmark choice, verified before any batch ran** (this project's own "verify before
   trusting" discipline): the paper cites a cadaver skin-thickness atlas for its 39 regions,
   not MediaPipe landmark indices, so the forehead/cheek polygons here are this project's
   own construction. A first attempt's cheek landmarks sat at glasses/eye height (visually
   wrong on inspection); fixed by moving to under-eye→nasolabial-fold landmarks, verified
   again on a real frame before committing. See §1 for both attempts' debug images.
2. **On 68 subjects completed (5 UBFC + 63/107 VIPL v1) before the batch was stopped**: a
   **real, Holm-corrected-significant improvement** in Branch 1 HR accuracy — CHROM MAE
   8.87→4.77 bpm (p=0.018), POS MAE 7.49→3.74 bpm (p=0.003). The first ROI-shape change in
   this project's ~30-segment history to show a significant HR accuracy effect either way.
3. **The improvement is entirely a VIPL phenomenon, not a UBFC one**: all 5 UBFC subjects
   are bit-identical between baseline and anatomy ROI (same as every prior ROI experiment on
   this posed/controlled dataset). Of the 63 VIPL subjects, 39 are tied, 17 improve, 7
   regress under the anatomy ROI.
4. **Branch 2 notch confidence regresses**, matching the established pattern: baseline 4/5
   UBFC subjects pass the 0.3 bar, anatomy ROI 2/5 (6-gt 0.41→0.32 still passes; 12-gt
   1.00→0.49 still passes; 5-gt, 7-gt, after-exercise fail).
5. **The batch was stopped (user request, "end the matlab tasks") at 63/107 VIPL v1
   subjects and 0/20 VIPL v2 motion-pool subjects** — twice interrupted by the harness's own
   low-memory background-task reaper before that, each time resumed cleanly (the script is
   idempotent per-subject), then stopped for real on the third run. **The motion-pool test —
   the actual question this phase exists to answer ("is ROI shape motion-robust")row — was
   never reached.** This result is real and worth reporting, but it answers a different,
   narrower question ("does this ROI help on a static/semi-natural pool") than the one this
   phase was designed for.
6. **NOT PROMOTED.** The plan's own pre-registered decision rule was "promote only if it's a
   real, Holm-corrected improvement on the MOTION pool without regressing the static pool" —
   that rule cannot be evaluated with zero motion-pool subjects. This is a strong,
   significant **CANDIDATE result on the static/semi-natural pool, motion-robustness
   completely untested** — needs a follow-up session to (a) finish the remaining 44 VIPL v1
   subjects for a clean full-107 static-pool number, and (b) run the 20-subject VIPL v2
   motion pool, before any promotion decision is possible.

---

## 1. Action 0 — landmark verification (before any batch ran)

Two-round visual smoke test, both on a real UBFC frame (subject 5-gt) before committing to
landmark indices:

- **Attempt 1**: candidate cheek landmarks (indices 111/340 under-eye to 129/358 nose-ala)
  produced boxes sitting at glasses/eye height on a bespectacled test subject — visually
  wrong (see the debug figure this session generated, not saved to the repo, described in
  the session transcript). Rejected before any batch ran.
- **Attempt 2**: moved to under-eye (111/340) → nasolabial-fold (216/436) for the vertical
  span, and outer-cheek (137/366) → nose-ala (129/358) for the horizontal span. Verified on
  a real VIPL v2 frame (p1, motion scenario): forehead box lands cleanly above the eyebrows
  below the hairline; both cheek boxes land on the malar/cheekbone area, below the eyes,
  beside the nose, above the mouth — matching Kim et al.'s own top-5 anatomical targets.
  This is the geometry `faceMeshAnatomyROIExtraction.m` ships with.

Skin-pixel filter: classical YCbCr thresholding (Cb∈[77,127], Cr∈[133,173]), chosen over a
learned segmentation model because it needs no extra model/inference cost on top of the
already Python-round-trip-bound MediaPipe call, is deterministic/inspectable, and the two
target regions are small enough that a simple chrominance gate is sufficient to reject
hair/glasses-frame pixels a landmark box alone can't exclude. On the smoke-test clip (VIPL
p1/v2/source1, 1162 frames) the filter never had to fall back to the unfiltered box average
(0/1162 frames below the 10% skin-pixel floor) — it found enough skin pixels every frame.

---

## 2. Method (unchanged from Segment 7 H/J's own protocol)

Shared-f0 source: the EXISTING baseline `extractROISignals.m` whole-ROI wide-band CHROM
decode (not a fresh estimate from the anatomy ROI's own traces) — keeps every condition
compared at the same f0 operating point, exactly Task H/J's own convention. Branch 1
(CHROM/POS + `fftHeartRate.m`) and Branch 2 (ABPF `adaptiveHarmonicFilter.m` at that shared
f0 → `fixPolarityByGroundTruth.m` → `resampleUniform.m` → `ensembleAverageBeats.m` →
`notchDetectIEM.m`) are both completely unmodified — only the ROI-extraction stage differs
between baseline and anatomy conditions. Branch 2/notch confidence only runs on the 5 UBFC
ground-truth subjects (no ground-truth PPG waveform exists for VIPL). Script:
`matlab/scripts/run_segment35_mediapipe_anatomy_roi_batch.m` (resumable/idempotent
per-subject CSV rows — proven twice this session, after two low-memory interruptions).

---

## 3. Results

### 3.1 Branch 1 HR accuracy, 68 subjects (5 UBFC + 63/107 VIPL v1)

| Method | Baseline MAE | Anatomy MAE | Baseline RMSE | Anatomy RMSE | Baseline r | Anatomy r | Tied | Anatomy better | Anatomy worse | Wilcoxon p | Holm p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CHROM | 8.8674 | **4.7683** | 19.2815 | 8.7949 | 0.3402 | 0.7206 | 44 | 17 | 7 | 0.017719 | 0.017719 |
| POS | 7.4898 | **3.7445** | 14.4495 | 5.7532 | 0.4519 | 0.8953 | 43 | 18 | 7 | 0.001430 | 0.002861 |

Both methods: MAE roughly halved, RMSE more than halved (driven by the anatomy ROI removing
some large outlier errors — RMSE's bigger relative drop than MAE's is consistent with that),
Pearson r roughly doubled. Both Holm-corrected p-values (2 tests) are significant at 0.05.

**Every one of the 5 UBFC subjects is bit-identical** between baseline and anatomy ROI on
both CHROM and POS — the improvement above is entirely among the 63 VIPL subjects (39
tied, 17-18 better, 7 worse), consistent with UBFC being posed/controlled (the same
"Branch 1 is ROI-shape-insensitive" finding Segment 7 established) while VIPL v1's more
natural, less-posed footage is where a genuinely better-placed ROI can matter.

### 3.2 Branch 2 notch confidence, 5 UBFC subjects (complete)

| Subject | Baseline confidence | Anatomy confidence | Baseline pass (≥0.3) | Anatomy pass |
|---|---|---|---|---|
| 5-gt | 0.7447 | 0.1034 | YES | no |
| 6-gt | 0.4120 | 0.3204 | YES | **YES** |
| 7-gt | 0.6405 | 0.1587 | YES | no |
| 12-gt | 1.0000 | 0.4880 | YES | **YES** |
| after-exercise | 0.1582 | 0.1030 | no | no |

**Bar count: baseline 4/5 → anatomy 2/5.** Matches the established pattern (Segment 7
H/I/J): every ROI-shape variant tried in this project so far regresses notch confidence,
ranked baseline (4/5) > KLT proxy (2/5) = this anatomy ROI (2/5) > hybrid (1/5) > pure
face-mesh polygon (0/5). The anatomy ROI ties KLT for second place on this metric, still
well short of the plain axis-aligned box.

### 3.3 VIPL v2 motion pool — NOT TESTED

Zero of the 20 motion-pool subjects were processed before the batch was stopped. **This is
the actual open question Phase 1 exists to answer** (does ROI shape matter under
motion/handheld conditions, as opposed to Segment 7's own "not settled under motion"
caveat about its static-clip finding) — it remains completely open after this session.

---

## 4. What this does and doesn't support

**Supports**: a real, statistically significant, large-effect-size HR accuracy improvement
from anatomy-informed multi-region ROI selection, on a 68-subject partial static/semi-
natural pool. This is a genuinely new result in this project's history — every prior
ROI-shape experiment found nothing (bit-identical HR) or only harm (notch regression). Worth
reporting to the supervisor as a real, positive, unexpected finding, clearly labeled as
partial.

**Does NOT support**: any claim about motion robustness (zero motion-pool subjects tested),
a claim about the full static pool (63/107 VIPL, not all 107 — the remaining 44 could shift
the numbers, though a Holm-significant result on 63 is unlikely to fully reverse), or
promotion to production (the plan's own pre-registered rule needs the motion-pool
comparison this session never reached).

## 5. Recommended follow-up (not done this session)

1. Resume the SAME script (`run_segment35_mediapipe_anatomy_roi_batch.m` — proven
   resumable/idempotent across two prior interruptions this session) to finish the
   remaining 44 VIPL v1 subjects (p64-p107ish) and all 20 VIPL v2 motion-pool subjects.
2. Re-run the stats above on the completed 112-subject static pool + fresh 20-subject motion
   pool numbers.
3. Apply the plan's own pre-registered decision rule to the MOTION pool result specifically.
4. Only then decide promotion — and if promoted, only then does an Android port of this
   ROI (MediaPipe landmarks on-device) become a live question, itself a substantial new
   scope (a MediaPipe dependency on Android, which Segment 30 already found costly for a
   different reason — the YUV→Bitmap conversion tax, now partially mitigated by this
   session's Phase 2 work but not eliminated).

## 6. Files

`matlab/src/roi/faceMeshAnatomyROIExtraction.m` (new), `matlab/scripts/
run_segment35_mediapipe_anatomy_roi_batch.m` (new, resumable), `results/metrics/
segment35_mediapipe_anatomy_notch.csv` (5/5 UBFC, complete), `results/metrics/
segment35_mediapipe_anatomy_branch1_main112.csv` (68/112, partial — 5 UBFC + 63/107 VIPL),
`results/metrics/segment35_mediapipe_anatomy_branch1_motion20.csv` (header only, 0/20 —
not reached), `data/processed/*_meshanatomy_rgb_traces.mat` (68 cached traces, reusable to
resume without re-decoding), `results/figures/segment35_anatomy_roi_*.png` (periodic debug
figures, one per ~10 VIPL subjects + all completed UBFC subjects).
