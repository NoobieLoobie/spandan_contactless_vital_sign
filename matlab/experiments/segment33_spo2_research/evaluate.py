import numpy as np, pandas as pd, sys
from scipy.stats import pearsonr, spearmanr
from sklearn.linear_model import Ridge, LinearRegression
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import make_pipeline

rng = np.random.default_rng(0)
fn = sys.argv[1] if len(sys.argv) > 1 else "feat_clip.csv"
df = pd.read_csv(fn).dropna(subset=["spo2"])
df = df[np.isfinite(df.select_dtypes("number")).all(axis=1)]
for c in [c for c in df.columns if c.startswith("RoR_")]:
    df["log_" + c] = np.log(df[c].clip(1e-6))

SINGLE = [c for c in df.columns if c.startswith("log_RoR_")]
MULTI = {
    "tian6_nabp": ["NABP_R", "NABP_G", "NABP_B", "RoR_RG_nabp", "RoR_RB_nabp", "log_RoR_RB_nabp"],
    "tian6_proj": ["PROJ_R", "PROJ_G", "PROJ_B", "log_RoR_RG_proj", "log_RoR_RB_proj"],
    "proj+dc": ["log_RoR_RG_proj", "log_RoR_RB_proj", "DC_RB", "DC_RG"],
    "all_ratios": [c for c in df.columns if c.startswith("log_RoR_")] + ["DC_RB", "DC_RG"],
}


def loso(d, cols, model):
    pred = np.full(len(d), np.nan)
    for s in d.subj.unique():
        te = (d.subj == s).values
        tr = ~te
        if cols is None:
            pred[te] = d.spo2.values[tr].mean()
        else:
            m = model()
            m.fit(d.loc[tr, cols].values, d.spo2.values[tr])
            pred[te] = m.predict(d.loc[te, cols].values)
    e = pred - d.spo2.values
    r = pearsonr(pred, d.spo2)[0] if np.std(pred) > 0 else np.nan
    return np.mean(abs(e)), np.sqrt(np.mean(e ** 2)), r, pred


def within_subject_r(d, col):
    """Correlation after removing each subject's (and device's) own mean -> can the
    feature TRACK SpO2 changes inside one person, i.e. after personal calibration?"""
    g = d.groupby(["subj", "src"])
    keep = g.spo2.transform("count") >= 2
    x = (d[col] - g[col].transform("mean"))[keep]
    y = (d.spo2 - g.spo2.transform("mean"))[keep]
    if len(x) < 10:
        return np.nan, np.nan, 0
    r, p = spearmanr(x, y)
    return r, p, len(x)


rows = []
for scope, d in [("all", df), ("webcam(src1)", df[df.src == "source1"]), ("phone(src2)", df[df.src == "source2"])]:
    d = d.reset_index(drop=True)
    mae, rmse, r, _ = loso(d, None, None)
    rows.append(dict(scope=scope, method="train-mean baseline", N=len(d), MAE=mae, RMSE=rmse, r=r))
    for c in SINGLE:
        mae, rmse, r, _ = loso(d, [c], LinearRegression)
        rs, ps = spearmanr(d[c], d.spo2)
        wr, wp, wn = within_subject_r(d, c)
        rows.append(dict(scope=scope, method=c, N=len(d), MAE=mae, RMSE=rmse, r=r,
                         raw_spearman=rs, raw_p=ps, within_subj_rho=wr, within_p=wp, within_n=wn))
    for name, cols in MULTI.items():
        mae, rmse, r, _ = loso(d, cols, lambda: make_pipeline(StandardScaler(), Ridge(alpha=10.0)))
        rows.append(dict(scope=scope, method="ridge:" + name, N=len(d), MAE=mae, RMSE=rmse, r=r))
res = pd.DataFrame(rows)
pd.set_option("display.width", 220)
print(res.round(4).to_string(index=False))
res.to_csv(fn.replace("feat_", "eval_"), index=False)
