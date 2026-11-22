# DuoPanel PATCH 0.1.3 — calibration guard + companion actually docks (2026-11-21)

Version: versionName "0.1.3", versionCode 4. No new permissions or dependencies. Do not change the Duo default behavior model, the pick modes, or any pref keys.

## Evidence from on-device self-testing (Surface Duo, DUO-DE Android 16, StageSplit, 2026-11-21)
Read these facts before designing; they are measured, not theorized:
1. After a corner formation, `dumpsys activity activities` showed: SplitRoot with children "side" (holding the Picker task, multi-window) and "main" (sz=0, EMPTY). The CompanionPanelActivity task was a standalone FULLSCREEN task, NOT inside the SplitRoot. Its window frame was [0,0][2700,1800]; the Picker frame was [0,0][1338,1800].
2. The re-anchor formation path (used when the effective side is RIGHT) logged: PANEL_PAIR_FORMED with flags 0x14000000 (NEW_TASK|CLEAR_TOP), opts "normalPrimary + reanchorPicker(RIGHT)", then LEFT_FLOW_STEP2_ANCHOR, then REANCHOR_SPAWN_PICKER with flags 0x18101000. Result: the companion launched as a normal primary BEFORE any split existed, so it became a fullscreen task; only the re-anchored Picker entered a split stage, paired with an empty stage.
3. The direct formation path (effective LEFT) logged PANEL_PAIR_FORMED with flags 0x18101000 and opts "reflection:fallback(NoSuchMethodException)": the ActivityOptions windowing-mode bundle reflection FAILED on this device (Android 16 / SDK 36), so only the intent flags ever do work. Do not rely on the reflection bundle anywhere; keep it only as a harmless extra attempt.
4. The side auto-calibration fired while the Picker window was SPANNED full-width (log: view=2700x1800, centerX=1350) and flipped the sides based on the display center. A spanned window carries no side information.

## Part A — calibration guard
In PickerActivity's auto-calibration: only measure when the Picker is actually confined to about one half of the display: the window width must be between 30% and 65% of the current display width AND the activity must be in multi-window mode. If the window is wider (spanned/fullscreen) or not in multi-window, skip calibration silently (no log entry, or one CALIBRATION_SKIPPED entry with the measured width). Never flip based on a spanned window.

## Part B — formation rework: the companion must END UP inside the split
Goal state after a corner formation from the home screen, for BOTH entries and with flip on or off: the SplitRoot holds BOTH DuoPanel tasks, one per stage; the Picker window frame equals its panel half; the Companion window frame equals the other panel half; NO DuoPanel task remains fullscreen.

Current defect: each path gets only one activity into a stage. The re-anchor path strands the companion fullscreen; the direct path relies on a single adjacent launch from a fullscreen caller.

Required approach (two-phase formation, driven by the activity that ends up confined):
- Phase 1: get the Picker confined into a split stage using the mechanism that already works on this device (the adjacent/anchor spawn that produced a staged Picker in the evidence above).
- Phase 2: once the Picker is in multi-window (use onMultiWindowModeChanged / a resume-time check with a short retry), the CONFINED Picker launches the Companion with the adjacent flag set (NEW_TASK | MULTIPLE_TASK | LAUNCH_ADJACENT | LAUNCH_ADJACENT_TO_RECENT) so the Companion docks into the OTHER stage. If a Companion instance is already running fullscreen from phase 1, finish it first or reuse/relaunch it so exactly one Companion exists, inside the split.
- Apply the same two-phase shape to both entry sides (mirror the code path; the anchor re-anchor trick may remain as the phase-1 mechanism for the side that needs it).
- Solo launches that show the companion on the free panel use the same docking helper.
- Keep PANEL_PAIR_FORMED / PANEL_PAIR_FAILED logging, and log the phase-2 launch as PANEL_PAIR_PHASE2 with its flags.

## Part C — formation self-check in the log
About 1.2s after a formation completes, record one log entry FORMATION_CHECK with: the Picker's window frame (via its root view location + current window metrics, or decor view), whether the Picker is in multi-window, and the same for the Companion if an instance is reachable (a shared in-app registry in DuoPrefs or a static holder is fine). This gives the Launch Lab the data to prove docking without adb.

## Docs
README + BUILD_NOTES: add the v0.1.3 section (marked PENDING VERIFICATION BY MUSE) describing the guard and the two-phase formation; PROGRESS.md append marked DONE.
