# Segment 33 — SpO2: Root-Cause Diagnosis, Literature/GitHub/Dataset Search, Offline Evaluation, and New Pipeline

Status as of **2026-09-25** (Cowork session). Nothing in production (`matlab/src/`,
`android/`, `ios/`) was modified by this segment. All new code is offline analysis under
`matlab/experiments/segment33_spo2_research/`.

Trigger: Abrar reported that the app's SpO2 **"shows very deviated result in comparison
to the pulse oximeter"** and asked for a rigorous search (papers, GitHub, anything), with
permission to replace the SpO2 pipeline entirely.

Citation discipline is unchanged (see `Literature_Review_Master.md`):
**VERIFIED-FULL** (read), **VERIFIED-PARTIAL** (key sections read via passage retrieval or
publisher full-text), **VERIFIED-INDEX** (existence/venue confirmed, not read),
**BLOCKED** (route failed; retry route recorded).

---

## 0. Bottom line (read this if nothing else)

1. **The app's SpO2 is effectively a constant, not a measurement.** The deployed formula is
   `SpO2 = 96.476 + 0.416·R` (`LiveSpo2Estimator.kt`, from `SpO2_Final_Calibration_Spec.md`).
   Over the whole R range ever observed (0.49–4.2) this spans 96.7–98.2 %. It cannot
   follow a real desaturation, and whatever the user's true SpO2 is, the app shows ≈ 96.8 %.
   The slope even has the opposite sign from `calibrateSpO2.m`'s own stated physiology.
   That is the "deviation" Abrar saw — not a bug in the code, a calibration fit on data
   that contains no SpO2 information.
2. **VIPL-HR cannot calibrate or validate any SpO2 method — proven here, not assumed.**
   Its labels are 1 %-quantized integers (100 % of 19,396 samples), 93.8 % sit in 95–99 %,
   and the whole-clip SD is 1.5 % — about the ±2 % accuracy of the finger oximeter that
   produced them. On 554 VIPL clips (107 people, webcam + phone), **every** candidate
   feature tested — production R/B, R/G, pulse-projection amplitudes, Tian's HR-guided
   narrow-band amplitudes, FFT-bin amplitudes, and multi-channel ridge regression — ties the
   "always predict the training mean" baseline under subject-grouped LOSO
   (MAE 1.15 vs 1.15–1.18; §4). This matches the literature: Cheng et al. 2024 report
   conventional RoR on VIPL at MAE 1.84–3.33.
3. **Why a population calibration cannot work on phone RGB faces (measured here):** the
   same person, filmed at the same moment by two cameras, gives strongly reproducible R
   (ρ = 0.73 across 94 people) — but R is unrelated to SpO2 (ρ = −0.09) and the phone reads
   20 % lower R than the webcam. So R is dominated by a stable **per-person × per-camera ×
   per-light offset** whose spread (SD of ln R = 0.36 between clips) is roughly 4–5× larger
   than the R change produced by the entire physiological SpO2 range (≈ 7 % of R per 1 %
   SpO2, from van Gastel & Verkruysse 2022's RGB calibration). Every published RGB method
   that reaches ≈ 1–2 % accuracy either uses a **per-subject/per-session anchor** (Guazzi
   2015, Wei 2021, Tian 2022 "participant-specific", Tang/McDuff 2025 "calibration is
   essential") or special hardware (NIR camera, optical filters, controlled LEDs).
4. **The acquisition side is also wrong for oximetry.** The Android camera runs fully
   automatic (auto-exposure, auto-white-balance, default sRGB tone curve; verified by
   grepping the source — no `Camera2Interop`/AE/AWB/tonemap control anywhere). Xuan et al.
   2023 measured a **74 % lower MAE** for smartphone PPG oximetry just by locking
   AE/AWB/ISO and forcing a linear tone curve. Auto-WB rescales R vs B gains on its own
   schedule, which directly rewrites the ratio-of-ratios.
5. **Recommended new pipeline ("Spandan SpO2 v2", §6):** locked-linear camera capture →
   HR-guided narrow-band AC per channel → log ratio-of-ratios with quality gating →
   **anchored trend estimation** `SpO2(t) = S_anchor + β·(ln R(t) − ln R_anchor)` with the
   anchor taken from a 20–30 s calm baseline (default 98 %, or a one-time reading from a
   real oximeter) and β fit on **our own breath-hold data** collected with the same phone
   (protocol in §7). The display becomes an honest "SpO2 trend / desaturation detector"
   rather than a fake absolute number. Validation requires new data — no public
   face-video dataset with real desaturations is openly downloadable (SUMS, LADH, NCTUBO
   need a faculty data-request; §3.3).

---

## 1. Audit of what currently ships

| Item | Finding | Source read |
|---|---|---|
| R formula | `R = (std(bp_R)/mean(R_raw)) / (std(bp_B)/mean(B_raw))`, whole window, 0.7–4 Hz band | `matlab/src/spo2/ratioOfRatios.m`, `LiveSpo2Estimator.kt` |
| Calibration | `SpO2 = A − B·R`, A = 96.476, **B = −0.416** (so SpO2 *rises* with R) fit on all 112 subjects, no hold-out | `SpO2_Final_Calibration_Spec.md` |
| Centering | Spec says the formula is meaningless without per-dataset R centering; Android applies it **uncentered** (Task R found centering a wash) | `Segment6_Task_R_Phone_SpO2_Centering.md` |
| Validated accuracy | Stratified LOSO VIPL MAE 1.908, r = −0.33 — **loses to predicting the mean** (1.84 vs 1.85 pooled) | `SpO2_Final_Report_Section.md` |
| Output range in practice | 96.7–98.2 % for any R ever observed; clamp 90–100 % never fires (on-device logs 96.72–96.94 %) | `SpO2_Live_Implementation.md`, Segment 16 Task 2 |
| Camera control | No AE lock, AWB lock, ISO/exposure fix, or tone-curve control; RGB derived from YUV_420_888 by BT.601 on gamma-encoded values | grep of `android/app/src/main/java/com/spandan/app/**.kt`, `RoiPixelAverager.kt` |
| Own Dataset videos | 10-bit HLG / BT.2020 HDR — non-linear transfer, never linearized (flagged 2026-09-19) | project memory / `Own_Dataset_Ground_Truth_Guide.md` |

Conclusion of the audit: the SpO2 number is a near-constant; fixing it needs (a) linear,
locked acquisition, (b) a less noise-dominated AC estimate, and above all (c) data with real
SpO2 variation plus a per-person/per-session anchor. A new regression on VIPL cannot help.

---

## 2. Search log (every query, so nothing is re-searched)

Tools: academic paper index (PubMed/PMC/bioRxiv/medRxiv/arXiv), publisher full text via
DOI resolution, GitHub/developer index, GitHub API, and the user's local
`H:\EEE 312 project\Contactless Vital Sign\Research Paper\` folder (Abrar placed the two
blocked PDFs there mid-session).

| # | Query / route | Useful hits |
|---|---|---|
| Q1 | "contactless SpO2 estimation from facial video RGB camera ratio of ratios" | 40 results; van Gastel 2022, Wei 2021, Tian 2022, Kim 2021 YCgCr, Cheng 2024, Guazzi 2015, Akamatsu 2023, Samsung EMBC 2023, Nishidate 2022, Tang 2025, SUMS |
| Q2 | "camera-based pulse oximetry calibration hypoxia study RGB camera validation" | Verkruysse 2017, Moço & Verkruysse 2019, Hoffman 2022, Xuan 2023 (via Q7) |
| Q3 | "smartphone camera blood oxygen saturation breath holding desaturation dataset" | Hoffman 2022 (open data), multispectral domain-adaptive 2026, Samsung smartwatch/phone hypoxia 2025, M3PD |
| Q4 | "public dataset facial video with reference SpO2 pulse oximeter rPPG" (2019+) | SUMS, VideoPulse (neonatal), MCD-rPPG/"Gaze into the Heart", VitalVideos, DDPM, PURE |
| Q5 | "camera auto exposure white balance gamma effect on rPPG amplitude ratio" | Skin-guided AE (EMBC 2024), ExpDrive 2026, dual-exposure (EMBC 2025) |
| Q6 | "relative SpO2 change tracking camera without absolute calibration personalized baseline" | Guazzi 2015 (anchored intercept), Sasaki 2023 (JTEHM, "without reference values") |
| Q7 | "design challenges camera oximetry mobile phone auto exposure white balance lock" | Karlen 2012/2013, **Xuan 2023 calibration method**, Scully 2012 |
| G1 | GitHub: "SpO2 estimation from face video rPPG ratio of ratios python" | batolhamoud443/contactless-oxygen-saturation-detection (CNN+XGBoost on VIPL/UBFC), mcjacktang/fusionvitals (LADH dataset), ubicomplab/rppg-toolbox (no SpO2) |
| G2 | GitHub: "thuhci SUMS dataset" | github.com/thuhci/SUMS — README read via GitHub API |
| G3 | GitHub: "NCTUBO breath holding SpO2 facial video" | referenced only inside SUMS paper; no repo located |
| L1 | Local folder `Research Paper\` | `Calibration of Contactless Pulse Oximetry.pdf` (read in full, pdftotext); `Analysis and improvement of non-contact SpO2 extraction using an RGB webcam.pdf` (content already read via Optica full text) |

---

## 3. What the literature actually says

### 3.1 Papers read (full or key sections)

| Paper | Status | What it contributes to Spandan |
|---|---|---|
| **van Gastel, M. & Verkruysse, W. (2022).** *Contactless SpO2 with an RGB camera: experimental proof of calibrated SpO2.* Biomed. Opt. Express 13(12):6791. doi:10.1364/BOE.471332 | VERIFIED-FULL (publisher HTML) | Only paper showing a *population* calibration for an RGB camera — but with a triple-band optical filter, cross-polarizers, a red+green LED floodlight, uncompressed 15 fps video and a 19-subject hypoxia lab (70–100 %). Uses **red/blue** (blue has lower sensitivity to the red LED), DC-normalized PPG `(I−LPF(I))/LPF(I)`, APBV amplitude estimation, calibration `SpO2 = (100·RoR − 53.3)/11.8`. Error < 4 pp seated; ~8 pp standing. Lists as requirements: no clipped pixels, no under-exposure, long stable desaturations for calibration. → Gives the physical sensitivity (≈ 7 % change of RoR per 1 % SpO2) used in §4.3, and shows how much hardware control a population calibration needs. |
| **Verkruysse, W. et al. (2017).** *Calibration of Contactless Pulse Oximetry.* Anesth. Analg. 124:136–145. doi:10.1213/ANE.0000000000001381 | VERIFIED-FULL (local PDF) | Monochrome cameras + 675/842 nm filters, forehead, 41 adults, hypoxic tent. One calibration `SpO2 = 118.0 − 45.9·RR` gave A*rms 1.15 % (long-term) and **A**rms 2.54 %** including short-term errors; PPG strength varies 5× between people; recordings with a weak red PPG had to be excluded via an SNR threshold (Q_thr 1.4). → Population calibration is *possible* with red/NIR narrowband optics; phone RGB has neither. Quality gating is mandatory. |
| **van Gastel, M., Stuijk, S. & de Haan, G. (2016).** *New principle for measuring arterial blood oxygenation, enabling motion-robust remote monitoring (APBV).* Sci. Rep. 6:38609 | VERIFIED-PARTIAL (publisher HTML; equations are images) | Instead of measuring two noisy amplitudes, try a bank of "blood-volume signatures" (one per SpO2) and pick the one whose extracted pulse has the best SNR. MAE 0.9 pp static / 2.0 pp during motion vs 2.5/24 pp for ratio-of-ratios — but at 760/800/840 nm. → The signature idea is the principled version of what Spandan would need; the signatures themselves have to be learned per camera/illumination, i.e. it still needs desaturation data. |
| **Guazzi, A.R. et al. (2015).** *Non-contact measurement of oxygen saturation with an RGB camera (Sophia).* Biomed. Opt. Express 6(9):3320 | VERIFIED-FULL (publisher HTML) | RGB (3-CCD) camera, red/blue, SNR-weighted sub-ROIs, **log RoR**: `log S(t) = c + β·E`. Calibration: β = average of *other* subjects, **intercept c from the subject's own first minute assuming 97 %**. Median r² 0.85 over 80–100 %. → Direct precedent for the anchored-trend design in §6. |
| **Wei, B. et al. (2021).** *Analysis and improvement of non-contact SpO2 extraction using an RGB webcam.* Biomed. Opt. Express 12(8):5227. doi:10.1364/BOE.423508 | VERIFIED-FULL (publisher HTML; PDF also in local folder) | Webcams (ThinkPad, iPhone 8), breath-hold ≥ 30 s, 8 subjects. Shows the textbook `std(AC)/mean(DC)` R/B ratio is poorly correlated with the oximeter; replacing std by SOBI blind-source-separation mixing-matrix energies and selecting "steady" DC segments restores r ≈ 0.8. **Calibration A, B fit per subject.** → Confirms our production AC estimator is the weak one, and that even the improved one is per-subject. |
| **Tian, X., Wong, C.-W., Ranadive, S.M. & Wu, M. (2022).** *A Multi-Channel Ratio-of-Ratios Method for Noncontact Hand Video Based SpO2 Monitoring Using Smartphone Cameras.* IEEE JSTSP 16:197. arXiv:2107.08528 | VERIFIED-FULL (arXiv passages) | iPhone 7 Plus, hands, breath-hold 89–99 %, 14 people. Features: per-channel AC/DC (AC = mean peak-to-valley after an **8th-order ±0.1 Hz band-pass centred on tracked HR**, DC = median of 0.1 Hz low-pass) + the three pairwise ratios → SVR. Participant-specific MAE 1.26 %, **leave-one-participant-out 1.70 %, ρ 0.53**; 2-channel RoR baseline ρ 0.22–0.36. Accurate HR tracking matters (10–21 % better than peak-picking). → Source of the narrow-band AC estimator and the 6-feature set tested in §4. |
| **Tang, J., Liu, X., McDuff, D. et al. (2025).** *Camera Measurement of Blood Oxygen Saturation.* arXiv:2503.01699 | VERIFIED-PARTIAL (arXiv passages) | Deep model + colour checker, THU/TUAT datasets (TUAT includes low SpO2). Conclusion in their own words: **"calibration is not only beneficial but necessary"** — per-video α, β from the first frames; 270-frame calibration cuts MAE 39 %. Their signal-processing baseline is itself a per-user-calibrated mean. → Even SOTA deep learning does not escape per-session calibration. |
| **Xuan, Y., Barry, C., Antipa, N. & Wang, E.J. (2023).** *A calibration method for smartphone camera photoplethysmography.* Front. Digit. Health 5:1301019 | VERIFIED-FULL (publisher HTML) | The default phone pipeline's tone mapping (sRGB-like log curve) inflates DC and compresses AC; AE/AWB/compression add more non-linearity. Fix on Android Camera2: tone map mode `CONTRAST_CURVE` with points `[(0,0),(1,1)]`, AE off with fixed ISO + exposure time, AWB off with fixed gains and identity colour-correction matrix, AF off, subtract a per-model "zero-light offset" (Samsung S22: −19.6). **74 % lower MAE, R² 0.81→0.97.** → Directly implementable in `MainActivity.kt` via CameraX `Camera2Interop`. |
| **Kim, N.H. et al. (2021).** *Non-Contact Oxygen Saturation Measurement Using YCgCr Color Space with an RGB Camera.* Sensors 21:6120 | VERIFIED-FULL (MDPI HTML) | Webcam, 10 people, 1-min breath-hold (85–100 %). Converts to YCgCr, AC via log(peak/valley) after 0.7–3 Hz band-pass, `SpO2 = 11.88·R_CgCr + 79.19`; reports MAE 0.54. Calibration and test appear to share subjects → treat as optimistic. → Cheap extra candidate feature (Cr/Cg log-ratio), tested in the new pipeline's evaluation plan. |
| **Hoffman, J.S. et al. (2022).** *Smartphone camera oximetry in an induced hypoxemia study.* npj Digit. Med. 5:146 | VERIFIED-PARTIAL | Finger-on-camera (contact), FiO2 protocol 70–100 %, open data at `github.com/ubicomplab/oximetry-phone-cam-data`. RoR MAE 7.1 %, CNN 5.0 %. → Even with contact + flash, a population model is only ≈ 5 %; open data is contact-finger, not face. |
| **Cheng, C.-H. et al. (2024).** *Contactless Blood Oxygen Saturation Estimation from Facial Videos Using Deep Learning.* Bioengineering 11(3):251 | VERIFIED-PARTIAL (MDPI HTML) | Benchmarks on **VIPL-HR**: conventional RoR MAE 1.84 / 3.33, deep models 1.00–1.27. No mean-predictor baseline reported. → Our VIPL MAE 1.91 is exactly the conventional-RoR level; their deep-learning margin is within what VIPL's label spread allows a near-constant predictor. |
| **Liu, K., Tang, J. et al. (2024).** *Summit Vitals (SUMS).* IEEE UIC 2024, arXiv:2409.19223 | VERIFIED-PARTIAL | 10 subjects at 2274–4898 m, face + finger, Logitech C922 60 fps, CMS50E+ reference, SpO2 < 92 % after exercise, oxygen-recovery sessions. Cross-subject face SpO2 MAE ≈ 2.5 %. Also documents **NCTUBO** (46 subjects, breath-hold, said to be open-source) and **SSL** (high altitude, mean SpO2 88 %). → The best available external face datasets with genuine desaturation. |
| Samsung Research America (2023). *Estimating SpO2 with Deep Oxygen Desaturations from Facial Video Under Various Lighting Conditions.* IEEE EMBC 2023, pmid 38083548 | VERIFIED-PARTIAL (abstract page) | "RGB camera **without auto-tuning**", induced hypoxemia to 81 %, R/B and R/G RoR, RMSE 1.93 %, PCC 0.97 under warm light (only 2 subjects LOSO); accuracy depends on lighting colour temperature. → Supports locking camera auto-adjustments and controlling illumination. |
| **van Gastel, M., Stuijk, S. & de Haan, G. (2018).** *Camera-based pulse-oximetry — validated risks and opportunities from theoretical analysis.* Biomed. Opt. Express 9(1):102 | VERIFIED-FULL (local PDF, added 2026-09-25 by Abrar) | Skin-model wavelength search restricted to red/NIR (600–1000 nm); explicitly does **not** model blue/green because their PPG origin (capillary, non-volumetric) is disputed — i.e. the physics behind any RGB R/B or R/G calibration is itself uncertain. Three usable ideas for Spandan: (1) **motion-induced intensity changes are equal on all channels**, so the pulse signature must be kept away from the "equal-weights" motion vector — measure amplitudes after removing the common (1,1,1) component of the normalized channels; (2) with three channels, form **two independent channel pairs** (e.g. R/B and R/G), convert each with its own calibration, and use their disagreement as a **reliability index**; (3) SpO2 contrast is quantified as the angle between pulse signatures at 0 % and 100 % SpO2 — an objective way to compare channel pairs on our own breath-hold data. |
| **Sasaki, S., Sugita, N., Terai, T. & Yoshizawa, M. (2024).** *Non-Contact Measurement of Blood Oxygen Saturation Using Facial Video Without Reference Values.* IEEE JTEHM 12:76. doi:10.1109/JTEHM.2023.3318643 | VERIFIED-FULL (local PDF, added 2026-09-25 by Abrar) | Reference-free SpO2 from **theoretical extinction coefficients** (Eq. 6) — but only with a multispectral RGB-NIR camera (msCAM) and narrowband LEDs at 524/630/850 nm, 15 subjects, breath-hold. Method 1: **band-pass the log of ROI luminance** (removes illumination/melanin terms multiplicatively, Eq. 10–12) and use its amplitude as ΔA. Method 2A/2B: **PCA with the green channel as a shallow-layer (capillary) reference**; the 2nd principal component of (red, green) and (NIR, green) is taken as the deep arterial pulse. RMSE 2.55 % (Method 2B, dark room), ≈ +1 % under ambient light; face SpO2 **lags the finger by several seconds** and must be lag-aligned (their Fig. 6). → Reference-free Eq. 6 does not transfer to broadband phone RGB (the delta-function spectral assumption fails), but the **log-domain amplitude** and the **green-as-shallow-reference PCA** are both cheap, portable candidates for the AC stage, and the lag finding fixes our evaluation protocol. |

### 3.2 Indexed, not read (recorded so they are not re-searched)

| Paper | Status | Why not pursued further |
|---|---|---|
| Moço, A. & Verkruysse, W. (2020). *Pulse oximetry based on PPG imaging with red and green light: calibratability and challenges.* J. Clin. Monit. Comput. doi:10.1007/s10877-019-00449-y | VERIFIED-INDEX | Red/green is calibratable but "challenging" (green probes shallower layers). Retry: Springer link, or add PDF to `Research Paper\`. |
| Ye, Gu & Wang (2023) EMBC, red vs green penetration depth; Petersen et al. (2023) EMBC smartphone face SpO2; Casalino et al. (2022) JAIHC | VERIFIED-INDEX (already in Segment 16 Task 2) | Unchanged verdicts from Segment 16. |
| Nishidate, I. et al. (2022). *RGB camera-based simultaneous SpO2, StO2, PR, RR.* Front. Physiol. 13:933397 | VERIFIED-INDEX | Needs Monte-Carlo-derived tissue matrices per camera; heavy, deferred. |
| Sun, Z. et al. (2021) BOE 12:1746 (smartphone iPPG + flash, multiple linear regression); Akamatsu et al. (2023) DC/AC STMap CNN; CL-SPO2Net (2024); OxyMamba (2026); multispectral domain-adaptive (2026); Wu et al. (2023) IEEE Sensors (NCTUBO/SSL) | VERIFIED-INDEX | Deep-learning or special-hardware methods — out of scope for the "no trained model artifact" constraint, or no readable full text. |

### 3.3 Datasets with face video + SpO2 (the real bottleneck)

| Dataset | SpO2 variation | Access | Verdict for Spandan |
|---|---|---|---|
| VIPL-HR (already used) | 1 %-quantized, 94 % in 95–99 %, clip SD 1.5 % | have it | **Cannot calibrate/validate SpO2** (§4) |
| PURE | normoxic only | public | same problem as VIPL |
| SUMS (github.com/thuhci/SUMS) | exercise desaturation < 92 %, O2 recovery | signed agreement **sent by faculty** to tjk24@mails.tsinghua.edu.cn cc yuntaowang@tsinghua.edu.cn | Best external candidate — needs the supervisor to request |
| LADH (github.com/mcjacktang/fusionvitals) | post-exercise / breath-hold | agreement-based (same group) | secondary candidate |
| NCTUBO (Wu et al. 2023) | breath-hold, 46 subjects, only 1.2 % < 90 % | reported open, repo not found | ask authors |
| Hoffman 2022 (ubicomplab/oximetry-phone-cam-data) | 70–100 % | public | finger contact video, not face — useful only for AC-estimator sanity checks |
| **Own Dataset (Spandan)** | has "After Breath Hold" cases but GT is 5.12 s AFE bursts, no oximeter SpO2 log | have it | not usable as SpO2 GT as recorded; §7 fixes this going forward |

---

## 4. Offline experiments on Spandan's own cached traces

Everything below uses the **same cached ROI traces** as production
(`data/processed/*_rgb_traces.mat`), so the SpO2 method is the only thing that changes.
Scripts: `matlab/experiments/segment33_spo2_research/` (Python; `features.py`,
`run_extract.py`, `evaluate.py`, `analysis.py`). Parity check: my re-implementation of
production R matches the frozen `segment5_vipl_calibration.csv` R values to ≈ 0.1–0.3 %
(e.g. p1: 1.3759 vs 1.3775) — residual is filter-order detail, not a different formula.

Data: every VIPL clip with a cached trace **and** a `gt_SpO2.csv` — 554 clips, 107 people,
v1/v4/v5/v7 webcam (source1, 223) and v1/v7/v8/v9 phone (source2, 330). GT samples outside
80–100 % (fault codes such as p25's 44 %, 103.79 %) were dropped; 246 of 19,396 samples.

Candidate AC/DC features (all per channel, then R/B and R/G ratios, analysed as ln R):
`prod` (production std/mean), `proj` (least-squares gain of each DC-normalized channel onto
the POS pulse), `nabp` (Tian 2022 ±0.1 Hz band-pass around the camera's own HR estimate),
`fft` (FFT magnitude at the HR bin), plus ridge regression on Tian's 6-feature set and on all
ratios + DC colour. Evaluation: leave-one-**subject**-out (all clips of a person held out
together), MAE/RMSE/Pearson r vs the "predict the training mean" baseline; raw Spearman ρ;
and **within-subject ρ** (feature and SpO2 both de-meaned per person × camera — the question
"could this track changes after a personal calibration?").

### 4.1 Whole-clip results (`eval_clip.csv`)

| Scope | Best single feature (LOSO MAE) | Train-mean baseline MAE | Best raw \|ρ\| (p) | Best within-subject ρ |
|---|---|---|---|---|
| All (N=554) | log RoR_RG_proj 1.155 | **1.153** | 0.063 (0.14) | 0.070 (p 0.10) |
| Webcam (N=223) | log RoR_RB_nabp 1.235 | **1.234** | 0.099 (0.14) | 0.141 (p 0.037, 1 of 8 tests, not significant after correction) |
| Phone (N=330) | log RoR_RG_fft 1.101 | **1.102** | 0.119 (0.03, 1 of 8) | 0.087 (p 0.12) |

Ridge models (Tian-6, projection set, all ratios + DC) are all *worse* than the baseline
in every scope (MAE 1.11–1.27). **No estimator carries detectable SpO2 information on VIPL.** This is a
property of the labels, not a verdict on the estimators: nothing *can* win here.

### 4.2 Within-clip tracking (10 s windows, 5 s step; `feat_10.csv`)

Only 57 of 554 clips swing ≥ 2 % in SpO2 during the clip. Within those, de-meaned
ln R vs SpO2: production R/B ρ = +0.109 (p 0.039), R/G-std +0.103 (p 0.052), all others
|ρ| ≤ 0.064 — one nominal hit out of 8 tests, sign consistent with van Gastel's positive
RoR-vs-SpO2 slope for red/blue, **too weak to act on**.

### 4.3 Why: offsets dwarf the physiology (`analysis_summary.csv`)

| Quantity | Value | Meaning |
|---|---|---|
| Same person, same moment, webcam vs phone: Spearman ρ of ln R | **0.73** (N = 94) | R is a strong, reproducible per-person property… |
| …and its correlation with that person's SpO2 | −0.09 | …that has nothing to do with oxygen (skin optics, geometry, lighting) |
| Phone − webcam mean ln R | −0.22 (phone R ≈ 20 % lower) | per-camera offset ≈ 3 % SpO2 at van Gastel's sensitivity |
| Between-clip SD of ln R (production R/B) | 0.36 | offset spread ≈ 5 % SpO2-equivalent |
| Expected ln R change per 1 % SpO2 (van Gastel 2022, RGB + filters) | ≈ 0.07 | the whole 95–99 % range moves ln R by only ≈ 0.28 |
| Within-clip jitter of ln R per 10 s window: prod / R-G std / nabp / fft / proj | 0.067 / 0.046 / 0.22 / 0.25 / 0.40 | std-based R is the steadiest, but std includes stationary noise, so "steady" ≠ "accurate"; the narrow-band/projection estimators need better HR tracking before they are usable (Tian's own ablation says the same) |

Implication: a single population formula is guaranteed to regress to a constant (exactly
what the 112-subject fit did, B ≈ 0). The only designs that can work on an unfiltered phone
RGB camera remove the per-person/per-camera offset — i.e. **anchor per session** — and learn
only the *slope* β, which is far more portable across people than the intercept
(Guazzi 2015's premise, and consistent with Tian's participant-specific > LOPO gap).

---

## 5. Candidates considered, and verdicts

| Candidate | Verdict | Reason |
|---|---|---|
| Re-fit the current R/B linear model on more VIPL clips | **Rejected** | §4: no information in the labels; would stay a constant |
| Swap to R/G, YCgCr (Kim 2021), CIELab channels | **Keep as features to test on breath-hold data** | Nothing can be ranked on VIPL; literature is split (van Gastel prefers R/B with filters; Moço/Ye study R/G; Kim reports Cg/Cr) |
| Tian 2022 HR-guided narrow-band AC + multi-channel regression | **Adopt the AC estimator in v2, keep regression optional** | Best-documented AC improvement on a smartphone; but depends on accurate HR tracking (their AMTC; our Segment 23-26 windowed read-out is the natural drop-in) |
| Wei 2021 SOBI mixing-matrix AC | Candidate B for the AC stage | Real noise-robustness gain in their webcam study; heavier to port to Kotlin |
| APBV / PBV signatures (van Gastel 2016) | Deferred | Needs signatures learned per camera + illumination = needs desaturation data first |
| Deep learning (Cheng 2024, CL-SPO2Net, OxyMamba, Tang 2025) | Out of scope | No trained artifact; still require calibration per Tang 2025 |
| Lock AE/AWB/ISO/exposure + linear tone curve + zero-light offset (Xuan 2023) | **Adopt (acquisition)** | Largest single measured gain in the literature (−74 % MAE); zero algorithmic risk; affects HR too, so must be checked against HR accuracy |
| Per-session anchoring (Guazzi 2015 / Tang 2025) | **Adopt (estimation)** | Only design consistent with §4.3; transparent; one number (β) to learn |
| Perfusion-index / SNR quality gate (Verkruysse 2017 Q_thr, Liang 2026) | **Adopt** | Every calibrated study discards low-SNR data; our PI/SNR are already computed |

---

## 6. New pipeline: "Spandan SpO2 v2" (proposed; not yet built on device)

```
Camera (front, CameraX + Camera2Interop)
  AE off: fixed exposure time + ISO chosen once from a 2 s auto-metering pre-roll,
          then frozen so forehead G mean ≈ 40–60 % of full scale, no clipping
  AWB off: COLOR_CORRECTION_MODE_TRANSFORM_MATRIX, identity CCM, fixed gains frozen from pre-roll
  Tone map: TONEMAP_MODE_CONTRAST_CURVE, points (0,0),(1,1)  (linear)
  AF off / locked; OIS off if exposed; target 30 fps fixed range
        │
ROI mean R,G,B  (existing face box; reject frames with >1 % clipped pixels)
        │   subtract zero-light offset (per phone model, measured once with lens covered)
DC_c(t) = LPF_0.1–0.5Hz(c)          PPG_c(t) = (c − DC_c)/DC_c
HR(t) from Branch-1 pulse (existing CHROM/POS + windowed read-out)
AC_c(t) = amplitude of PPG_c in a ±0.1 Hz band around HR(t)   [Tian 2022]
        (candidate B: SOBI mixing-matrix energy [Wei 2021])
        (candidate C: amplitude of band-passed ln(ROI mean) per channel [Sasaki 2024, Method 1])
        (candidate D: PCA of (R, G) with G as shallow-capillary reference, AC = PC2 amplitude [Sasaki 2024, Method 2A])
        before any of these: remove the common-mode (1,1,1) part of the normalized channels,
        which is where motion/illumination intensity changes live [van Gastel 2018]
Quality q(t): pulse SNR, PI_R, PI_B, motion (box jitter); window rejected below thresholds
L(t) = ln( AC_R/AC_B )   (also log R/G and log Cr/Cg logged for evaluation)
Reliability index: convert R/B and R/G separately (each its own β); |difference| large
        -> show "unreliable" instead of a number [van Gastel 2018 wavelength-pair idea]
Anchor: first 20–30 s of accepted windows while user sits still and breathes normally
        L0 = median L;  S0 = 98 % default  (or user enters a real oximeter reading once)
SpO2(t) = S0 + β·(L(t) − L0),  β learned from Spandan's own breath-hold data (§7),
          sign and magnitude checked against van Gastel 2022 (≈ +0.118 RoR per % → β ≈ +14 %/unit ln R)
Smoothing: 10 s moving median (Tian 2022 used 10 s mean); display "Trend" + drop alerts (≥3 % below anchor)
```

What this can honestly claim once validated: **detecting and quantifying a desaturation
relative to the user's own baseline** (breath-hold, exercise recovery), not an absolute
clinical SpO2 for a stranger. The UI text should say so.

---

## 7. Data we must collect (nothing can be validated without it)

Minimum viable calibration set, using the phone that runs the app and Abrar's pulse
oximeter:

1. Phone on a tripod ≈ 40–50 cm from face; steady indoor light (no window daylight
   changes); the **oximeter display visible in the same camera frame** (or filmed by a
   second phone) so readings can be time-aligned from the video itself; note the
   oximeter's own ~2–10 s delay (Tian 2022 measured 1.8 s for a CMS-50E; Tang 2025 up to 10 s).
   Face SpO2 lags the finger by several seconds (Sasaki 2024, Fig. 6; up to ~10 s per
   Tang 2025) — estimate and remove the lag per session before scoring.
   Use one fixed lamp (Sasaki 2024: RMSE ≈ +1 % under mixed ambient light); log which light.
2. Protocol per session (≈ 5 min): 60 s normal breathing → breath-hold 30–45 s (only as long
   as comfortable) → 60 s recovery → repeat ×3. This is the protocol used by Tian 2022,
   Wei 2021, Kim 2021 (SpO2 typically reaches 89–93 %).
3. People: all 5 group members + anyone willing, 2 sessions each on different days
   (session 1 trains β, session 2 tests — Tian's participant-specific vs LOPO split).
4. Record twice: app capture mode (locked/linear, raw RGB logged to CSV every frame) **and**
   the default camera app, so the effect of locking is measured on the same person.
5. Stop immediately if anyone feels dizzy; breath-holding is voluntary and self-limited.
6. Ask the supervisor to request SUMS (and LADH) access — the agreement must come from a
   faculty email.

Acceptance criteria before shipping v2 (pre-register them, same discipline as Segments 24–27):
within-session tracking r ≥ 0.6 and MAE ≤ 2 % on held-out session-2 data; a desaturation of
≥ 3 % detected within 20 s in ≥ 80 % of breath-holds; HR accuracy not worse with the locked
camera (Branch-1 regression check).

---

## 8. Next steps (queued, in order)

1. **Device work** (handed to a local Claude Code CLI session — prompt in
   `matlab/experiments/segment33_spo2_research/CLAUDE_CODE_PROMPT_Segment34.md`):
   camera lock + linear tone curve + raw-RGB CSV logger + "calibration recording" mode; no
   change to the displayed SpO2 formula until β exists.
2. Collect the §7 data; run the v2 evaluation offline (these scripts extend directly: replace
   the VIPL loader with the recorded CSV + oximeter readings).
3. Fit β, implement the anchored estimator in `LiveSpo2Estimator.kt` behind a flag, and only
   then change the UI wording to "SpO2 trend".
4. ~~Read Sasaki 2023 (JTEHM) and van Gastel 2018 (BOE 9:102) once the PDFs are in
   `Research Paper\`.~~ **Done 2026-09-25** — both read in full (§3.1). Consequences folded into §6 (AC-stage candidates C/D, reliability index) and §7 (lag alignment, fixed lighting).

## Files

- `matlab/experiments/segment33_spo2_research/features.py`, `run_extract.py`, `evaluate.py`, `analysis.py`
- `.../results/feat_clip.csv` (554 clips), `feat_10.csv` (3,217 windows), `eval_clip.csv`, `analysis_summary.csv`
- `.../CLAUDE_CODE_PROMPT_Segment34.md`
- `matlab/docs/Literature_Review_Master.md` §9 (new)
