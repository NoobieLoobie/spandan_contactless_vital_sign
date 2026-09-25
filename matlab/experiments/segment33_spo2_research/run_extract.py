import numpy as np, pandas as pd, sys
from features import iter_vipl, features_for_segment, load_vipl_spo2

WIN_S = float(sys.argv[1]) if len(sys.argv) > 1 else 0  # 0 = whole clip
STEP_S = 5.0
rows = []
for subj, ver, src, R, G, B, fs in iter_vipl():
    gt = load_vipl_spo2(subj, ver, src)
    if gt is None or len(gt) == 0:
        continue
    dur = len(R) / fs
    t_gt = np.arange(len(gt)) * (dur / len(gt))  # GT resampled onto clip duration
    segs = [(0, len(R))] if WIN_S == 0 else [
        (int(s * fs), int((s + WIN_S) * fs)) for s in np.arange(0, dur - WIN_S + 1e-9, STEP_S)]
    for a, b in segs:
        if b - a < 8 * fs:
            continue
        f = features_for_segment(R[a:b], G[a:b], B[a:b], fs)
        if f is None:
            continue
        m = (t_gt >= a / fs) & (t_gt < b / fs)
        g = gt[m] if m.any() else gt
        valid = g[(g >= 80) & (g <= 100)]
        f.update(subj=subj, ver=ver, src=src, fs=fs, t0=a / fs,
                 spo2=valid.mean() if len(valid) >= max(3, 0.6 * len(g)) else np.nan,
                 spo2_std=valid.std() if len(valid) else np.nan,
                 gt_raw_min=g.min(), gt_raw_max=g.max())
        rows.append(f)
df = pd.DataFrame(rows)
out = f"feat_{'clip' if WIN_S == 0 else int(WIN_S)}.csv"
df.to_csv(out, index=False)
print(out, df.shape, df.spo2.notna().sum())
