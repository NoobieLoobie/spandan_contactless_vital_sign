"""Segment 34 HR regression analysis: auto vs locked/linear capture, bracketed
A-B-A-B on the same person in one session (Galaxy A35, 2026-09-25).

Inputs per capture: the calibration CSV the app wrote (adb pull) and the
RealHeartRateEstimator logcat lines extracted from that capture's window.
Usage: python analyze_hr_regression.py <dir>
"""
import csv
import re
import statistics as st
import sys
from pathlib import Path

CAPTURES = [
    # label, mode, oximeter PR range reported by the subject during that minute
    ("c1_auto", "auto", (72, 76)),
    ("c2_locked", "locked", (71, 75)),
    ("c3_auto", "auto", (77, 84)),
    ("c4_locked", "locked", (76, 81)),
]
HR_RE = re.compile(r"HR_chrom=([\d.]+)bpm\s+HR_pos=([\d.]+)bpm.*displayed=(\w+) \(([\d.]+)bpm\)")


def load_csv(path):
    header, rows, events = {}, [], []
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    cols = None
    for line in lines:
        if line.startswith("# "):
            k, _, v = line[2:].partition("=")
            header[k] = v
        elif line.startswith("EVENT,"):
            events.append(line)
        elif cols is None:
            cols = line.split(",")
        else:
            rows.append(dict(zip(cols, line.split(","))))
    return header, rows, events


def fnum(x):
    return float(x) if x not in ("", None) else None


def analyze(d, label, mode, pr):
    header, rows, _ = load_csv(d / f"{label}.csv")
    ts = [int(r["timestamp_ns"]) for r in rows]
    dur = (ts[-1] - ts[0]) / 1e9
    dts = [(b - a) / 1e6 for a, b in zip(ts, ts[1:])]
    med_dt = st.median(dts)
    fps = (len(ts) - 1) / dur

    exp = sorted({r["exposure_ns"] for r in rows})
    iso = sorted({r["iso"] for r in rows})
    tone = sorted({r["tonemap_mode"] for r in rows})
    lock = sorted({r["lock_state"] for r in rows})
    R = [float(r["R_mean"]) for r in rows]
    G = [float(r["G_mean"]) for r in rows]
    B = [float(r["B_mean"]) for r in rows]
    clip = sum(int(r["clipped_px"]) for r in rows) / max(1, sum(int(r["sampled_px"]) for r in rows))
    pir = [fnum(r["pi_red"]) for r in rows if fnum(r["pi_red"]) is not None]
    pib = [fnum(r["pi_blue"]) for r in rows if fnum(r["pi_blue"]) is not None]
    spo2 = [fnum(r["spo2_current_displayed"]) for r in rows if fnum(r["spo2_current_displayed"]) is not None]

    # HR per 1 Hz recompute, from logcat (raw switched value, before display smoothing)
    hr, chrom, pos = [], [], []
    for line in open(d / f"logcat_{label}.txt", encoding="utf-8", errors="replace"):
        if "RealHeartRateEstimator" in line:
            m = HR_RE.search(line)
            if m:
                chrom.append(float(m[1])); pos.append(float(m[2])); hr.append(float(m[4]))
    jumps = [abs(b - a) for a, b in zip(hr, hr[1:])]
    disp = [fnum(r["hr_bpm_current"]) for r in rows if fnum(r["hr_bpm_current"]) is not None]
    disp_ticks = [disp[0]] + [b for a, b in zip(disp, disp[1:]) if b != a] if disp else []
    disp_jumps = [abs(b - a) for a, b in zip(disp_ticks, disp_ticks[1:])]
    lo, hi = pr
    mid = (lo + hi) / 2
    return {
        "capture": label, "mode": mode,
        "frames": len(ts), "span_s": round(dur, 2), "fps": round(fps, 2),
        "median_dt_ms": round(med_dt, 2), "dt_cv": round(st.pstdev(dts) / st.mean(dts), 3),
        "gaps_gt_2x": sum(1 for x in dts if x > 2 * med_dt),
        "exposure_ns": "|".join(exp), "iso": "|".join(iso), "tonemap_mode": "|".join(tone), "lock_state": "|".join(lock),
        "R_mean": round(st.mean(R), 1), "G_mean": round(st.mean(G), 1), "B_mean": round(st.mean(B), 1),
        "clipped_frac": round(clip, 5),
        "pi_red_mean": round(st.mean(pir), 5) if pir else "", "pi_blue_mean": round(st.mean(pib), 5) if pib else "",
        "spo2_displayed_mean": round(st.mean(spo2), 3) if spo2 else "",
        "hr_ticks": len(hr),
        "hr_raw_mean": round(st.mean(hr), 2), "hr_raw_sd": round(st.pstdev(hr), 2),
        "hr_raw_mean_abs_jump": round(st.mean(jumps), 2) if jumps else "",
        "hr_raw_max_abs_jump": round(max(jumps), 2) if jumps else "",
        "chrom_mean": round(st.mean(chrom), 2), "pos_mean": round(st.mean(pos), 2),
        "hr_disp_mean": round(st.mean(disp_ticks), 2) if disp_ticks else "",
        "hr_disp_mean_abs_jump": round(st.mean(disp_jumps), 2) if disp_jumps else "",
        "oximeter_pr": f"{lo}-{hi}",
        "hr_raw_mae_vs_pr_mid": round(st.mean(abs(h - mid) for h in hr), 2),
        "hr_raw_frac_within_pr_range_pm3": round(sum(1 for h in hr if lo - 3 <= h <= hi + 3) / len(hr), 3),
        "hr_disp_mae_vs_pr_mid": round(st.mean(abs(h - mid) for h in disp_ticks), 2) if disp_ticks else "",
    }


def main():
    d = Path(sys.argv[1])
    out = [analyze(d, *c) for c in CAPTURES]
    with open(d / "segment34_hr_regression_summary.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader(); w.writerows(out)
    for r in out:
        print(r)


if __name__ == "__main__":
    main()
