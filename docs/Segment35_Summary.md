# Segment 35 — Summary (all 5 phases)

Date: 2026-09-28/29. This is the one-page hand-off for the course supervisor, summarizing
the whole session's work across MATLAB (Phase 1), Android (Phases 2-3), an on-device test
(Phase 4), and SpO2 calibration (Phase 5). Each phase links to its own full-detail doc.

**Scope constraint honored throughout**: per the supervisor's own instruction, ML/DL was
used only for the ROI-extraction stage (MediaPipe FaceMesh landmarks + a classical skin
filter). No end-to-end learned rPPG model, and no DSP stage (pulse combination, filtering,
HR read-out, SpO2 computation) used ML/DL — every DSP function is the same classical,
hand-derived code this project has used throughout.

---

## Phase 1 — MediaPipe anatomy-informed ROI (MATLAB)

*Full detail: `matlab/docs/Segment35_MediaPipe_Anatomy_ROI.md`*

Built a landmark-driven forehead + both-cheek (malar) ROI, following Kim, Lee & Sohn (2021,
Sensors 21:7923)'s own top-5 ranked anatomical regions, with a classical skin-color pixel
filter — replacing the existing pipeline's fixed fractional-box ROI. Evaluated on 68 of the
planned 132 subjects (a background batch was stopped partway through, by request, before it
could reach the 20-subject motion-robustness pool).

**Result: a real, statistically significant heart-rate accuracy improvement** — CHROM error
roughly halved (8.9→4.8 bpm MAE), POS error roughly halved (7.5→3.7 bpm), both significant
after correcting for multiple comparisons. This is the *first* ROI-shape change in this
project's entire history (~30 prior work sessions) to show a real accuracy effect; every
previous attempt showed no difference at all. The gain shows up specifically on more
naturally-filmed footage (VIPL dataset), not on posed/still footage (UBFC), where results
stay identical as before.

**Caveat, and why this is not yet adopted**: the specific question this experiment was
designed to answer — does this ROI help under head motion/handheld conditions — was never
reached, because the motion-specific test pool (20 subjects) wasn't processed before the
batch stopped. The result above is real and promising, but on a different, easier condition
than the one that matters most for the phone app. **Recommendation: worth finishing** (the
same script resumes automatically from where it left off) before deciding whether to port
this to the Android app.

---

## Phase 2 — Android app fix pack

*Full detail: `android/docs/Segment35_Android_Fix_Pack_And_Oximeter_Box.md`*

Five classical (no ML/DL) fixes targeting why the app "worked in testing but not in your
hand": a smaller minimum face size for handheld framing, real gap detection so a
momentarily-lost face doesn't silently corrupt the heart-rate window, uniform time-based
resampling to remove camera timing jitter, a wider forehead+cheeks sampling region, and
automatic re-locking of the camera's exposure when lighting drifts or the face is
re-acquired.

**On-device testing found and fixed one real crash and two real bugs** the same session a
phone became available — a format choice that crashed face detection outright, a re-lock
loop that would have made a whole feature unusable, and a performance bug that dropped
frame-processing speed by 70% while a debug panel was open. All three are fixed and
re-verified working. A follow-up **structured on-device test (Phase 4, below) found the
re-lock feature still fires too often in real use** (about every 20-35 seconds) and needs
further tuning before it's turned on by default.

---

## Phase 3 — Oximeter viewing box

*Full detail: same Android doc, §2 and §4.5*

Added an on-screen guide box and a small live, right-way-up preview of whatever's held in
that box, so a pulse oximeter's reading can be seen and photographed without it appearing
mirrored/backwards the way a normal selfie camera does. Confirmed correct on a real device
using the oximeter's own printed brand logo as an unambiguous test (flipping the image made
the logo unreadable, proving the un-flipped version was right). Also fixed, on your own
suggestion, so the guide box and recording keep running even after the control panel is
collapsed out of the way.

---

## Phase 4 — On-device test session (reduced, 6 sessions)

*Full detail: `android/docs/Segment35_Phase4_Results.md`*

You held the phone through 6 short recordings (old app vs. new app vs. new app with locked
exposure; each on a stand and handheld) with a real pulse oximeter in view, while I drove
the app and logged results.

**Headline result: the app's SpO2 (blood oxygen) reading is confirmed, on real data, to be
essentially a fixed number** — it moved less than 0.15 percentage points across all 6
recordings while your actual oximeter reading moved 3 points. This matches an earlier
finding from public datasets, now confirmed with your own phone and oximeter. **Heart rate
accuracy was inconsistent** — no version of the app was clearly best across only 6 short
recordings, though handheld was worse than stand-mounted on average, matching your original
complaint. The locked-exposure feature re-locked itself every 20-35 seconds in real use,
which likely hurt its own results by repeatedly restarting the measurement.

One real deviation, disclosed plainly: the software I used to control the phone remotely
only works over USB, so "handheld" sessions kept the cable connected rather than truly
unplugged — we tested motion, not the separate question of whether charging affects
performance.

---

## Phase 5 — SpO2 calibration

**Not started.** This needs a dedicated data-collection session (breath-holding while
recording, with the oximeter visible) that wasn't part of this session's scope. The
recording/photo-capture tooling for it (Phase 3, above) is built and proven working; the
actual data collection is the next step whenever you're ready to schedule it.

---

## What changed in the shipped app

**Nothing was promoted to a new default.** Every change above is either a fix already
verified not to make things worse (kept), or a candidate still marked "needs more testing"
(not turned on by default). The displayed SpO2 formula is unchanged — this session adds more
evidence that it needs a real calibration (Phase 5), not that it's currently broken code.

## Recommended next steps, in order

1. Finish Phase 1's MATLAB evaluation on the 20-subject motion pool (automatic, no
   supervisor input needed) before deciding whether to port the new ROI to Android.
2. Tune the re-lock feature's sensitivity using the real numbers from Phase 4, then repeat
   just the two "locked" sessions to check.
3. Schedule a real breath-hold data-collection session (Phase 5) — this is the only way the
   SpO2 reading can become a real measurement instead of a near-constant number.
