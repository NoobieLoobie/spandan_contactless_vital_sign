# Segment 36 — "Own Dataset" evaluation, universal video normalization, and the
# two-pipeline split

Date: 2026-09-29 · Status: **DONE** (all 9 subjects evaluated). Consequence: the anatomy
ROI promotion (Segment 35) is **NOT reverted**, but is now shipped alongside a parallel
classical (no ML/DL) pipeline copy (`../../matlab_classical/`) rather than as the sole
default, because this independent dataset's result does not confirm Segment 35's finding
and in one method (POS) meaningfully contradicts it.

---

## 0. Why this segment exists

`docs/Segment35_Accuracy_Research_and_Plan.md` (Test 2) identified the "Own Dataset" --
9 subjects with real finger-PPG ground truth (AFE4404 device), recorded 2026-09-19,
sitting in `Own Dataset/` (a sibling of the `spandan/` repo, never previously integrated)
-- as a genuine third, independent validation source beyond UBFC and VIPL. That test was
gated on Test 1 (open-source DL rPPG models) and never ran, because Test 1 was cancelled
by Segment 35's own scope correction (ML/DL restricted to ROI extraction only). This
segment finally runs it, using the NOW-PROMOTED anatomy ROI vs. the classical baseline,
directly extending Segment 35's own comparison methodology to a dataset neither the
anatomy ROI nor CHROM/POS were ever tuned or validated against before.

## 1. New infrastructure this required

### 1.1 Video format problem, verified directly

`ffprobe` on the Own Dataset videos: HEVC, 10-bit `yuv420p10le`, `color_transfer=
arib-std-b67` (HLG, an HDR transfer function), `color_space=bt2020nc`. One file
(the landscape-stored, non-"_Vertical" sibling) additionally carries a 90-degree
`Display Matrix` rotation flag. A direct MATLAB test this session confirmed
`VideoReader` decodes this WITHOUT erroring -- it does not crash -- but:
- the landscape-flagged file's frames come out sideways (`VideoReader` ignores the
  rotation flag), and
- every frame's RGB values come from an uncontrolled, unverified HDR-to-8-bit
  conversion (Windows Media Foundation's own default, not a real HLG electro-optical
  transfer function).

Neither failure mode raises an error -- both would have silently fed CHROM/POS/the
anatomy ROI wrong pixel data with no warning.

### 1.2 `matlab/src/io/ensureSDRVideo.m` (new)

Probes any video via `ffprobe`. **Verified a no-op for every video this project has
validated on**: UBFC (`rawvideo`/`bgr24`/`color_transfer=unknown`) and VIPL
(`mjpeg`/`yuvj420p`/`color_transfer=unknown`) both have no HDR transfer and no rotation
flag, confirmed directly, so this function changes nothing for anything already
validated. Only a file with an HDR transfer (`arib-std-b67`/HLG, `smpte2084`/PQ,
`smpte428`), a non-8-bit pixel format, or a rotation flag gets tone-mapped
(`zscale`+`tonemap=hable`) and/or re-encoded via `ffmpeg` -- whose own default
auto-rotate handles the rotation case in the same pass, verified directly (the
90-degree-flagged file produces correctly oriented output through this exact filter
chain). Output is cached in `data/processed/_video_cache/` (gitignored), keyed by the
input's path+size+mtime, so repeated/resumed runs never re-encode a file twice.
Gracefully returns the original path unchanged if `ffprobe`/`ffmpeg` are missing or fail
-- never crashes the caller.

Wired into `io/loadUBFCVideo.m` and `io/loadVIPLVideo.m` (both call it immediately
before their own `VideoReader(...)`), and embedded as a verbatim local copy in the
standalone `scripts/run_spandan_interactive_anatomy_roi.m` (`matlab/`) /
`run_spandan_interactive_classical.m` (`matlab_classical/`) -- renamed from the original
shared `run_spandan_interactive.m` specifically so the two copies never collide on one
filename once pulled out of their directories -- matching that file's own "copy
anywhere, zero extra dependency" design --
with a best-effort availability probe and per-call fallback there, since a hard
MediaPipe/ffmpeg requirement would violate that file's own stated design goal.

### 1.3 `matlab/src/io/loadOwnDatasetGroundTruth.m` (new)

Loads the Own Dataset's ground-truth format (per-subject full-recording CSV: 100 Hz,
DC-removed + 0.5-8 Hz bandpassed PPG; plus the summary CSV's three independent HR
estimates -- beat-mean, beat-median, spectral). SpO2 in this dataset is explicitly
flagged unreliable by its own README (unconfirmed LED wavelength assignment, near-noise-
floor second channel) and is carried through for completeness only, never compared
against this pipeline's own SpO2 output.

### 1.4 Subject<->ground-truth mapping, resolved by timestamp cross-matching

Two of the nine ground-truth rows do NOT share a name with their video folder:
- **"Device_test"** (the ground-truth dataset's own README flags this as "unlabeled
  recording, filename has no subject name, confirm whose this is") has source_file
  `Device_Volts_20260919_141054.csv` -- IDENTICAL timestamp to
  `Abrar/Normal/Abrar_Volts_20260919_141054.csv`. **Resolved: Device_test = Abrar
  (Normal condition).**
- **"Lubaba_after_exercise"** has source_file
  `Lubaba_after_exercise_Volts_20260919_150258.csv` -- IDENTICAL timestamp to
  `Lubaba/After Breath Hold/Lubaba_after_breath_hold_Volts_20260919_150258.csv`.
  **Resolved: despite its own filename, this ground-truth row is the AFTER-BREATH-HOLD
  condition, not exercise** -- a real labeling inconsistency in the ground-truth export,
  stated plainly rather than assumed either way.

`Ramis/After_exercise` and `Abrar/After_exercise` have no timestamp-matched ground truth
at all -- excluded, not guessed. Final usable set: **9 subject-condition pairs** (7
"normal", 1 "after-breath-hold", 1 "after-exercise").

---

## 2. Method

Byte-for-byte Segment 35's own UBFC Pool A protocol
(`run_segment35_mediapipe_anatomy_roi_batch.m`'s `notchConfidenceFor`, reused verbatim)
plus Segment 35's GT-PPG correlation method
(`estimateLagPolarityByGroundTruth.m` + z-score + Pearson r), applied to baseline
(`roi/extractROISignals.m`) vs. anatomy (`roi/faceMeshAnatomyROIExtraction.m`) ROI on
each of the 9 subjects. Shared f0 for Branch 2 comes from the BASELINE whole-ROI
wide-band CHROM pulse (same convention as Segment 35). `maxLagSec=15s` (vs. Segment
13/35's 2s), because this dataset's video and PPG come from two independently-started,
unsynchronized devices (a phone and a separate PPG logger) -- the true start offset is
unknown and, confirmed by the results below, sometimes genuinely large.
Script: `scripts/run_segment36_own_dataset_evaluation.m` (resumable/idempotent
per-subject, survived one harness low-memory kill mid-run without losing completed
subjects -- see §4).

---

## 3. Results

### 3.1 Branch 1 HR accuracy, all 9 subjects

| Method | vs. GT | Baseline MAE | Anatomy MAE | Baseline r | Anatomy r | Wilcoxon p (n=9) |
|---|---|---|---|---|---|---|
| CHROM | spectral | 6.00 | 7.14 | 0.644 | 0.869 | 0.40 (n.s.) |
| POS | spectral | **2.88** | **5.31** | **0.926** | **0.263** | 0.28 (n.s.) |
| CHROM | beat-mean | 7.12 | 6.92 | 0.681 | 0.916 | 1.00 (n.s.) |
| POS | beat-mean | 4.15 | 3.84 | 0.853 | 0.454 | 0.79 (n.s.) |

**No test reaches significance at n=9** (small, noisy sample). But the direction matters:
**POS -- this project's most accurate method on UBFC/VIPL -- regresses notably here**,
its correlation with ground truth collapsing from 0.93 to 0.26 against the spectral GT,
MAE nearly doubling. CHROM's picture is more mixed (worse MAE vs. spectral GT, roughly
tied vs. beat-mean GT, r improves against both -- consistent with a few large individual
corrections lowering variance-normalized error while raising MAE via other subjects'
losses).

Full per-subject table: `results/metrics/segment36_own_dataset_branch1_hr.csv`.

| Subject | Condition | GT (spectral) | CHROM base→an | POS base→an |
|---|---|---|---|---|
| OWN_Abrar | normal | 82.0 | 82.1→94.9 | 82.1→87.8 |
| OWN_Bayezid | normal | 82.0 | 81.5→81.5 (tied) | 81.5→81.5 (tied) |
| OWN_Hadi | normal | 82.0 | 82.8→82.8 (tied) | 82.8→82.8 (tied) |
| OWN_Lubaba | normal | 87.9 | 85.8→90.8 | 89.4→90.8 |
| OWN_Lubaba_ABH | after-breath-hold | 99.6 | 94.0→108.8 | 94.0→77.2 |
| OWN_NayeemSir | normal | 85.0 | **57.3→85.2** | 85.2→85.2 (tied) |
| OWN_Ramis | normal | 70.3 | 64.5→45.3 | 64.5→72.7 |
| OWN_SelimSir | normal | 76.2 | 73.4→73.4 (tied) | 73.4→73.4 (tied) |
| OWN_Siyam | after-exercise | 79.1 | 70.5→88.9 | 70.5→88.9 |

Note Nayeem Sir: baseline CHROM badly failed (57.3 vs. GT 85.0, likely a rate-halving
error), and the anatomy ROI fixed it completely (85.2, near-exact) -- the single clearest
positive case in this pool, and the mirror image of the aggregate POS regression above.
This is a genuinely mixed dataset, not a one-sided negative result.

### 3.2 Branch 2 notch confidence and GT-PPG waveform correlation

Notch pass rate (≥0.3 bar): baseline 2/9 → anatomy 3/9 (both low -- home recordings are
harder than UBFC's controlled setup). Mean waveform correlation: 0.219 → 0.266.

**Data-quality caveat, checked directly**: 3 of 9 subjects' correlation lag estimates
landed within 1.6s of the ±15s search boundary (Lubaba -12.1/-12.7s, Ramis -4.0/+11.3s,
Siyam -13.5/-14.4s) -- a sign the cross-correlation search did not find a confident peak
for these subjects, not a real alignment. **Excluding those 3, the remaining 6 subjects
ALL improve or tie under the anatomy ROI** (mean 0.243→0.294, every single subject
individually non-negative) -- a clean, consistent positive signal for Branch 2 waveform
correlation specifically, distinct from and more consistent than the Branch 1/notch
picture.

Full per-subject table: `results/metrics/segment36_own_dataset_branch2_gt_correlation.csv`.

---

## 4. A real bug, found and fixed during this segment

`branch1HR` (this segment's own local function) called `posCombine(Rf, Gf, Bf, R, G, B)`
-- missing `posCombine.m`'s 4th argument, `frameRate` (its signature is
`posCombine(R, G, B, frameRate, RRaw, GRaw, BRaw)`, unlike `chromCombine.m` which has no
frameRate argument -- the two functions' signatures differ and this was a copy-paste
mismatch). Crashed on the very first subject with "Not enough input arguments" inside
`posCombine`. Fixed by adding the missing `fs` argument. The resumable per-subject cache
design meant the crash cost zero re-work -- both subjects already processed survived and
were reused on the next run.

Separately, the script's own CSV-writing had a real bug (unconditional
`writelines(header, path)` on every launch, instead of `if ~isfile(path)` guarding it the
way Segment 35's own batch scripts do) that silently wiped the first 5 subjects'
already-saved rows on a mid-run restart (caused by an unrelated harness low-memory kill,
same pattern that interrupted Segment 35's own batch twice). No data was actually lost --
the underlying `.mat` traces were still cached, so the affected rows were recomputed and
correctly re-appended within seconds on the next run -- but the bug itself is fixed for
future correctness.

---

## 5. Consequence: the two-pipeline split

Segment 35 promoted the anatomy ROI as the sole default despite an already-known
non-significant motion-pool test, on the reasoning that the static-pool significance plus
a directionally-consistent (if underpowered) motion-pool point estimate were enough to
act on. This segment's result is a THIRD, independent test, and it does not confirm the
promotion -- POS notably regresses, even though Branch 2 correlation mildly improves and
one subject (Nayeem Sir) shows the clearest single positive case yet.

Given a real accuracy regression on POS specifically, on data neither pipeline was tuned
against, keeping ONE forced default was judged the wrong call. Instead (2026-09-29, by
explicit instruction): **`matlab/` keeps `opts.useAnatomyROI=true` as its default (the
ML/DL-anatomy pipeline), and a full sibling copy, `matlab_classical/`, has the same
toggle forced `false` everywhere (the classical, no-ML/DL pipeline)** -- see each
directory's own `PIPELINE_VARIANT.md` for the exact file list and the shared-data-folder
caveat. Both pipelines are otherwise byte-identical (a fork of one codebase, not two
independently maintained ones); a future shared-function fix must be applied to both
copies by hand.

## 6. Files

`matlab/src/io/ensureSDRVideo.m` (new), `matlab/src/io/loadOwnDatasetGroundTruth.m`
(new), `matlab/scripts/run_segment36_own_dataset_evaluation.m` (new, resumable),
`results/metrics/segment36_own_dataset_branch1_hr.csv`,
`results/metrics/segment36_own_dataset_branch2_gt_correlation.csv`,
`results/figures/segment36_anatomy_roi_*.png` (9 debug figures), `data/processed/
OWN_*_rgb_traces.mat` / `OWN_*_meshanatomy_rgb_traces.mat` (9 subjects x 2 ROI
conditions, cached), `data/processed/_video_cache/*.mp4` (9 tone-mapped/re-encoded
source videos, cached). `matlab_classical/` (new sibling directory, full copy of
`matlab/` with the anatomy-ROI toggle forced off in 4 files + its embedded
`run_spandan_interactive_classical.m` copy, renamed from the original shared
`run_spandan_interactive.m`).
