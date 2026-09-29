# Segment 35 Phase 1 — MediaPipe anatomy-informed ROI (offline MATLAB evaluation)

Date: 2026-09-28/29 · Status: **PROMOTED TO PRODUCTION DEFAULT (2026-09-29,
`opts.useAnatomyROI` in `pipeline/estimateVitalsAndMorphology.m`, default `true`) BY
EXPLICIT INSTRUCTION, NOT because the phase's own pre-registered promotion rule
passed.** The static/semi-natural pool result is real and Holm-significant. The
motion pool (the actual question this phase was designed to answer) finished at
19/20 subjects after this doc's first version was written, and its own
Holm-corrected Wilcoxon test is **NOT significant** (p=0.215 both CHROM/POS) despite
a large raw MAE improvement — see §3.3/§3.4, updated 2026-09-29. Branch 2 waveform
correlation with GT PPG was also measured for the first time (§3.5): a small, mixed
(not one-sided) effect, unlike notch confidence's clear regression.

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
5. **[2026-09-29 UPDATE] The motion pool finished at 19/20 subjects** after this doc's
   first version was written (a separate resumable follow-up script,
   `run_segment35_mediapipe_anatomy_roi_motion_only_batch.m`; the 1 missing subject was
   left unresolved by explicit instruction). Raw MAE improves in the same direction as
   the static pool (CHROM 7.63→4.54bpm, POS 8.88→5.65bpm — §3.3), but the **paired
   Holm-corrected Wilcoxon test on this pool is NOT significant** (p=0.215 both methods
   — §3.4): only 7-8 of the 19 subjects are non-zero pairs (the rest tied exactly
   between baseline and anatomy), too little power to clear significance despite the
   large point estimate.
6. **PROMOTED ANYWAY, BY EXPLICIT INSTRUCTION (2026-09-29).** The plan's own
   pre-registered decision rule — "promote only if it's a real, Holm-corrected
   improvement on the MOTION pool without regressing the static pool" — **does NOT
   pass** under the completed motion-pool test. `pipeline/estimateVitalsAndMorphology.m`'s
   `opts.useAnatomyROI` defaults to `true` anyway, per Abrar's direct instruction after
   being shown this exact non-significant result. Stated plainly: the promotion rests on
   the static-pool significance + the motion-pool's directionally-consistent (if
   underpowered) point estimate, not on a passed pre-registered test. See §3.4-§3.6.
   The remaining 44 VIPL v1 subjects (for a clean full-107 static-pool number) and the
   20th motion-pool subject are still not run — a disclosed gap, not done as part of
   this promotion.

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

### 3.3 VIPL v2 motion pool — 19/20 subjects, completed 2026-09-29

`run_segment35_mediapipe_anatomy_roi_motion_only_batch.m` (a separate, resumable
follow-up script extracted verbatim from the main batch's own Pool C loop, per its own
header) finished 19 of the 20 motion-pool subjects after this doc's first version was
written. The 20th subject was left unresolved by explicit instruction (not attempted
further this round). Raw numbers, `results/metrics/segment35_mediapipe_anatomy_branch1_motion20.csv`:

| Method | Baseline MAE | Anatomy MAE | Baseline RMSE | Anatomy RMSE | Baseline r | Anatomy r | Tied | Anatomy better | Anatomy worse |
|---|---|---|---|---|---|---|---|---|---|
| CHROM | 7.63 | **4.54** | 10.64 | 6.40 | 0.610 | 0.892 | 11 | 5 | 3 |
| POS | 8.88 | **5.65** | 14.98 | 11.65 | 0.419 | 0.554 | 12 | 5 | 2 |
| Green | 21.46 | 23.47 | 24.78 | 26.97 | -0.204 | -0.170 | 7 | 3 | 9 |

Same direction as the static pool for CHROM/POS (MAE cut ~40%, r up), green (never a
serious candidate) unaffected/slightly worse. **But see §3.4 — this MAE improvement does
NOT clear this project's own significance bar**, unlike the static pool's Holm-significant
result.

### 3.4 Motion-pool significance test (2026-09-29)

Paired Wilcoxon signed-rank test on |error_baseline| − |error_anatomy| per subject
(same method as §3.1's static-pool test — dropped-zero convention, normal approximation
with tie correction and continuity correction, matching MATLAB's own `signrank` default
for n>15/ties-present), then Holm correction across the 2 methods:

| Method | n (total) | n (non-zero pairs) | W+ | W− | z | raw p | Holm p |
|---|---|---|---|---|---|---|---|
| CHROM | 19 | 8 | 30.0 | 6.0 | 1.610 | 0.107 | 0.215 |
| POS | 19 | 7 | 23.0 | 5.0 | 1.437 | 0.151 | 0.215 |

**Neither method reaches significance.** The reason is exact ties, not a weak effect
among the subjects that actually differ: 11/19 (CHROM) and 12/19 (POS) subjects are
byte-identical between baseline and anatomy (the anatomy extractor's skin-pixel filter
likely fell back to the same effective pixels on these clips), leaving only 7-8 subjects
to drive the test — not enough power to clear significance even with a large point
estimate among those that do differ. This method was validated by reproducing §3.1's own
static-pool p-values independently (got p=0.0184/0.0015 against the doc's own
0.0177/0.0014 — close enough, small residual differences consistent with MATLAB's exact
tie-correction implementation, to trust this motion-pool result).

**This is the actual test the phase's pre-registered promotion rule required, and it does
not pass.** See §5 for the promotion decision made despite this.

### 3.5 Branch 2 waveform correlation with ground-truth PPG (NEW, 2026-09-29)

§3.2 only measured notch-confidence pass/fail. A follow-up script,
`run_segment35_task_branch2_gt_correlation.m`, additionally measured the ABPF-combined
pulse's own Pearson correlation against the real ground-truth contact-PPG waveform (same
lag+polarity-search alignment method as Segment 13's `analyzeHarmonicBranch` —
`estimateLagPolarityByGroundTruth.m` + z-score + Pearson r), on all 5 UBFC subjects.
Results (`results/metrics/segment35_branch2_gt_correlation.csv`; notch-confidence column
reproduced here only to confirm this run matches §3.2 exactly, which it does):

| Subject | Notch conf. base | Notch conf. anatomy | Waveform corr. base | Waveform corr. anatomy |
|---|---|---|---|---|
| 5-gt | 0.7447 | 0.1034 | 0.5349 | 0.5012 |
| 6-gt | 0.4120 | 0.3204 | 0.2019 | 0.2204 |
| 7-gt | 0.6405 | 0.1587 | 0.2397 | 0.2147 |
| 12-gt | 1.0000 | 0.4880 | 0.3786 | 0.3446 |
| after-exercise | 0.1582 | 0.1030 | 0.2103 | 0.2192 |
| **mean** | | | **0.3131** | **0.3000** |

**A materially different picture from notch confidence.** Where notch confidence
collapsed sharply (4/5→2/5 pass, e.g. 5-gt 0.74→0.10), waveform correlation with GT PPG
moves only slightly and in BOTH directions (3/5 subjects worse, 2/5 better; mean
0.313→0.300, a ~4% relative drop). The anatomy ROI is not "destroying" Branch 2's
waveform fidelity the way the notch-confidence number alone might suggest — it is
picking up a real but much smaller correlation cost, on top of (not fully explaining) the
notch-detector's own pass/fail sensitivity. **n=5 is this project's standing small-sample
caveat** (same as every UBFC-only Branch 2 result) — no formal significance test is
reported here; the numbers are stated as descriptive, not tested.

### 3.6 Promotion decision (2026-09-29)

The plan's own pre-registered rule — "promote only if it's a real, Holm-corrected
improvement on the MOTION pool without regressing the static pool" — **fails** per §3.4
(motion pool Holm p=0.215, not significant). `pipeline/estimateVitalsAndMorphology.m`'s
`opts.useAnatomyROI` was set to default `true` anyway, **by Abrar's explicit instruction
after being shown this exact result**, along with `run_vipl_integration_batch.m`,
`run_segment2_roi_batch.m`, and the standalone `run_spandan_interactive_anatomy_roi.m`
(renamed 2026-09-29 from `run_spandan_interactive.m` — see
`Segment36_Own_Dataset_Evaluation.md`; with a best-effort fallback to the classical ROI
if MediaPipe is unavailable — see that file's own new header note). See
`SESSION_HANDOFF.md`'s Segment 35 entry and
`docs/Spandan_Final_Pipeline_Report.md` for where this is recorded project-wide. **What
this promotion is NOT**: a claim that the pre-registered motion-robustness bar was met.
It was not. The static-pool significance (§3.1) plus the motion-pool's
directionally-consistent, underpowered point estimate (§3.3) were judged sufficient by
the project owner to promote anyway — a human decision overriding the pre-registered
statistical rule, recorded here so it is never later mistaken for a passed test.

---

## 4. What this does and doesn't support

**Supports**: a real, statistically significant, large-effect-size HR accuracy improvement
from anatomy-informed multi-region ROI selection, on a 68-subject partial static/semi-
natural pool (§3.1), AND a directionally-consistent (though not significant) improvement
on the 19-subject motion pool (§3.3). This is a genuinely new result in this project's
history — every prior ROI-shape experiment found nothing (bit-identical HR) or only harm
(notch regression).

**Does NOT support**: the phase's own pre-registered motion-robustness promotion rule
(§3.4 — the motion-pool test is not significant), a claim about the full static pool
(63/107 VIPL, not all 107 — the remaining 44 could shift the numbers, though a
Holm-significant result on 63 is unlikely to fully reverse), or a claim that Branch 2
waveform fidelity is unaffected (§3.5 shows a small real cost, separate from notch
confidence's larger one). **It was nonetheless promoted to the production default**
(§3.6) — by explicit instruction, not because every piece of evidence supported it.

## 5. Recommended follow-up (not done this session)

1. Resume `run_segment35_mediapipe_anatomy_roi_batch.m` (proven resumable/idempotent) to
   finish the remaining 44 VIPL v1 subjects (p64-p107ish) for a clean full-107 static-pool
   number, and find/rerun the 20th motion-pool subject.
2. Re-run §3.1/§3.4's stats on the completed 112-subject static pool + full 20-subject
   motion pool, to see whether the motion-pool test's power problem (too many exact ties)
   resolves with the 20th subject or needs a genuinely larger motion pool.
3. Now that this IS the production default, regenerate the headline pooled metrics
   (`results/metrics/segment6_hr_pooled_metrics.csv` and everything derived from it) under
   the anatomy ROI — not done as part of this promotion (a multi-hour MediaPipe batch over
   112+ subjects), so every existing headline number in `Spandan_Final_Pipeline_Report.md`
   still reflects the PRE-anatomy-ROI pipeline. This is a real, disclosed gap.
4. An Android port of this ROI (MediaPipe landmarks on-device) is a live question now that
   it's the MATLAB production default — a substantial new scope (a MediaPipe dependency on
   Android, which Segment 30 already found costly for a different reason — the
   YUV→Bitmap conversion tax, now partially mitigated by Segment 35 Phase 2's own work but
   not eliminated). Not started.

## 6. Files

`matlab/src/roi/faceMeshAnatomyROIExtraction.m` (new), `matlab/scripts/
run_segment35_mediapipe_anatomy_roi_batch.m` (new, resumable), `matlab/scripts/
run_segment35_mediapipe_anatomy_roi_motion_only_batch.m` (new, 2026-09-29, resumable Pool-C
follow-up), `matlab/scripts/run_segment35_task_branch2_gt_correlation.m` (new, 2026-09-29,
§3.5's GT-PPG correlation), `results/metrics/segment35_mediapipe_anatomy_notch.csv` (5/5
UBFC, complete), `results/metrics/segment35_mediapipe_anatomy_branch1_main112.csv` (68/112,
partial — 5 UBFC + 63/107 VIPL), `results/metrics/segment35_mediapipe_anatomy_branch1_motion20.csv`
(19/20, updated 2026-09-29), `results/metrics/segment35_branch2_gt_correlation.csv` (new,
2026-09-29, 5/5 UBFC), `data/processed/*_meshanatomy_rgb_traces.mat` (87 cached traces as of
2026-09-29 — 68 static-pool + 19 motion-pool, reusable to resume without re-decoding),
`results/figures/segment35_anatomy_roi_*.png` (periodic debug figures, one per ~10 VIPL
subjects + all completed UBFC subjects).

**Production promotion files touched (2026-09-29, see §3.6)**:
`matlab/src/pipeline/estimateVitalsAndMorphology.m` (`opts.useAnatomyROI`, default `true`),
`matlab/scripts/run_vipl_integration_batch.m`, `matlab/scripts/run_segment2_roi_batch.m`,
`matlab/scripts/run_spandan_interactive_anatomy_roi.m` (standalone demo, with a best-effort
fallback to the classical ROI if MediaPipe is unavailable), `matlab/scripts/
run_spandan_full_demo.m` (header note only, new implicit dependency),
`matlab/docs/Spandan_Final_Pipeline_Report.md`, `SESSION_HANDOFF.md`. Historical/frozen
per-segment experiment scripts (Task D/H/I/J/K, the colorspace ablation, Task N multi-region,
etc.) were deliberately NOT touched — they remain frozen snapshots of their own segment's
methodology, per this project's standing convention.
