# Segment 33 — SpO2 offline research scripts (Python 3, numpy/scipy/pandas/scikit-learn)

Offline analysis only; nothing here is called by the production MATLAB/Android/iOS code.
Full write-up: `matlab/docs/Segment33_SpO2_Research_and_New_Pipeline.md`.

Inputs (defaults resolve relative to the repo; override with env vars):
- `SPANDAN_TRACES`   -> `data/processed/` (cached `*_rgb_traces.mat`, same ROI traces production uses)
- `SPANDAN_VIPL_RAW` -> `data/raw/VIPL-HR/` (per-video `gt_SpO2.csv`)

Run from this folder:
```
python run_extract.py 0     # whole-clip features  -> feat_clip.csv (554 clips with valid SpO2 GT)
python run_extract.py 10    # 10 s windows, 5 s step -> feat_10.csv
python evaluate.py feat_clip.csv   # subject-grouped LOSO vs train-mean baseline -> eval_clip.csv
python analysis.py          # label resolution, estimator jitter, webcam-vs-phone offsets -> analysis_summary.csv
```
`results/` holds the outputs produced on 2026-09-25. Parity: `features.py`'s `RoR_RB_prod`
reproduces `results/metrics/segment5_vipl_calibration.csv` R values to ~0.1-0.3 %.

`CLAUDE_CODE_PROMPT_Segment34.md` is the paste-ready prompt for the next (on-device) segment.
