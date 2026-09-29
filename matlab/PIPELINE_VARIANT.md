# This is the ML/DL-anatomy-ROI pipeline

This directory is one of **two parallel MATLAB pipeline copies**, split 2026-09-29 after
the Own Dataset evaluation (`docs/Segment36_Own_Dataset_Evaluation.md`) found the anatomy
ROI's benefit did not replicate cleanly on an independent dataset (POS accuracy notably
regressed there, even though the original VIPL/UBFC evidence for promoting it was real and
Holm-significant — see `docs/Segment35_MediaPipe_Anatomy_ROI.md`). Rather than pick one
verdict and delete the other, both pipelines are kept side by side so either can be run,
demoed, or compared directly.

- **This directory (`matlab/`)**: `opts.useAnatomyROI` defaults to `true` everywhere
  (`pipeline/estimateVitalsAndMorphology.m`, `scripts/run_vipl_integration_batch.m`,
  `scripts/run_segment2_roi_batch.m`, `scripts/run_spandan_interactive_anatomy_roi.m`,
  `scripts/run_spandan_full_demo.m`). Uses `roi/faceMeshAnatomyROIExtraction.m`
  (MediaPipe FaceMesh landmarks, forehead + both malar/cheekbone regions) as the ROI stage.
  **Requires a working MediaPipe Python install** and
  `pyenv('ExecutionMode','OutOfProcess')` (auto-set if not yet loaded this MATLAB session).
- **Sibling directory (`../matlab_classical/`)**: the same codebase, with
  `opts.useAnatomyROI` forced `false` in those same five files. Uses the original
  `roi/extractROISignals.m` plain face-box ROI. **No MediaPipe/Python dependency** —
  standard MATLAB toolboxes only (Image Processing, Computer Vision, Signal Processing,
  Wavelet).

Everything else (Branch 1 CHROM/POS/HR, Branch 2 morphology/notch, SpO2, wavelet denoising,
the confidence gate) is byte-identical between the two copies — this is a fork of a single
prior codebase, not two independently-maintained pipelines, and the only intended
difference is the ROI-extraction stage. **A future fix to a shared function (e.g.
`chromCombine.m`) must be applied to BOTH copies by hand** — there is no automated sync;
this is the same accepted "duplication + manual re-diff discipline" tradeoff
`scripts/run_spandan_interactive_anatomy_roi.m`'s own DESIGN HISTORY note (shared with
`matlab_classical/scripts/run_spandan_interactive_classical.m`, the same file's sibling
copy) has used since Segment 17.

**Shared, not duplicated**: `data/raw/`, `data/processed/`, and `results/` live at the
repo root (`../data/`, `../results/`), one level above both `matlab/` and
`matlab_classical/` — both pipelines' `projectRoot` resolution lands on the same repo root
regardless of which directory a script runs from, so raw datasets and cached ROI traces
are shared automatically (cache filenames are already namespaced by ROI type, e.g.
`<id>_rgb_traces.mat` vs `<id>_meshanatomy_rgb_traces.mat`, so no collision there).
**Caveat**: some `results/metrics/*.csv` OUTPUT filenames are NOT namespaced by pipeline
variant (e.g. `segment4_hr_summary_vipl.csv`) — running the same batch script from both
directories back to back will overwrite that file with whichever ran last. Move/rename an
output file first if you need to keep both variants' results side by side.
