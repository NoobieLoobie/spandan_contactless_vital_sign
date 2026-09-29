# Segment 35 Phase 4 — On-device test protocol

Status: **the reduced 6-session matrix (rows 1, 4, 5, 8, 9, 12) RAN, same session, later the
same day** — Abrar changed his mind ("let's do the option 2 of phase 4") after seeing the
on-device fix-pack smoke test results. See `android/docs/Segment35_Phase4_Results.md` for
the full results, including one real deviation from this protocol (USB stayed connected for
every "handheld" session — see that doc's own note). Phase 5 (SpO2 calibration) is still
blocked on a dedicated breath-hold session, which this round did not attempt.

This protocol exists so the actual on-device session is just Abrar holding the phone while a
Claude Code session watches `adb logcat` and times each recording. Read
`android/docs/Segment35_Android_Fix_Pack_And_Oximeter_Box.md` §4 first — this protocol is
built to answer exactly the six questions listed there.

---

## 0. Decision needed before starting: full matrix or reduced?

The plan's own design is a 3×2×2 = **12 short sessions** (§2 below), 3 minutes each (~36 min
of pure recording, likely 60-90 min including setup/reset between each). A reduced matrix
(e.g. drop the "phone on a stand" arm, since Phase 2's real motivating problem is handheld
use, or drop one build) would cut this roughly in half but leave some comparisons
unanswered. **Say which you want before this session starts** — the rest of this doc works
either way; §2's table just gets fewer rows.

---

## 1. What must be built before Abrar picks up the phone

Three APK variants, each installed as a separate app (different `applicationId` suffix or
just reinstalled fresh before its own sessions — simplest: build one variant, run its
sessions, then rebuild the next variant and run those, since only one variant needs to be on
the phone at a time):

| Build | What it is | How to get it |
|---|---|---|
| **A. Old build** | Pre-Segment-35 `main` (before this session's two commits) | `git checkout <commit before Segment 35>` in a separate worktree, or check out the `v1.2-android` tag/release APK if still installable |
| **B. Fix-pack build** | This session's Phase 2 changes, `useOximetryCapture` OFF | Current `main`, default config |
| **C. Fix-pack + locked-AE-with-relock** | Same as B, `useOximetryCapture` ON (developer panel switch, long-press vitals card) | Current `main`, toggle the switch before each session in this arm |

Build B and C are the SAME APK — the only difference is one developer-panel toggle at the
start of each recording, so only two real APK builds are needed (A and B/C).

Before Abrar's session starts, confirm:
- `./gradlew testDebugUnitTest assembleDebug` passes clean for build B/C (done this session —
  96/96, see the Segment 35 Android doc).
- The calibration recorder (developer panel, long-press the vitals card) is reachable and the
  oximeter guide box appears when the panel is open (Phase 3) — sanity-checked visually once
  before the real matrix, not counted as one of the 12 sessions.
- Abrar's pulse oximeter is charged and its display is easy to read through the guide box's
  inset (adjust `OximeterGuideBox`'s fractions first if the framing is obviously unusable —
  cheap to change, expensive to redo 12 sessions over).

---

## 2. The matrix

12 sessions (or fewer, per §0), each **3 minutes**, oximeter visible in the guide box,
calibration recorder ON for every session (so every session leaves a CSV + a folder of
once/sec crop JPEGs, joinable by sensor timestamp).

| # | Build | Placement | Power | What it's isolating |
|---|---|---|---|---|
| 1 | A (old) | stand | USB | absolute baseline |
| 2 | A (old) | stand | unplugged | thermal/CPU-governor effect alone |
| 3 | A (old) | handheld | USB | motion effect alone (vs #1) |
| 4 | A (old) | handheld | unplugged | both together — the ORIGINAL complaint ("worked when Claude tested it [USB, stand], fails in my hand") |
| 5 | B (fix-pack) | stand | USB | fix-pack vs old, no motion/power confound |
| 6 | B (fix-pack) | stand | unplugged | |
| 7 | B (fix-pack) | handheld | USB | |
| 8 | B (fix-pack) | handheld | unplugged | fix-pack's answer to row 4 |
| 9 | C (fix-pack+relock) | stand | USB | does locked capture help/hurt on top of the fix pack |
| 10 | C (fix-pack+relock) | stand | unplugged | |
| 11 | C (fix-pack+relock) | handheld | USB | |
| 12 | C (fix-pack+relock) | handheld | unplugged | the full stack's answer to row 4 |

**Reduced-matrix option** (if chosen in §0): rows 1, 4, 5, 8, 9, 12 (six sessions) still
answer "does each build fix the original handheld/unplugged complaint," just without the
isolated stand/USB-only comparisons that separate motion from power effects.

**Per-session steps** (same for every row):
1. Confirm the right build is installed (rebuild/reinstall between build groups, not between
   rows within the same build).
2. Toggle placement (stand vs. handheld) and power (plug/unplug) as this row specifies.
3. Open the developer panel (long-press the vitals card). For row group C, also toggle
   "Locked linear" ON and wait for `LOCKED` in the status line before proceeding (rows 1-8
   leave it off, i.e. build A doesn't have the toggle, build B has it but off).
4. Position the oximeter so its display sits inside the guide box; check the live inset
   (top-right) actually shows readable digits — reposition if not.
5. Type a one-word note in the lighting field (e.g. "same room, ceiling light") so all 12
   sessions can be compared under the same recorded condition.
6. Tap **Start rec**. Hold the position (stand) or hold the phone up to your face (handheld)
   for the full 3 minutes. Watch the HR/SpO2 numbers and the oximeter's own display; no need
   to write anything down (the CSV + JPEG crops capture everything).
7. Tap **Stop rec**. Confirm the toast shows a saved file. Move to the next row.

Claude's job during this: watch `adb logcat -s SPANDAN_OXI SPANDAN_CAL RealHeartRateEstimator
LiveSpo2Estimator` for anything alarming (crashes, a `DRIFT` warning that never clears, an
exception), and confirm each session's CSV + crop folder actually appeared via `adb pull`
before moving to the next row — cheap to catch a bad session immediately, expensive to
discover after all 12 are done.

---

## 3. Offline analysis (after the 12 — or fewer — sessions)

Not run this session; the exact steps once real data exists:

1. `adb pull /sdcard/Android/data/com.spandan.app/files/calibration/` — one CSV +
   one crop-JPEG folder per session.
2. For each session, read the oximeter's HR/SpO2 digits from its crop JPEGs (visually, or a
   simple OCR pass if the digits are legible enough — Segment 35 Phase 3's own plan flagged
   this as untested) at a few timestamps spread through the 3 minutes.
3. Align those oximeter readings to the CSV's `hr_bpm_current`/`spo2_current_displayed`
   columns by `elapsed_ms` (same clock), with a few seconds of lag tolerance (a finger
   oximeter averages over several seconds — don't expect frame-exact agreement).
4. Compute HR MAE and SpO2 MAE per session (12 numbers each), plus tick-to-tick jitter from
   the CSV's own per-second HR column (already used this way in Segment 34).
5. Compare across the matrix's rows to answer `android/docs/Segment35_Android_Fix_Pack_And_
   Oximeter_Box.md` §4's six questions directly — e.g. row 8 vs row 4 answers "does the fix
   pack help the original handheld/unplugged complaint," row 12 vs row 8 answers "does locked
   capture help further."
6. Report honestly whichever defaults should change (or shouldn't) — same discipline as
   every other segment in this project. A negative result here (fix pack doesn't help, or
   locked capture doesn't help further) is as reportable as a positive one.
