# Segment 35 Phase 4 — On-device test results (reduced 6-session matrix)

Date: 2026-09-29 · Device: Samsung Galaxy A35 (`SM-A356E`, `RFCXC0FFFSN`) · Operator: Abrar
(holding the phone/oximeter), Claude Code (driving the app via `adb`, timing sessions,
pulling data). Executes the **reduced matrix** from `docs/Segment35_Phase4_OnDevice_
Test_Protocol.md` (rows 1, 4, 5, 8, 9, 12) — Abrar chose the reduced option, then asked to
start it live in this same session.

**One real deviation from the written protocol, disclosed up front**: `adb` only works over
USB (or pre-configured WiFi debugging, not set up this session), so once the phone is
genuinely unplugged, Claude loses all control (screenshots, start/stop, file pulls). Abrar
proposed holding the phone handheld while still leaving the USB cable connected. **All 6
sessions ran with USB connected** — the placement variable (stand vs. handheld) was tested;
the power/charging variable (plugged vs. unplugged, i.e. the thermal/CPU-governor question
Segment 8's own diagnostic first raised) was **not** tested this round.

Each session: 3 minutes (target), calibration recorder on, oximeter held visible in frame,
Abrar read HR/SpO2 off the oximeter's own display and reported it verbally at the end of
each session (a coarse range, not a continuous log — see §3's caveat on precision).

---

## 0. Bottom line

- **A real crash, a real oscillation bug, and a real throughput bug were found and fixed
  *before* this test even started** (same session, see `Segment35_Android_Fix_Pack_And_
  Oximeter_Box.md` §4) — this test ran on top of those fixes, not the original code.
- **SpO2 display is confirmed, on real paired data, to be an effective constant**: displayed
  values spanned only 96.79–96.92% (a 0.13-point range) across all 6 sessions, while the
  oximeter's own readings genuinely varied 93–96%. This is exactly Segment 33's diagnosis
  (`SpO2 = 96.476 + 0.416·R` can't move enough to track anything), now confirmed with real
  paired phone+oximeter data instead of only public-dataset analysis.
- **HR accuracy did not show a clean winner among the three builds** at this sample size
  (n=2 sessions per build) — mean absolute error vs. the oximeter's reported range midpoint
  was A: 13.0 bpm, B: 33.25 bpm, C: 23.25 bpm, but B contains both the single best result
  (1 bpm) and the single worst (65.5 bpm) of the whole matrix, i.e. high variance, not a
  systematic effect. **Handheld was worse than stand on average** (35.5 vs 10.8 bpm mean
  error), consistent with the original complaint, but with only 3 handheld sessions and one
  clear outlier this is suggestive, not conclusive.
- **Locked linear capture's re-lock logic fired 6 times in EVERY session it was on** (both C
  sessions), roughly once every 20-35 seconds, clearing the signal buffer each time. This is
  a real, on-device-confirmed usability cost: the buffer needs 25s to fill for a real
  estimate, so re-locking every ~30s means the estimator rarely gets a full clean window.
  **Session 12 ended still in "Warming up" state** because the last re-lock landed only 36s
  before the session's own end. This is new evidence the current re-lock tuning
  (`RELOCK_DRIFT_FRACTION=0.15`, `RELOCK_SETTLE_MS=3000`) is too eager for real handheld/
  stand use, not just the earlier ad-hoc smoke test's finding.
- **The oximeter viewing box's crop is confirmed genuinely un-mirrored** on real data: a
  flip-test against the oximeter's own printed "JUMPER" logo (unambiguous, since it's molded
  text, not an LCD) showed the un-flipped crop was correct and flipping made it wrong. A
  real, positive confirmation of Phase 3's core design claim — not something the earlier
  ad-hoc smoke test had checked.
- **Oximeter digit legibility in the saved crops is marginal** at the current crop
  size/resolution — readable with effort, not clean enough for automated OCR without
  cropping tighter or increasing resolution. A real, concrete finding for any future
  refinement of the guide box.

---

## 1. Session data

| # | Build | Placement | Power | CSV rows | Duration | Displayed HR | Displayed SpO2 | Oximeter SpO2 | Oximeter PR range | HR abs. error* |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | A (old, pre-Segment-35) | stand | USB | 2296 | 186s | 58 bpm | 96.92% | 93% | 72-76 | 16.0 |
| 4 | A (old) | handheld | USB** | 4547 | 186s | 72 bpm | 96.92% | 93% | 79-85 | 10.0 |
| 5 | B (fix-pack) | stand | USB | 4056 | 186s | 77 bpm | 96.92% | 93% | 74-78 | **1.0** |
| 8 | B (fix-pack) | handheld | USB** | 4170 | 186s | **153 bpm** | 96.86% | 96% | 80-95 | **65.5** |
| 9 | C (fix-pack + locked/relock) | stand | USB | 4370 | 193s | 96 bpm | 96.83% | 95% | 78-83 | 15.5 |
| 12 | C (fix-pack + locked/relock) | handheld | USB** | 4248 | 185s | 53 bpm | 96.79% | 95% | 82-86 | 31.0 |

\* `|displayed − midpoint(oximeter range)|`. \*\* USB stayed connected (see the deviation
note above) — "handheld" here means motion, not the unplugged/thermal condition originally
planned.

**By placement**: stand mean error 10.8 bpm (n=3) vs. handheld mean error 35.5 bpm (n=3).

**By build**: A mean 13.0 bpm (n=2), B mean 33.25 bpm (n=2, driven entirely by session 8's
outlier), C mean 23.25 bpm (n=2, both partly explained by re-lock buffer clears, see §2).

**SpO2**: displayed 96.79-96.92% (range 0.13 points) across every session and every build;
oximeter 93-96% (range 3 points) over the same sessions. The app's SpO2 display did not
track the oximeter's own real, if modest, movement in any session.

Raw data: `android/docs/segment35_phase4_data/` — one CSV per session (full per-frame ROI
RGB/exposure/HR/SpO2/lock-state columns, same schema as Segment 34), `session_summary.csv`
(this table's own source), and a handful of representative oximeter-crop JPEGs per Build
B/C session (the full ~150-crop/session set exists locally, not committed, to keep the repo
size reasonable — see §3).

---

## 2. Re-lock behavior under real conditions (sessions 9 and 12)

Both "Locked linear" sessions logged real `RELOCK` events (not simulated, not the earlier
ad-hoc smoke test — this is Abrar actually sitting/holding the phone):

**Session 9 (stand)**: 6 re-locks in 193s —
```
+87s  face reacquired after a lost-face gap
+91s  ROI brightness drifted 42% -> 51%
+100s ROI brightness drifted 47% -> 54%
+117s ROI brightness drifted 56% -> 47%
+121s face reacquired after a lost-face gap
+172s ROI brightness drifted 51% -> 43%
```
Two of these were genuine lost-face events even on a stand — meaning the framing or
lighting shifted enough (or Abrar moved) that ML Kit lost the face at least twice in a
193-second stationary sit. `clip=8006/45552` (17.6% clipped pixels) at the end of this
session also suggests the locked exposure was running a bit bright for this room.

**Session 12 (handheld)**: 6 re-locks in 185s, all brightness-drift (no lost-face this
time) —
```
+1.4s  ROI brightness drifted 59% -> 68%
+28s   ROI brightness drifted 54% -> 43%
+110s  ROI brightness drifted 53% -> 45%
+119s  ROI brightness drifted 54% -> 44%
+143s  ROI brightness drifted 50% -> 57%
+149s  ROI brightness drifted 45% -> 44%
```
Roughly one re-lock every 20-35 seconds throughout — natural handheld micro-motion is
clearly enough to cross the 15% brightness-drift threshold repeatedly. The session ended
with the estimator still in `WARMING_UP` (the last re-lock's buffer-clear at +149s left only
36s before the 185s session ended — under `RealHeartRateEstimator`'s own `MIN_WINDOW_
SECONDS`/buffer-fill requirement).

**Implication, stated plainly**: the re-lock feature works exactly as designed (no crash, no
sub-second cascade — the earlier same-day fix holds), but at its current 15%/3s settings it
re-locks often enough in ordinary use that the signal buffer rarely gets a full clean
window. This is very likely part of why sessions 9 and 12's HR accuracy wasn't better than
the plain fix-pack build (B) despite the extra engineering — the re-lock's own buffer-clear
cost may be outweighing whatever exposure-stability benefit it provides. **Needs a real,
controlled follow-up** (not run this session): either widen the drift threshold, lengthen
the settle window, or both, then re-measure.

---

## 3. Honest caveats on this data

- **n=1 subject, n=1-2 sessions per condition** — every number above is a single data point
  or a pair, not a validated statistic. Session 8's 65.5 bpm outlier alone swings Build B's
  entire average; a different single session could easily have told a different story.
- **Oximeter ground truth is a verbally-reported range per session, not a continuous log.**
  Abrar read the oximeter's display at the end of each session and reported a range (e.g.
  "72-76 bpm") from memory of what he'd seen during the session — not a frame-by-frame
  reference. The HR "error" above compares the app's one final displayed value against the
  midpoint of that whole-session range, which is a coarse comparison, not a real per-second
  MAE. The saved oximeter-crop JPEGs (once/sec, Build B/C sessions only) exist specifically
  to do this properly, but reading precise digits from them needs either careful manual
  review of all ~150 crops/session or a proper OCR pass — not done this session (see
  Phase 3's own "not tried with an actual oximeter" gap, now partially closed: it WAS tried,
  but the resulting crops weren't precision-read).
- **Crop resolution/legibility**: manual inspection of the sample crops (`android/docs/
  segment35_phase4_data/*/sample_oximeter_crops/`) shows the oximeter's digits are readable
  with effort but small/soft — a tighter guide-box crop, higher source resolution, or basic
  contrast enhancement would likely be needed before any automated OCR pass could work
  reliably.
- **The power/unplugged variable was not tested** (see the deviation note at the top) —
  Segment 8's original thermal/CPU-governor question remains open.
- **Old build (A) sessions have no oximeter-crop ground truth** — Phase 3 (the crop-saving
  feature) postdates that build entirely, so sessions 1 and 4's oximeter readings rest on
  Abrar's verbal report alone, same limitation as every session but with no crop backup.

---

## 4. What this changes / doesn't change

- **`useOximetryCapture` stays `false` by default.** This data does not show locked capture
  (Build C) beating the plain fix-pack (Build B) — if anything the re-lock's own buffer-
  clear frequency looks like a real cost. Segment 34's own promotion bar ("keep default
  false unless HR is demonstrably not worse") is not cleared by this round.
- **`RELOCK_DRIFT_FRACTION`/`RELOCK_SETTLE_MS` need real tuning**, now backed by two
  independent on-device observations (this session's ad-hoc smoke test AND this structured
  test) both showing more frequent re-locking than intended. A natural next step: widen the
  drift threshold (e.g. 20-25%) and/or lengthen the settle window, then repeat sessions 9
  and 12 only, comparing re-lock counts.
- **SpO2 formula stays unchanged** (still no validated β) — this round adds real paired
  phone+oximeter data points but not the dedicated breath-hold protocol (Segment 33 §7 /
  Phase 5) needed to actually fit one.
- **The oximeter guide box/inset are confirmed genuinely useful and correctly un-mirrored**
  on real hardware — worth keeping, with the crop-legibility caveat above as the main open
  item.
- **Handheld vs. stand HR degradation is now real, on-device-observed evidence** (not just
  the original user complaint) — supports keeping Phase 2's fix-pack items (gap detection,
  uniform resample, widened ROI) as the right direction, even though this small sample can't
  show they've fully solved it yet.

## 5. Files

`android/docs/segment35_phase4_data/`: `session_summary.csv` (this doc's own source table),
`session{1,4,5,8,9,12}_*/spandan_cal_*.csv` (full per-frame CSVs, one per session, same
schema as Segment 34's recorder), `session{5,8,9,12}_*/sample_oximeter_crops/` (2 sample
JPEGs per Build B/C session; full ~150-crop sets per session exist locally, not committed).
