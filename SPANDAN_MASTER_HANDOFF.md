# Spandan — Complete Project History & Master Reference

*Last refreshed **2026-09-20**, from a full read of every `.md` file in
`H:\EEE 312 project\Contactless Vital Sign\` (103 files across
`spandan/`, `spandan/android/docs/`, `spandan/matlab/docs/`,
`spandan/matlab/experiments/`, and `Research Paper/`), cross-checked
against the repo's own living changelog, `SESSION_HANDOFF.md`. This
replaces the previous version of this file (generated 2026-09-14,
current only through Segment 17). This is a **self-contained history +
reference document**: someone with only this file, on a different
computer with no repo access, should be able to understand the whole
project — what it is, everything that has been tried, what shipped,
what didn't, and exactly why, with dates wherever a date exists.*

*It is still not a replacement for the repo's own living
`SESSION_HANDOFF.md`, which stays the actively-maintained, task-by-task
source of truth for anyone with the repo open — this file is the
comprehensive "whole story, in order" companion to it.*

---

## 0. How to use this document

1. **This file**, top to bottom — full context, zero repo access needed.
2. `SESSION_HANDOFF.md` (repo root) — the living, task-by-task current
   state and active queue; more current than anything below by
   definition, and the one to edit when new work happens.
3. `matlab/docs/Spandan_Final_Pipeline_Report.md` — the finished,
   adopted pipeline as a clean technical report (not a work log).
4. `README.md` and `android/README.md` — architecture + folder
   reference.
5. Any specific `docs/Segment*_Task_*.md` or
   `experiments/segmentNN_*/REPORT.md` — the full derivation/evidence
   behind one specific decision.

A note on dates: this project's own record-keeping changed partway
through. Everything up to and including the original MATLAB pipeline
build (Segments 1–9), the VIPL-HR integration, and the first working
Android/iOS ports happened **before** `SESSION_HANDOFF.md` was created
on **2026-09-12** and carries **no reliable calendar dates anywhere in
the source docs** (their headers use a LaTeX `\today` placeholder, not
a real date). Section 1 below covers that period by segment number,
labelled accordingly, rather than inventing dates. Section 2 covers
**2026-09-12 onward**, where every session in the repo's own changelog
is dated, and this document keeps those dates verbatim.

---

## PART I — Who / what / why, and the architecture as it stands today

### 1.1 The 30-second version

**Abrar Jawad** (Student ID 2206019, Section A1, Group 05) — a 3rd-year,
1st-semester (3-1) Electrical and Electronic Engineering undergraduate
at BUET (Bangladesh University of Engineering and Technology) — is
building **Spandan** ("heartbeat / pulsation" in Bengali) as his **EEE
312 (Digital Signal Processing I Laboratory)** group design project,
supervised by **Prof. Dr. Md. Kamrul Hasan**. Group collaborators:
**Ramis Isfar**, **Bayezid Rahman**, **Lubaba Tasnia Khan**.

**Goal**: estimate heart rate (HR) and blood-oxygen saturation (SpO2)
from ordinary facial video, with no contact sensor, using **classical
(non-deep-learning) DSP** in MATLAB — then port the validated pipeline
to a real Android app (and an experimental iOS app).

**Status as of 2026-09-20, one line**: MATLAB pipeline implemented and
validated across two public datasets (112 subjects, extended to 300+
additional VIPL clips across Segments 22–26); Android app is
feature-complete and defense-ready on real hardware, including a live
port of the waveform-morphology branch; iOS app exists but is
CI-verified only; a self-collected Bangladeshi test set has not yet
been started; a 20-segment investigation into motion robustness,
fairness of past rejections, and a promising-but-unvalidated read-out
change closed out on 2026-09-20 with the production pipeline
**unchanged** — every candidate tested lost, tied, or (for one
new finding) needs more validation before it can be adopted.

### 1.2 Course context

- **Course**: EEE 312 — Digital Signal Processing I Laboratory, BUET
  Dept. of EEE (companion theory course EEE 311). Compulsory,
  sessional, 1.5 credit. Assessment: participation 10%, continuous
  assessment 20%, final exam 70%.
- **Structure**: five standard lab experiments (sampling/quantization;
  time-domain analysis; Z-transform; DTFS/DTFT/DFT; FIR filter design)
  plus a term-long **design project** that each group proposes,
  presents, and demonstrates. Spandan is that design project.
- **Supervisor's explicit requirement**, given at the project progress
  presentation: Dr. Hasan asked the team to improve rPPG **signal
  quality** specifically — a waveform as close to real PPG as possible,
  with a visible dicrotic notch, stable. Defects he named in an earlier
  waveform: very few notches, most cut off at the top, the few visible
  ones inverted. Chasing this — "Branch 2" / waveform morphology —
  became the throughline of Segments 6 through 19.

### 1.3 Architecture (current, 2026-09-20)

Two branches off one shared ROI extraction (`roi/extractROISignals.m`):

- **Branch 1 — production HR + SpO2.** Narrow 0.7–4 Hz band. Ships on
  Android.
- **Branch 2 — waveform morphology / dicrotic notch.** Wide 0.5–8 Hz
  band (falls back to a 0.6–6 Hz "mid" band near the Nyquist limit at
  low fps) + a harmonic-comb filter. Ported to Android as of Segment 19
  (2026-09-15/16).

**Deliberately not merged** — the wide-band/harmonic-comb filtering
Branch 2 needs measurably breaks Branch 1's HR accuracy (one VIPL
subject's CHROM jumped 69.4→140.8 bpm when combined).

**Pipeline stages** (both branches share stage 1):

1. **Face detection + ROI extraction** (Segment 2) — Viola-Jones face
   detector per frame, forehead ROI crop, spatial pixel averaging per
   channel → R(t), G(t), B(t). Multi-region (forehead+cheek) exists as
   a gated, non-default MATLAB/Android option (Segment 6 Task N,
   Segment 9 Field Guide Action 1) — never promoted; the plain
   axis-aligned box beat every alternative ROI tried across the whole
   project (KLT tracking, real face-mesh polygon, a hybrid) — see §1.5.
2. **Detrend + bandpass filter** (Segment 3) — cubic-polynomial
   detrend, then zero-phase (`filtfilt`) Butterworth bandpass. As of
   2026-09-13, **DWT wavelet-shrinkage denoising**
   (`filtering/waveletDenoise.m`, db4, 3-level,
   Donoho-Johnstone soft-threshold) runs immediately before this step
   by default, both MATLAB and Android.
3. **CHROM / POS combination** (Segment 4) — two published,
   motion-robust pulse-extraction algorithms combining the three
   channels. **POS is the better performer** on this project's own
   pooled data, with a proven mechanistic reason (§1.6, "isochromatic
   pulsation").
4. **FFT → heart rate** (Segment 4) — FFT the combined signal, find the
   dominant peak in the 0.7–4 Hz band, convert to bpm.
5. **SpO2 estimation** (Segment 5) — AC/DC ratio-of-ratios on Red/Blue
   (Blue substitutes for Infrared, a published approximation) → linear
   calibration (`SpO2 = A − B·R`, A=96.4763, B=−0.41595). **Honestly
   weak**: loses to a predict-the-training-mean baseline on every
   honest test — a real data limitation (too little physiological SpO2
   variance in a healthy cohort + a cross-camera Simpson's-paradox
   offset), not a code bug.
6. **Branch 2 morphology filtering** — `adaptiveHarmonicFilter.m` (ABPF,
   a real, verified port of Moço/Stuijk/de Haan, *Sci Rep* 8:8501,
   2018), wrapped as of Segment 14 (2026-09-13) by
   `harmonicFilterConfidenceGate.m` (production default): keep ABPF
   wherever its own notch confidence already clears a 0.3 bar,
   substitute `harmonicSelectiveGaussianFilter.m` (alpha=0.15) only
   where ABPF fails.
7. **Validation** (Segment 6) — leave-one-subject-out (LOSO)
   cross-validation (MAE, RMSE, Pearson r, Bland-Altman), plus, as of
   Segment 11 (2026-09-13), a ground-truth-free fidelity metric:
   **cross-ROI PLV**.
8. **Self-collected test set** — classmates on video with a real
   pulse-oximeter ground truth, held out from the public training data.
   **Not yet started.**
9. **Android app** — real-time on-device HR/SpO2 display, a faithful
   port of Branch 1, joined by Branch 2 as of Segment 19. Implemented
   and defense-ready.

The real orchestrator both branches run through is
`pipeline/estimateVitalsAndMorphology.m` (the older `estimateVitals.m`
stub is unused).

### 1.4 Datasets used, in the order they entered the project

- **UBFC-rPPG DATASET_1** (5 subjects, HR + SpO2 ground truth) — the
  first dataset used, throughout Segments 2–6.
- **UBFC-rPPG DATASET_2** (42 subjects, HR-only ground truth waveform,
  9 with a duplicate-timestamp data-quality issue → 33 usable) —
  extracted and first used in Segment 7 Task K (template-collapse
  diagnostic) and formally as Segment 14's held-out validation set
  (2026-09-13).
- **VIPL-HR** (Dr. Hu Han / Yunchi Zhang, ICT CAS; 107 subjects, up to 9
  scenarios × 4 sources each) — access requested and integrated via two
  new loader functions (`io/loadVIPLVideo.m`, `io/loadVIPLGroundTruth.m`)
  with **no change to any existing algorithm file** (§1.7). Brought the
  pooled HR validation set from 5 to 112 subjects. Its raw archive
  (47GB, 30 zip files) was later moved 2026-09-13 from
  `H:\EEE 312 project\...\VIPL-HR-V1\` to
  `I:\EEE 3-1\EEE 312\project\dataset\VIPL-HR-V1\` purely to free disk
  space — the working extracted subset the pipeline actually reads
  stays at `spandan/data/raw/VIPL-HR/`, untouched by that move.
- **VIPL-HR, further scenarios** — v2 (head motion, 20 subjects, first
  used Segment 18 2026-09-20), v3/v5/v7 source1, v1/v4/v6 source1,
  v1 source3 (RealSense), and the phone (source2) scenarios — pulled in
  progressively through Segments 22–26 to stress-test motion robustness
  and a candidate read-out change against unseen videos.
- **Hoffman finger-camera oximetry dataset**
  (`github.com/ubicomplab/oximetry-phone-cam-data`, 6 subjects, wide
  FiO2-driven SpO2 range 65–100%) — used once, in Segment 5, purely as
  a **code-correctness sanity check** for `ratioOfRatios.m`/
  `calibrateSpO2.m` on data with real oxygenation range; explicitly
  **not** evidence about facial-video SpO2 accuracy (different optical
  regime — finger + flash vs. ambient facial light).
- **PURE** — reserved, access request pending, never used.
- **Self-collected Bangladeshi set** — reserved for pipeline stage 7,
  **not started**. A prediction on record for when it happens: because
  isochromatic-pulsation leakage (§1.6) grows with skin-colour angle
  (~5–10° light skin, 30–50° dark skin per the literature), this set is
  predicted to sit at a **higher** angle than UBFC/VIPL and perform
  **worse** under the current pipeline than either public dataset does.

### 1.5 What was tried for the ROI itself, and why the simplest box won

Across four separate investigations (Segment 7 Tasks D/D2/H/I/J,
Segment 9's Android multi-region pilot, Segment 6 Task N's multi-region
MATLAB work), no ROI alternative to the plain axis-aligned Viola-Jones
box ever beat it on the notch-confidence metric:

| ROI variant | Result vs. baseline (4/5 UBFC pass) |
|---|---|
| Axis-aligned box (baseline) | **4/5 — best** |
| KLT-tracked landmark ROI (Task D/D2) | 2/5, then 0/5 under frozen cadence |
| Real MediaPipe FaceMesh polygon (Task H/I) | 0/5 (but bit-for-bit HR-identical to baseline) |
| Face-mesh + box-geometry hybrid (Task J) | 1/5 (partial recovery, still net-negative) |
| Multi-region (forehead+cheek) | Negligible Android fps cost (Segment 9), best region shifts by scenario, never ported |

A template-collapse diagnostic (Task K, 100-subject pool) found no
evidence that this is a shape-convergence artifact of the baseline box.
Net standing decision: **single forehead ROI, axis-aligned box** — the
evidence-based choice, not a default nobody re-checked.

### 1.6 The central mechanistic finding: isochromatic pulsation

Surfaced by Segment 10's literature search (2026-09-13) and
independently corroborated on this project's own data by Segment 10
Task 3 and Segment 11: Kaur, Lakshminarayanan & Saini (*Biomed. Opt.
Express* 17(7):3832, 2026) argue the standard rPPG model (chromatic
only — what CHROM/POS assume) is **incomplete**. A second
cardiac-frequency component, "isochromatic pulsation"
(ballistocardiographic skin-geometry modulation), lies along the skin's
mean reflectance direction q̂, carries the **majority** of cardiac-band
energy (median 69–83% across their cohorts), and **cannot be separated
by temporal filtering** since it shares the real signal's frequency.
This mechanistically explains this project's own measured phase
distortion, missing 2nd-harmonic energy, and independently predicts
POS > CHROM (POS's basis is orthogonal to [1,1,1] by construction;
CHROM's is not, so it leaks even at zero skin-colour angle — measured
median cardiac angle on this project's own data: 109.5°, VIPL 121.4°,
UBFC 97.8°, landing inside Kaur et al.'s own reported range).

Their fix, **cPACE**, was fully implemented across two segments
(Stage 1: Segment 11, 2026-09-13; Stages 2–3: Segment 15, 2026-09-14)
and evaluated honestly on this project's own 100-subject pool — it does
**not** beat production POS/CHROM here (this cohort is light-skinned,
low-motion — exactly the regime Kaur et al.'s own paper reports the
smallest gains for), so it stays an off-by-default option, not a
rejection of the underlying science. Waveform fidelity is genuinely
modest even after all this work — median ground-truth correlation
r=0.44–0.52 — and this is now understood mechanistically, not treated
as a bug to patch away.

### 1.7 VIPL-HR integration — the one real format surprise

Integrating VIPL-HR needed **two new I/O loader functions only** —
`io/loadVIPLVideo.m`, `io/loadVIPLGroundTruth.m` — with **zero changes**
to any of Segments 2–5's algorithm code, because
`extractROISignals.m`/`bandpassClean.m`/`chromCombine.m`/`posCombine.m`/
`fftHeartRate.m`/`ratioOfRatios.m`/`calibrateSpO2.m` all already take
plain numeric signals + a sampling rate, nothing UBFC-specific baked
in. VIPL-HR needed a genuinely different loader shape than UBFC because
every `(subject, scenario, source)` triple is its own independent
video with its own ground truth — there is no single "the video" per
subject the way UBFC has.

**The one real surprise**: every VIPL-HR video reports `FrameRate = 25`
from `VideoReader`, including sources whose own documentation claims
"~30 fps" — because the videos are re-encoded for storage and the
container's declared rate is a compression artifact, not real capture
timing (VIPL-HR's own `ReadMe.pdf` says so once you read past the
first paragraph). Real timing lives in each video's own `time.txt`,
recomputed as `(numTimestamps-1)/elapsed_seconds` whenever one exists —
measured disagreement up to **19–20%** for some recordings. The phone
camera (source2) ships **no** `time.txt` at all, a real, documented
accuracy limitation for that source specifically. This frame-rate bug,
uncaught, would have produced HR estimates ~19–20% off for every
affected VIPL video, silently — the single most consequential format
surprise in the whole integration.

Small-scale validation (3 subjects, before any larger batch): CHROM/POS
landed within 1–6 bpm of ground truth, consistently beating green-only
— the same pattern already established on UBFC.

---

## PART II — Complete chronological history

### 2.1 Segments 1–6 — building the core pipeline (dates not recorded)

*Everything in this section predates `SESSION_HANDOFF.md`'s creation
(2026-09-12) and carries no calendar date in any source document.*

**Segment 2 — Face ROI extraction.** `roi/extractROISignals.m`:
per-frame face detection (`vision.CascadeObjectDetector`, Viola-Jones),
a forehead crop taken as a fixed fraction of the detected face box,
spatial averaging of R/G/B pixel intensities across that crop per
frame → three 1-D signals. Explicit graceful-degradation path for a
frame where detection fails (reuses the last good box) rather than
erroring the whole clip. Team README + a full line-by-line teaching
document (with `::: intuition`/`::: pitfall`/`::: pausecheck` blocks)
were written alongside the code, establishing the documentation
pattern every later segment followed.

**Segment 3 — Filtering.** `filtering/detrendSignal.m` (cubic
polynomial detrend, removing slow baseline drift that isn't
physiological) and `filtering/bandpassClean.m` (Butterworth bandpass,
0.7–4 Hz, applied via `filtfilt` for genuine zero-phase filtering —
this single design choice is why Android's later phase-distortion
audit, Segment 7 Task H/6b, found Lapitan et al.'s causal-filter-delay
concern simply inapplicable by construction, avoiding up to ~450 ms of
group delay at band edges that a causal filter would have cost).

**Segment 4 — CHROM/POS + FFT heart rate.** `pulseextraction/
chromCombine.m` and `posCombine.m` (both signatures grew from their
original stubs to take raw + filtered traces, since the two
normalization schemes each need a real, pre-filter DC brightness
baseline that a post-filter signal's near-zero mean cannot supply) and
`heartrate/fftHeartRate.m` (masks the search to 0.7–4 Hz *before*
peak-picking, not after — the documented "beginner mistake" this
segment's own guideline explicitly warns against). Verified on the
first 3 UBFC subjects: CHROM/POS landed within 1–3 bpm of ground truth
while raw green-channel FFT missed by nearly 17 bpm on one subject — the
founding evidence for combining channels instead of trusting the
"best single channel" alone.

**Segment 5 — SpO2 ratio-of-ratios + calibration.** `spo2/
ratioOfRatios.m` (AC/DC per channel, Blue substituting for Infrared)
and `spo2/calibrateSpO2.m` (`polyfit`-based linear fit, fit/apply
modes). A 5-subject leave-one-out calibration attempt on UBFC DATASET_1
gave MAE 1.97 percentage points but a genuinely unstable fitted slope
sign across folds (too few training points) — flagged honestly as "a
calibration attempt," not a validated calibration. A separate Hoffman
finger-camera sanity check (wide 65–100% SpO2 range) confirmed the
*code* responds correctly across the physiologically expected range
(test MAE 8.56 points on 351 held-out windows, positive slope in the
correct direction) while explicitly not claiming anything about facial
accuracy.

**Segment 6 — Formal LOSO validation + refinements.** `validation/
computeMetrics.m` (MAE/RMSE/Pearson r, unit-agnostic), `blandAltman.m`
(bias + limits of agreement, dataset-colored), `runLOSO.m` (genuine
pooled leave-one-subject-out SpO2 refit across UBFC+VIPL together — HR
needed no such fold structure since `fftHeartRate.m` has no fitted
parameter to leak). First pooled result: HR (VIPL's 3 subjects only, at
that point) CHROM/POS MAE ~3.94 bpm, r=0.995; SpO2 pooled 8 subjects,
MAE 1.23 points. This segment also ran a long series of refinement
Tasks (L: phone-device evaluation; N: multi-region ROI; O: detrend/
adaptive-bandpass tuning; P: windowed harmonic-quality continuity; Q:
anchored continuity + 2-way region switching; R: phone-camera SpO2
centering) — most **not** ported forward this close to defense
(regression risk vs. runway), documented in
`android/docs/Defense_Readiness_Checklist.md`'s own standing decision;
Task R's finding (phone SpO2 needs no device-specific centering) *was*
adopted, live on Android.

**Segment 6 Task 5 — RAKF/Kalman smoothing, first pass.** Residual-
adaptive Kalman filtering (Debnath & Kim) tested against Task P/Q's
windowed methods — RAKF came last of six methods compared; simplest
methods (naive windowing, gating-only) won. This verdict was
re-confirmed twice more later in the project (a parameter sweep in
Segment 9, and the Android smoothing decision in Segment 16), never
overturned.

**VIPL-HR integration** (§1.7) — folded in during this early period,
unblocking the pooled 112-subject HR validation and the pooled 8-subject
SpO2 LOSO that Segment 6's own headline numbers depend on.

**Original Android app build.** A faithful Kotlin/CameraX/ML Kit port
of Branch 1 (HR via CHROM/POS+FFT, SpO2 via ratio-of-ratios +
calibration), real-time and on-device, verified on a physical Samsung
Galaxy A35 (the only device this project has ever tested on). Reached
a defense-ready state per `android/docs/
Defense_Readiness_Checklist.md` (7 checklist actions done, including a
16.1-minute continuous crash-free stability run) before the dated
record below begins — the exact date of that milestone is not recorded
in any source document, only that it predates 2026-09-12.

**Original iOS app build.** A Swift/UIKit/AVFoundation/Vision port of
the same Branch 1 pipeline, written and CI-built
(`.github/workflows/ios-build.yml`, macOS runner) **without access to a
Mac or a physical iPhone**. The algorithm core is CI-unit-tested; the
camera pipeline has never been verified on real hardware — stated as an
open risk in `ios/README.md`'s "Known risk areas," not silently
assumed to work.

### 2.2 Segments 7–9 — ROI experiments, timing fixes, denoising, and small pilots (dates not recorded, immediately pre-dating 2026-09-12)

- **Segment 7 Task F** — `pipeline/estimateVitalsAndMorphology.m` built
  as the real orchestrator chaining Branch 1 and Branch 2 off one
  shared ROI extraction; this is the function every later segment
  actually calls.
- **Segment 7 Tasks D/D2/H/I/J** — the ROI experiments summarized in
  §1.5 above (KLT, real face-mesh, hybrid) — all net-negative vs. the
  simple box.
- **Segment 7 Task K** — template-collapse diagnostic; no support
  found for the concern (§1.5).
- **Segment 7 Task B** — original notch quantification establishing
  the 4/5-UBFC-subjects-pass baseline for ABPF that every later Branch
  2 result is measured against.
- **Segment 8** — VIPL source2 (phone) FPS-mismatch root cause and fix
  (relabeling with a corrected constant fps; a real cubic-spline
  correction was later tested in the dated record and rejected, see
  2026-09-13 below); Android frame-skip throughput work began here.
- **Segment 9 "Field Guide" pilots** — early, small-sample explorations
  (Android multi-region ROI feasibility, an RAKF parameter sweep, a
  weighted region-switching rule) — all later formally closed on
  2026-09-13, see below.

### 2.3 The dated record — 2026-09-12 through 2026-09-20

*From here on, every entry below carries the date recorded in the
repo's own `SESSION_HANDOFF.md` changelog. This section condenses that
changelog into one continuous narrative; the changelog itself
(`spandan/SESSION_HANDOFF.md`, section "Changelog") remains the
authoritative, most granular record — this section points to it rather
than duplicating every number.*

**2026-09-12** — `SESSION_HANDOFF.md` created, seeded from a planning
conversation covering the face-mesh ROI experiments (Segment 7 Tasks
H/I/J), a 5-paper literature verification pass, and the Segment 8
source2 FPS finding. Same day: **Segment 7 Task J fixed** — the
"empty output" bug was a MATLAB↔Python (`py.*`) per-landmark round-trip
proxy-handle leak (468 landmarks × 2 attribute reads/frame as separate
calls), fixed by batching each frame's landmark extraction into one
Python-side list comprehension. Result: hybrid ROI partially confirms
the pixel-count hypothesis (1/5, up from face-mesh's 0/5) but stays
net-negative vs. baseline's 4/5. This closed the whole
baseline/KLT/mesh/hybrid ROI investigation line — no ROI variant ever
beat the simple axis-aligned box.

**2026-09-13** (the single busiest day in this project's dated
history — nine largely sequential sessions):

1. **Template-collapse diagnostic** (Segment 7 Task K, "Action 2")
   completed — no support for shape collapse; cross-subject rPPG
   correlation (0.93) actually *lower* than ground truth's own (0.97).
2. **Source2 timing fix** ("Action 3") — cubic-spline correction tested
   and rejected (source2 has no real per-frame timestamps to spline
   from); the already-adopted relabel fix stays (pooled MAE 13.83 bpm
   vs. spline's 18.74 vs. uncorrected 19.41). 4 of 12 affected subjects
   still regress under relabel for reasons investigated but not fully
   explained (best guess: source2's own uncorrectable capture jitter).
3. **DWT wavelet-shrinkage denoising** ("Action 4") ablated on the full
   112-subject pool — genuine improvement on every metric, both
   combiners (CHROM MAE 9.10→7.83 bpm, POS 8.68→7.22 bpm), 7/112
   subjects regress >10 bpm on CHROM even as the pool improves (an
   honest, on-record caveat). **Promoted to the default** in both
   MATLAB and Android the same day ("Action 7").
4. **RAKF/Kalman smoothing** ("Action 5") — re-tested head-to-head
   against Segment 6's windowed methods on the full 112-subject pool;
   confirmed worst of 6 methods (MAE 12.15, r=0.204).
5. **Android frame-skip re-measured for real** ("Action 6a") — a
   physical Galaxy A35 became available; real capture: **13.44 fps →
   21.40 fps** steady-state, a genuine ~1.6× speedup, reported plainly
   as short of a naive 3× projection.
6. **Phase-distortion assessment** ("Action 6b") — confirmed
   `BandpassFilter.kt` uses `filtfilt` (zero-phase), Lapitan et al.'s
   causal-filter concern inapplicable by construction; quantified the
   causal-filter delay this design avoids (~152 ms mean, up to ~450 ms
   at band edges).
7. **File reorganized** for a clean handoff (Active Work Queue emptied
   into a new Completed Work Archive) — no code changed.
8. **Three Field Guide pilots closed**, all small-sample and none
   adopted: Android multi-region ROI feasibility (negligible fps cost,
   promising, not scaled up); an RAKF parameter sweep using the paper's
   real R0/Q values for the first time (best combo: modest 8%
   improvement, confirms rather than overturns the "simpler wins"
   verdict); a weighted region-switching rule (small real win in one
   scenario, flat-to-slightly-worse in another — ambiguous).
9. **Segment 10 opened**: **Task 1**, a full rPPG-vs-ground-truth
   waveform fidelity audit across 100 subjects (0 failures) — six
   headline findings, the most important being that
   `notchDetectIEM.m`'s boolean output is a useless gate at pool scale
   (100/100 register "detected"; only ~26–28% clear this project's own
   0.3 confidence bar). **Task 2**, a solution literature search —
   surfaced the isochromatic-pulsation paper (§1.6) as the project's
   central mechanistic finding, after two initially-BLOCKED papers were
   retrieved and read in full. **Task 3**, four Tier-0 diagnostics — 3
   of 4 supported; the one that failed was the search's own headline
   candidate (a camera-frame-rate "ceiling" on notch detectability,
   ruled out by a two-cluster artifact once checked *within* a single
   dataset rather than pooled across UBFC+VIPL).
10. **Segment 11 Task 1** — cPACE Stage 1 implemented (gated,
    off-by-default): proven an exact algebraic no-op for POS, a modest
    real regression for CHROM; cross-ROI PLV promoted to a standing
    validation metric.
11. **Segment 12** — Task 1: `bandpassMorphology.m`'s existing `'mid'`
    mode evaluated (small, non-regressive win, not adopted as default,
    and not via the originally-hypothesized mechanism). Task 2:
    Harmonic-Selective Gaussian Filtering implemented — underperforms
    ABPF at the source paper's own parameter (alpha=0.5); a tuned
    alpha=0.15 beats ABPF on pooled metrics but with an unexplained
    17-subject severe-regression tail — neither adopted.
12. **Segment 13** — root-caused that 17-subject regression: every
    regressor was a subject ABPF *already* passed (a confidence-clip
    ceiling artifact, not a real accuracy loss). Built
    `harmonicFilterConfidenceGate.m` (keep ABPF where it passes,
    substitute Gaussian(0.15) only where it fails) — beats both
    ingredients on every metric with **zero severe regressions by
    construction** (pass rate 24%→47%). Also tested and explicitly
    warned against a "pick the highest self-reported confidence"
    multi-candidate variant — a demonstrated selection-bias artifact.
13. **Segment 14** — held-out validation on UBFC DATASET_2 (42
    subjects, 33 valid after excluding 9 with a real ground-truth
    duplicate-timestamp data-quality issue) replicated the gate's
    zero-severe-regression property exactly (pass rate 45%→58%).
    **Promoted `harmonicFilterConfidenceGate` to Branch 2's production
    default** (`opts.useConfidenceGate=true`). Confirmed Branch 2 had
    never been ported to Android (true at the time — corrected two
    days later, see Segment 19).

**2026-09-14** —

1. **Segment 15** — cPACE Stages 2–3 (eigenvector selection +
   homodyne normalization) implemented directly from the paper's main
   text and Supplement (two brief claims corrected against the primary
   source before coding). Honest result: the full 3-stage pipeline does
   not beat production POS/CHROM at any tested bandwidth (best pooled:
   8.66 bpm vs. production's 7.86–7.87), 24/100 subjects regress
   severely; a genuine cross-ROI PLV gain was measured anyway. Kept
   off-by-default.
2. **Segment 16** — Android, three tracks (HR display smoothing, SpO2
   audit, UI/UX). Started with no physical device (an AVD emulator
   never finished booting), a real Galaxy A35 became available
   partway through. Real on-device A/B capture: display-level rolling-
   median smoothing cut tick-to-tick jitter **17.46→5.49 bpm (−69%)**
   at zero accuracy cost, promoted to default; a second real bug (a
   5×-faster UI tick re-ingesting the same raw value into the smoothing
   window) was found and fixed via the same capture. A 2023+ literature
   search for a better SpO2 calibration found nothing independently
   validated to responsibly adopt — no calibration change made, said so
   plainly. UI redesigned into a vitals card with per-metric status
   pills; a real text-wrap bug was found and fixed via a live capture.
3. **Segment 17** — `scripts/run_spandan_interactive.m` rewired from a
   frozen Segment-7/8 snapshot (duplicated ~1200 lines of pipeline
   code) to call the real, current `matlab/src/` pipeline via a
   self-locating `addpath` bootstrap — it can no longer silently go
   stale, at the stated cost that the file can no longer be copied
   alone to a machine with no repo checkout. One real capability was
   flagged as dropped, not silently lost: a confidence-anchored
   polarity-selection heuristic for no-ground-truth clips, never ported
   into `matlab/src/morphology/fixPolarity.m` itself.

**2026-09-15/16** — Two Android workstreams, both real-on-device
verified:

1. **Segment 18 (Android)** — camera throughput/tracking revisit. A new
   gated optical-flow inter-detection tracker was built and measured:
   its own cost is negligible (<1 ms/frame) but end-to-end throughput
   regressed (~20fps→16.33fps) for a reason a controlling re-check
   could not pin on simple thermal drift — a real, unrounded-up
   **negative result**, kept off by default, root cause flagged as an
   open question needing profiling tools this session didn't have.
2. **Segment 19** — Branch 2 (waveform morphology) **ported to Android
   for the first time** — this project's first real, on-device-verified
   dicrotic-notch estimation running live on a phone.
   `MorphologyWaveformEstimator.kt` + 9 supporting files, a new
   "WAVEFORM MORPHOLOGY (BRANCH 2)" UI card, 49/49 unit tests passing,
   and a real ~40-second capture exercising both the WIDE/MID band
   fallback and the ABPF/Gaussian confidence-gate substitution live,
   zero crashes, Branch 1 confirmed unaffected on the same capture.

**2026-09-20** — the second busiest day in this project's dated
history, closing out a full investigation into motion robustness and
into whether every past "evaluated, not adopted" rejection was fair:

1. **Segment 18 (MATLAB colour-space)** — CIELab a\*/YCbCr Cb,Cr tested
   as single-channel pulse sources on 112 subjects plus a new
   20-subject VIPL v2 (head-motion) pool. a\* beats green (MAE
   14.98→9.53 bpm) but still loses to production CHROM/POS; under
   motion, every single channel tested collapses (green/Cb even go
   *negative* correlation). Not promoted — but it opened a real
   question about motion robustness.
2. **Segment 21** — literature search for motion-robust combiners
   (2SR, LGI, PBV, OMIT) specifically because of that motion collapse.
   Ranked LGI highest on published evidence (r=0.97 vs. POS's 0.56
   under head rotation in the source paper's own benchmark) but
   costliest to implement; 2SR cheapest; PBV needs camera calibration
   this project's design goal excludes; OMIT deprioritized (validated
   for compression robustness, not motion). A new
   `Literature_Review_Master.md` consolidates every paper this project
   has ever searched, for future sessions to check before re-searching.
3. **Segment 22** — implemented and evaluated both 2SR and LGI
   (projection stage only — its state-space tracker was not built,
   since the projection alone already lost). **Verdict: NO-GO for
   both.** A Tier-0 check first found production CHROM/POS had simply
   never been measured on the v2 motion pool before — once measured, it
   already beats both new combiners everywhere it matters (2SR: 21.3
   bpm on v2 motion vs. CHROM/POS's 8.85/10.57; LGI projection: 16.9
   bpm). This closed the motion-robustness line — nothing promoted.
4. **Segment 23** — a fairness audit: re-ran every one of the project's
   past "evaluated, not adopted" candidates in its *native or fully
   completed* form (windowed cPACE, CIELab with the source paper's own
   ROI+KLT+Lab pipeline, 2SR with a real skin mask, LGI with its own
   read-out + a real state-space tracker, a 19-combiner×6-read-out
   fairness matrix, de-tuned CHROM/POS, RAKF with the paper's literal
   exponent form) — asking whether CHROM/POS's unbroken record was real
   robustness or an artifact of under-implemented challengers. **Answer:
   mostly real robustness**, plus a real-but-modest tuning/read-out
   asymmetry that changes no ranking. One genuinely new thing fell out
   of this: a windowed/tracked **read-out** stage (not a new combiner —
   just how the final bpm is picked off an existing CHROM/POS signal)
   that lowers pooled MAE by 1.1–1.5 bpm — flagged explicitly as
   needing held-out validation before being taken seriously.
5. **Segment 24** — held-out validation of that read-out candidate.
   Found UBFC-D2 was not actually untouched (Segment 14 used it) and
   VIPL has no unused *subjects* left, so the held-out set became
   unused VIPL *videos* (different scenarios of already-seen people).
   The as-tested candidate did **not** clearly beat production. A
   *different*, incidental variant — the same windowing but matched to
   production's own 0.7–4 Hz band instead of a narrower band the
   original test happened to use — did clearly win, but that band-match
   was only ever a control condition, never properly tested on its own:
   flagged as **candidate, not validated**, not a result.
6. **Segment 25** — replicated that band-matched candidate on a genuine
   held-out set: holds on the main 112-subject pool (no regression);
   clearly replicates on two unseen VIPL scenarios (v3/v5, largely
   driven by v5's dark/low-fps conditions); does **not** replicate
   across an unseen *camera* (v1 RealSense). Verdict, corrected the
   next day for precision: **candidate, validated — but only under
   degraded-signal conditions** (dark/low-fps), not shown to generalize
   to normal lighting or across devices. Still not adopted.
7. **Segment 26** — tried to isolate *why* the read-out helps under
   degraded conditions. Tested median-vs-mean window aggregation (not
   the mechanism — mean recovers ~86–100% of the same gain); tested
   correlation against cached signal-quality proxies (frame rate,
   dropped-frame fraction, ROI brightness — none of them track the
   gain). **Mechanism remains unknown** after two dedicated attempts.
8. **Segment 27** — a third mechanism-isolation attempt was designed
   but **deliberately not run** — after two negative attempts with no
   pending production decision riding on the answer, further
   mechanism-hunting was judged to have hit diminishing returns.
   Final resting verdict on the read-out candidate: **validated for
   degraded-signal conditions only, mechanism unresolved, not
   adopted**, no further investigation planned absent new evidence.
9. **Wavelet-denoise orchestrator fix** — found and fixed the same day:
   the 2026-09-13 wavelet-denoising promotion had been wired into the
   two batch scripts but never into `pipeline/
   estimateVitalsAndMorphology.m`, the orchestrator every other caller
   actually uses. Fixed (`opts.useWaveletDenoise`, default true,
   applied immediately before detrending); verified the fix changes
   nothing about the frozen HR numbers already on record (112/112
   subjects already matched wavelet-on values). Found and reconciled a
   related splice artifact in the GREEN-channel column of the pooled
   metrics CSV (a leftover from the original 2026-09-13 promotion that
   was never fully reconciled — no verdict in the project has ever
   rested on the GREEN column, so nothing downstream was affected).
   Pinned the SpO2 path to the pre-wavelet signal chain specifically
   (since `calibrateSpO2`'s coefficients were fit on pre-wavelet R
   values, and re-fitting was out of scope) to avoid a train/serve
   mismatch. Whether wavelet denoising itself helps or hurts SpO2, and
   how Branch 2's notch detection behaves under it, are both
   pre-registered but deliberately **not yet run** — see Segment 27's
   preregistration doc.

**Net effect of the entire 2026-09-20 investigation (Segments 18, 21–27,
wavelet fix): the production pipeline is functionally unchanged.**
Nothing new was promoted to default; one internal wiring bug was fixed
with no change to any already-published number; one genuinely
promising read-out idea is now on record as validated-but-narrow and
explicitly not adopted, rather than either quietly dropped or
oversold.

---

## PART III — Reference (current as of 2026-09-20)

### 3.1 Repo folder structure

```
spandan/
  SESSION_HANDOFF.md       <- living, actively-maintained entry point (read first once you have the repo)
  README.md                 <- architecture + folder reference + download/install instructions
  SPANDAN_MASTER_HANDOFF.md <- this file
  LICENSE
  matlab/
    startup.m                <- adds all src/ subfolders to the MATLAB path — run this first
    src/
      io/                     - loadUBFCVideo.m, loadGroundTruth.m, loadVIPLVideo.m, loadVIPLGroundTruth.m
      roi/                    - extractROISignals.m (multi-region since Task N), extractROISignalsLab.m (Segment 18), extractROICovariance.m (Segment 22)
      filtering/              - bandpassClean.m, detrendSignal.m, waveletDenoise.m (DEFAULT ON), resampleSource2CubicSpline.m
      pulseextraction/        - chromCombine.m, posCombine.m, cpaceProjection.m / cpaceEigenExtract.m / cpaceHomodyneNormalize.m (all gated, NOT default), spatialSubspaceRotation.m, lgiProjection.m (Segment 22, NOT default)
      heartrate/               - fftHeartRate.m, windowedHeartRate.m
      spo2/                    - ratioOfRatios.m, calibrateSpO2.m (ported live to Android)
      morphology/              - adaptiveHarmonicFilter.m (ABPF), harmonicSelectiveGaussianFilter.m (gated), harmonicFilterConfidenceGate.m (DEFAULT ON), notchDetectIEM.m, fixPolarity.m, bandpassMorphology.m, ensembleAverageBeats.m
      validation/               - runLOSO.m, computeMetrics.m, blandAltman.m, computeCrossROIPLV.m (adopted standing metric), residualAdaptiveKalmanHR.m (rejected), computeRegionSwitchingEstimateWeighted.m (pilot)
      pipeline/                 - estimateVitalsAndMorphology.m <- the REAL orchestrator; estimateVitals.m is an unused stub
    scripts/                    - run_pipeline_demo.m, batch_process_dataset.m, run_spandan_interactive.m (self-locating single-file demo, Segment 17), plus one run_segmentN_taskM_*.m per investigation
    docs/                       - Spandan_Final_Pipeline_Report.md, DATA_FORMAT.md, SpO2_Final_Report_Section.md, SpO2_Final_Calibration_Spec.md, Literature_Review_Master.md, Fairness_Audit_Summary_Segments23-26.md, one Segment*_Task_*.md per investigation
    experiments/                 - segment2{2..7}_*/ (motion-robust combiner + fairness audit + read-out investigation, each with its own PREREGISTRATION.md + REPORT.md)
    tests/                       - sanity_test.m, segment7_task_f_regression_test.m (3-part regression check, passing)
  data/                          <- git-ignored, not in a fresh clone
    raw/UBFC-rPPG/DATASET_1/, DATASET_2/
    raw/VIPL-HR/                  <- working extracted subset the pipeline reads
    raw/PURE/                     <- empty, reserved
    processed/, self_collected/
  results/                        <- git-ignored: figures/, metrics/, logs/
  docs/                            <- cross-cutting: DATA_FORMAT.md, VIPL/Hoffman data-format notes, Android HR-switching port spec
  android/                         <- Kotlin/CameraX/ML Kit app, fully implemented, defense-ready, Branch 2 included since Segment 19
    README.md, docs/                - Defense_Readiness_Checklist.md, Segment*_Task_*.md
    app/                             <- Gradle Android project
  ios/                             <- Swift/UIKit/AVFoundation/Vision port; algorithm core CI-tested, camera pipeline NOT verified on real hardware
```

### 3.2 Current production defaults

| Setting | Default | Where |
|---|---|---|
| Wavelet denoising | **ON** | MATLAB batch scripts + orchestrator (fixed 2026-09-20); `WaveletDenoise.kt` on Android |
| Branch 2 confidence gate | **ON** (`opts.useConfidenceGate=true`) | `pipeline/estimateVitalsAndMorphology.m` since Segment 14; ported to Android Segment 19 |
| Bandpass mode (Branch 2) | **`wide`** (0.5–8 Hz) | `'mid'` (0.6–6 Hz) validated as a low-risk non-default alternative |
| cPACE (any stage) | **OFF**, all stages | Proven no-op for POS, real regression for CHROM/full-pipeline at every setting tested |
| Motion-robust combiners (2SR, LGI) | **Rejected**, not shipped | Segment 22 — neither beats production CHROM/POS anywhere it matters |
| Windowed/tracked read-out | **Not adopted**, on record as a validated-narrow candidate | Segments 23–27 — helps only under dark/low-fps conditions, mechanism unresolved |
| RAKF/Kalman HR smoothing | **Rejected** | Worst of 6 methods, tested 3 separate times, never won |
| Android HR display smoothing | **ON** | `DisplaySmoother.kt` — display-layer-only rolling median, cut jitter 17.46→5.49 bpm on real device |
| Android SpO2 perfusion-index exposure | **ON**, informational only | `LiveSpo2Estimator.kt` |
| Multi-region (cheek) ROI | **OFF**, experimental only | Negligible Android fps cost, never wired live |
| Android motion tracking (Segment 18) | **OFF** | Negligible own cost but a real, unexplained throughput regression when enabled |

### 3.3 Validated results (headline numbers, 2026-09-20)

**Branch 1, pooled 112 subjects (5 UBFC-D1 + 107 VIPL), current defaults:**

| Combiner | MAE (bpm) | RMSE (bpm) | Pearson r |
|---|---|---|---|
| CHROM | 7.83 | 11.87 | 0.53 |
| POS | **7.22** | **10.85** | **0.62** |

**Branch 2 (waveform morphology), current production (confidence
gate):** pass rate (subjects clearing the 0.3 notch-confidence bar)
**24%→47%** on the 100-subject audit pool and **45%→58%** on the
33-subject held-out UBFC-D2 set, **zero severe regressions by
construction** across all 133 subjects tested — the strongest single
validated result in the whole Segment 10–19 investigation line.

**SpO2**: calibration finalized (A=96.4763, B=−0.41595) but honestly
reported as weak — stratified LOSO MAE 1.908 (VIPL), does not beat a
trivial "guess the training mean" baseline within the narrow observed
range. Live and working on Android.

**Motion robustness (v2 head-motion pool, 20 subjects)**: production
CHROM/POS, MAE 8.85/10.57 bpm, r 0.54/0.31 — beats every colour-space
or motion-robust-combiner alternative tested against it (Segments 18,
22).

**Android throughput**: 13.44 fps → 21.40 fps steady-state after the
every-Nth-frame detection-skip optimization (Segment 8/13); Branch 2
now also runs live, real fs observed 14.16–22.08 Hz during actual
on-device use (Segment 19).

### 3.4 Standing findings & decisions

- **Isochromatic pulsation** (§1.6) is this project's central
  mechanistic finding, explaining POS>CHROM, the modest waveform
  fidelity ceiling, and motivating (but not yet winning) the cPACE
  line.
- **A pooled-metrics-only view can hide catastrophic per-subject
  failures** — seen independently at least three times (Segment 12's
  17-subject Gaussian-filter regression a pooled median hid; Segment
  15's ~40 bpm per-subject cPACE swings invisible in a pooled 0.99–2.88
  bpm number; Segment 10's harmonic-confusion detector reproducing a
  known single-subject anecdote a pool average would never surface).
  Always check per-subject before adopting anything.
- **Evaluation-honesty principles this project holds itself to**:
  small-sample accuracy inflation is real (HR r=0.824 at N=18 → r=0.314
  at N=112); same-subject train/test splits inflate accuracy; real
  datasets beat synthetic ones; data limitations get documented, not
  hidden (SpO2's weakness is on the record, not buried); cross-camera/
  cross-session confounds (Simpson's paradox) must be checked for;
  every external citation carries an explicit VERIFIED-FULL /
  VERIFIED-INDEX / BLOCKED marker.
- **CHROM/POS's unbroken record is mostly real robustness**, not an
  artifact of weaker challenger implementations — the one Segment 23
  fairness audit built to test this directly confirmed it, while also
  surfacing the one still-open read-out lead (§2.3, Segments 23–27).
- **The confidence-gated Branch 2 filter is this project's
  single best-supported result** — validated with zero severe
  regressions across 133 subjects on two independently-composed pools,
  the only Segment 10–19 change to reach production on both MATLAB and
  Android.

### 3.5 Do-not-touch list (validated, regression risk only)

`pulseextraction/chromCombine.m`, `pulseextraction/posCombine.m`,
`heartrate/fftHeartRate.m`, `morphology/adaptiveHarmonicFilter.m`'s
existing behavior, `filtering/waveletDenoise.m`'s internals,
`morphology/harmonicFilterConfidenceGate.m`'s own gating logic
(0.3-bar/ABPF-primary/Gaussian-0.15-fallback design, validated on 133
subjects — re-tune only with a new, explicit ablation),
`android/.../signal/WaveletDenoise.kt`'s internals, `FaceAnalyzer.kt`'s
frame-skip logic, `BandpassFilter.kt`'s `filtfilt` implementation,
anything marked done in `android/docs/Defense_Readiness_Checklist.md`
— except where a specific task explicitly adds an alternative
alongside it.

### 3.6 Flagged follow-ups not yet done

- Port `chooseHeuristicPolarityByNotchConfidence` (confidence-anchored
  polarity selection for no-ground-truth clips) into
  `morphology/fixPolarity.m` itself, or add an `opts.polarityMethod`
  hook — currently falls back to the plain skewness heuristic, which
  has a documented bias.
- Re-run `scripts/run_spandan_interactive.m` once inside real MATLAB to
  confirm the Segment 17 rewrite works end-to-end (only structurally
  verified so far).
- Investigate the Harmonic-Selective Gaussian Filter's regression-tail
  subjects further (per-subject alpha, tighter alpha + more harmonics)
  — the most promising open lead from Segment 10–13, never pursued past
  the confidence-gate fix.
- Isolate the Segment 18 (Android) motion-tracker throughput regression
  with real heap/allocation profiling — currently an open question.
- Isolate the mechanism behind Segments 24–26's degraded-condition
  read-out gain — two dedicated attempts came back negative; a third
  was designed (Segment 27) but deliberately not run.
- A dedicated multi-minute SpO2 stability re-run on Android.
- The self-collected Bangladeshi test set (pipeline stage 7) — not
  started; remember the skin-colour-angle prediction in §1.4/§1.6
  before collecting.
- Whether wavelet denoising itself helps or hurts SpO2, and how Branch
  2's notch detection behaves under it — both pre-registered
  (`experiments/segment27_branch2_wavelet_evaluation/PREREGISTRATION.md`)
  but not run.

### 3.7 Working preferences (how Abrar wants this project run)

- Casual, direct communication; prefers clean, complete,
  **submission-ready** deliverables over outlines or drafts.
- Prefers direct execution over confirmation gates.
- **Code-ownership awareness is an explicit, standing concern** — he
  must defend this project live to a supervisor, so code should be
  explained/onboarded as it's built, not just delivered.
- When teaching any DSP concept related to this project (however
  small): act as an outstanding EEE 312 professor — gauge his current
  understanding first, teach at his level, check the explanation lands
  before moving on; intuition/analogies before formulas, concrete
  before abstract, one idea per check.
- Update the relevant project docs (`SESSION_HANDOFF.md` first) **as
  part of finishing any session that changes project state**, not as
  an afterthought.
- Uses Claude Code separately for day-to-day work; wants paste-ready
  prompts for it, not prose descriptions.
- Values a teaching/presentation style that leans on real images/photos
  with captions rather than text alone, generally, not just for this
  project.

### 3.8 Contacts

| Who | Role |
|---|---|
| Prof. Dr. Md. Kamrul Hasan | EEE 312 supervisor |
| Ramis Isfar | Spandan group collaborator |
| Bayezid Rahman | Spandan group collaborator |
| Lubaba Tasnia Khan | Spandan group collaborator |
| Dr. Hu Han (hanhu@ict.ac.cn) | VIPL-HR dataset maintainer |
| Yunchi Zhang (zhangyunchi19@mails.ucas.ac.cn) | VIPL-HR download logistics |

---

*End of master history. For anything not covered here in full detail,
the repo's own `SESSION_HANDOFF.md` (task-by-task changelog, every
entry dated back to 2026-09-12) and the per-segment
`docs/Segment*_Task_*.md` / `experiments/segmentNN_*/REPORT.md` files
are the ground truth — this document summarizes and points to them, it
does not replace them.*
