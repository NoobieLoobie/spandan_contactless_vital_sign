"""Segment 33 - offline SpO2 feature extraction on cached Spandan RGB traces.

Every feature here is computed from the SAME cached ROI traces the production
MATLAB pipeline uses (data/processed/*_rgb_traces.mat), so differences come
from the SpO2 method only, not the ROI.
"""
import glob, os, re
import numpy as np
import scipy.io as sio
from scipy.signal import butter, filtfilt, detrend

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.abspath(os.path.join(_HERE, "..", "..", ".."))  # spandan/
# Override with env vars if running from a copy (e.g. unzipped traces elsewhere).
TR = os.environ.get("SPANDAN_TRACES", os.path.join(_REPO, "data", "processed"))
GT = os.environ.get("SPANDAN_VIPL_RAW", os.path.join(_REPO, "data", "raw", "VIPL-HR"))


def bp(x, fs, lo, hi, order=2):
    nyq = fs / 2.0
    hi = min(hi, 0.95 * nyq)
    b, a = butter(order, [lo / nyq, hi / nyq], btype="band")
    return filtfilt(b, a, x)


def lp(x, fs, fc, order=2):
    b, a = butter(order, fc / (fs / 2.0), btype="low")
    return filtfilt(b, a, x)


def pos_pulse(Cn, fs, win_s=1.6):
    """POS (Wang 2017) on normalized channels Cn (3xN, each c/mean). Overlap-add."""
    N = Cn.shape[1]
    L = int(round(win_s * fs))
    H = np.zeros(N)
    P = np.array([[0, 1, -1], [-2, 1, 1]])
    for n in range(N - L + 1):
        C = Cn[:, n:n + L]
        C = C / C.mean(axis=1, keepdims=True)
        S = P @ C
        h = S[0] + (S[0].std() / (S[1].std() + 1e-12)) * S[1]
        H[n:n + L] += h - h.mean()
    return H


def hr_from_pulse(p, fs, lo=0.7, hi=4.0):
    n = len(p)
    nfft = max(4096, 1 << int(np.ceil(np.log2(n))) + 2)
    P = np.abs(np.fft.rfft(p * np.hanning(n), nfft)) ** 2
    f = np.fft.rfftfreq(nfft, 1 / fs)
    m = (f >= lo) & (f <= hi)
    return f[m][np.argmax(P[m])]


def features_for_segment(R, G, B, fs):
    """Return dict of candidate SpO2 features for one segment of raw traces."""
    out = {}
    raw = np.vstack([R, G, B]).astype(float)
    dc = raw.mean(axis=1)
    if np.any(dc <= 0):
        return None
    # --- F0: production ratio-of-ratios (std of 0.7-4Hz bandpassed / mean raw) ---
    filt = np.vstack([bp(detrend(c), fs, 0.7, 4.0) for c in raw])
    ac_std = filt.std(axis=1, ddof=1)
    pi_std = ac_std / dc
    out["RoR_RB_prod"] = pi_std[0] / pi_std[2]
    out["RoR_RG_std"] = pi_std[0] / pi_std[1]

    # normalized (DC-divided) channels, van Gastel eq.1 style: (I - LPF)/LPF
    base = np.vstack([lp(c, fs, 0.5) if len(c) > 3 * fs else np.full_like(c, c.mean()) for c in raw])
    norm = (raw - base) / base
    normf = np.vstack([bp(c, fs, 0.7, 4.0) for c in norm])

    # pulse reference (POS) + HR estimate from the camera itself (no GT leakage)
    pulse = pos_pulse(raw / dc[:, None], fs)
    pulse = bp(pulse, fs, 0.7, 4.0)
    hr = hr_from_pulse(pulse, fs)
    out["hr_hz"] = hr

    # --- F1: pulse-synchronous projection amplitude (least-squares gain of each
    # normalized channel onto the pulse reference). Noise uncorrelated with the
    # pulse averages out instead of inflating AC like std() does. ---
    pp = pulse / (np.linalg.norm(pulse) + 1e-12)
    proj = normf @ pp  # 3 gains (signed)
    out["PROJ_R"], out["PROJ_G"], out["PROJ_B"] = proj
    out["RoR_RB_proj"] = abs(proj[0]) / (abs(proj[2]) + 1e-12)
    out["RoR_RG_proj"] = abs(proj[0]) / (abs(proj[1]) + 1e-12)

    # --- F2: HR-guided narrow adaptive bandpass (Tian et al. 2022, +-0.1Hz) ---
    lo, hi = max(hr - 0.1, 0.5), hr + 0.1
    try:
        nab = np.vstack([bp(c, fs, lo, hi, order=4) for c in norm])
        amp = nab.std(axis=1, ddof=1) * np.sqrt(2) * 2  # ~peak-to-peak of a sinusoid
        out["NABP_R"], out["NABP_G"], out["NABP_B"] = amp
        out["RoR_RB_nabp"] = amp[0] / amp[2]
        out["RoR_RG_nabp"] = amp[0] / amp[1]
    except Exception:
        return None

    # --- F3: FFT amplitude at the HR bin of each normalized channel ---
    n = norm.shape[1]
    nfft = max(4096, 1 << int(np.ceil(np.log2(n))) + 2)
    f = np.fft.rfftfreq(nfft, 1 / fs)
    k = np.argmin(abs(f - hr))
    w = np.hanning(n)
    spec = np.abs(np.fft.rfft(normf * w, nfft, axis=1))
    band = slice(max(k - 2, 0), k + 3)
    a_fft = spec[:, band].max(axis=1)
    out["RoR_RB_fft"] = a_fft[0] / a_fft[2]
    out["RoR_RG_fft"] = a_fft[0] / a_fft[1]

    # quality: pulse SNR (power within +-0.1Hz of HR and 2nd harmonic vs rest of 0.7-4Hz)
    Pp = np.abs(np.fft.rfft(pulse * w, nfft)) ** 2
    m = (f >= 0.7) & (f <= 4.0)
    sig = ((abs(f - hr) <= 0.1) | (abs(f - 2 * hr) <= 0.1)) & m
    out["snr_db"] = 10 * np.log10(Pp[sig].sum() / (Pp[m & ~sig].sum() + 1e-12))
    # perfusion indices + DC colour (skin tone / illumination proxies)
    out["PI_R"], out["PI_G"], out["PI_B"] = pi_std
    out["DC_R"], out["DC_G"], out["DC_B"] = dc
    out["DC_RB"] = dc[0] / dc[2]
    out["DC_RG"] = dc[0] / dc[1]
    return out


def load_vipl_spo2(subj, ver, src):
    p = os.path.join(GT, subj, ver, src, "gt_SpO2.csv")
    if not os.path.exists(p):
        return None
    v = np.loadtxt(p, skiprows=1, delimiter=",", ndmin=1).astype(float)
    return v


def iter_vipl(pattern=r"^VIPL_(p\d+)_(v\d)_(source\d)_rgb_traces\.mat$"):
    for fn in sorted(os.listdir(TR)):
        m = re.match(pattern, fn)
        if not m:
            continue
        subj, ver, src = m.groups()
        mat = sio.loadmat(os.path.join(TR, fn))
        R, G, B = (mat[k].ravel().astype(float) for k in "RGB")
        fs = float(mat["fs"].ravel()[0])
        yield subj, ver, src, R, G, B, fs
