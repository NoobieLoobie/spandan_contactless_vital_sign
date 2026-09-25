"""Segment 33 supplementary analyses (reproduces every number quoted in the doc
that is not in eval_clip.csv). Run after run_extract.py 0 and run_extract.py 10."""
import glob, os, numpy as np, pandas as pd
from features import GT
from scipy.stats import spearmanr

out = []
# 1. Label resolution of VIPL SpO2 ground truth
v = [np.loadtxt(f, skiprows=1, delimiter=",", ndmin=1) for f in glob.glob(os.path.join(GT, "*", "*", "*", "gt_SpO2.csv"))]
a = np.concatenate(v); ok = a[(a >= 80) & (a <= 100)]
out.append(("label_files", len(v))); out.append(("label_samples", len(a)))
out.append(("label_integer_fraction", float(np.mean(np.isclose(ok, np.round(ok))))))
out.append(("label_frac_in_95_99", float(np.mean((ok >= 95) & (ok <= 99)))))
out.append(("label_out_of_range_samples", int(np.sum((a < 80) | (a > 100)))))

d = pd.read_csv("feat_clip.csv").dropna(subset=["spo2"])
out.append(("clip_N", len(d))); out.append(("clip_subjects", d.subj.nunique()))
out.append(("clip_spo2_sd", d.spo2.std()))

# 2. Estimator precision: within-clip jitter of ln(RoR) over 10 s windows
w = pd.read_csv("feat_10.csv").dropna(subset=["spo2"])
w["vid"] = w.subj + "_" + w.ver + "_" + w.src
for c in [c for c in w.columns if c.startswith("RoR_")]:
    lg = np.log(w[c].clip(1e-6))
    out.append((f"withinclip_sd_ln_{c}", lg.groupby(w.vid).std().median()))
    out.append((f"betweenclip_sd_ln_{c}", lg.groupby(w.vid).mean().std()))
g = w.groupby("vid"); swing = g.spo2.transform(lambda s: s.max() - s.min())
dd = w[swing >= 2]
out.append(("clips_with_2pct_swing", dd.vid.nunique()))
for c in [c for c in w.columns if c.startswith("RoR_")]:
    x = np.log(dd[c].clip(1e-6)); x = x - x.groupby(dd.vid).transform("mean")
    y = dd.spo2 - dd.groupby("vid").spo2.transform("mean")
    r, p = spearmanr(x, y)
    out.append((f"withinclip_rho_{c}", r)); out.append((f"withinclip_p_{c}", p))

# 3. What drives the R offsets: same person + same moment, two cameras
A = d[(d.ver == "v1") & (d.src == "source1")].set_index("subj")
B = d[(d.ver == "v1") & (d.src == "source2")].set_index("subj")
j = A.join(B, rsuffix="_ph", how="inner")
out.append(("paired_webcam_phone_N", len(j)))
out.append(("paired_rho_lnR", spearmanr(np.log(j.RoR_RB_prod), np.log(j.RoR_RB_prod_ph))[0]))
out.append(("paired_mean_lnR_phone_minus_webcam", float((np.log(j.RoR_RB_prod_ph) - np.log(j.RoR_RB_prod)).mean())))
out.append(("paired_rho_spo2_vs_lnR_webcam", spearmanr(np.log(j.RoR_RB_prod), j.spo2)[0]))

pd.DataFrame(out, columns=["quantity", "value"]).to_csv("analysis_summary.csv", index=False)
print(pd.DataFrame(out, columns=["quantity", "value"]).to_string(index=False))
