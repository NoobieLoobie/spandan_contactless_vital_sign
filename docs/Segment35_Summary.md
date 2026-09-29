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


---

## Addendum — pre-demo safety fix (2026-09-29, morning of the presentation)

One additional short, safety-first session ran directly before the final presentation
(live demo 2026-09-30), scoped to a single low-risk item rather than any new investigation.

**What prompted it**: Phase 4 session 8 was thought to show a 153 bpm reading against an
oximeter range of 79-85 bpm — a possible harmonic-doubling failure. **On closer check, this
was not confirmed**: session 8's own CSV never logs HR above 130 bpm (it stays 64-105 bpm
throughout), and the oximeter range compared against had been mixed up with session 4's.
The theory has no confirmed supporting evidence from this project's own data.

**What was still added, as a precaution**: a small, classical (no ML/DL) subharmonic-
preference guard in `HeartRateFft.estimateBpm` — if a spectral peak near half the top
peak's frequency holds a substantial share of its power and falls in a plausible
resting-HR range, the lower (subharmonic) reading is preferred. This is a standard rPPG
correction, not new architecture. Known, disclosed risk: it could incorrectly halve a
genuine ~120 bpm reading if a strong 1 Hz component happens to be present. 4 new unit
tests pass; the full suite is 100/100; the app builds and runs. Committed as `694daa2`.

**One further on-device confirmation session** (107s, handheld, Galaxy A35, USB connected)
ran cleanly afterward — no crash, HR stayed in a plausible 67-96 bpm range — but without a
legible oximeter reading to compare against, so this confirms the build didn't break, not
that the guard fixed anything real. Committed as `225bd20`. **Say so plainly if asked**:
the guard is precautionary, not a confirmed fix for an observed failure.

**Scope reminder, restated per Abrar's own clarification**: the oximeter viewing box
(Phase 3) exists solely as an internal tool for Abrar's own future ground-truth data
collection toward Phase 5's SpO2 calibration. It is not part of the demo and should not be
shown to the course supervisor.

---

## Addendum 2 — Phase 1 finished, and promoted despite an inconclusive motion test (2026-09-29)

This updates two things stated above as open: the "Recommendation: worth finishing" line in
Phase 1, and "Nothing was promoted to a new default" in "What changed in the shipped app."
Both are now out of date. Read this addendum alongside them, not in place of them (the
history above stays visible, per this project's own record-keeping convention).

**The motion-pool test finished.** After this document was first written, the same
resumable script processed 19 of the 20 motion-condition subjects it had not yet reached
(the 20th was left for a later session). The result points the same direction as Phase 1's
main finding — roughly 40% lower heart-rate error with the new ROI — but **the formal
statistical test on this specific motion result did not reach significance** (in plain
terms: with this many subjects, and this many of them scoring identically either way, the
improvement could plausibly be chance, even though the raw numbers look good). This was the
exact test Phase 1 was designed to pass before recommending a change.

**The new ROI was made the default anyway, by direct instruction, after seeing this exact
result.** This affects the MATLAB research pipeline only — it is a research/desktop tool,
not the phone app. **The Android app you will demo tomorrow is unaffected**: this ROI was
never ported to the phone, and nothing about tomorrow's demo changes because of this
promotion. The reason to record this plainly: if asked how confident this specific
improvement is, the honest answer is "promising and consistent across two test pools, but
the motion-specific test itself came back inconclusive, and it was adopted as a judgment
call, not because every test passed."

One more measurement was also added this same session: how well the waveform-shape
(morphology) branch tracks a real reference pulse signal under the new ROI, on the 5
subjects with that reference available. Unlike the notch-detection pass/fail rate (which
dropped noticeably), this waveform-tracking measure barely moved either direction — a much
smaller, mixed effect than the notch number alone might suggest.

Full technical detail: `matlab/docs/Segment35_MediaPipe_Anatomy_ROI.md` §3.3-§3.6.
