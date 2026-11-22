# PATCH 0.1.10: companion pane names the other panel's app again (2026-11-21)

Direction: the 0.1.9 smoke test (smoke-2026-11-21/) found the Stage companion
pane showing state text only: "App list active / Right Panel" in the plain
RIGHT entry and "App running / Right Panel" in the over-app entry, with no
app name. Earlier verified builds named the app in the companion's text
(0.1.6 verification: companion naming Chrome; clean retest: "Chrome, Left
Panel, Active app"). Jonathan, 2026-11-21 21:38 EST: "Yes fix it."

## Diagnosis

The rendering code was never broken. StageActivity.buildCompanionPane has
two branches for its "other app" row: when `pairedPackage` is non-null it
renders the app icon, the label from AppRepository.getAppLabel (which
resolves through PackageManager), and the subtitle "«Other Panel» • Active
app"; when it is null it renders the generic fallback ("App running" /
"App list active"). The defect is that `pairedPackage` ends up null in the
failing entry paths, for a structural reason:

- pairedPackage has exactly two sources: the EXTRA_PAIRED_PKG intent extra,
  and SoloLauncher's active-panel-app records. No shipped Stage entry path
  sets the extra (the panel shortcuts built by ShortcutHelper.buildStageIntent
  pass no paired package, and there is no in-app over-app launcher).
- The records lived ONLY in process memory (SoloLauncher.activePanelApps, a
  plain MutableMap). Picks do record into it (StageActivity.performAppPick,
  PickerActivity, SoloLauncher.launchSoloShortcut), but the pick flow
  finishes the Stage and Android reaps the DuoPanel process, so the next
  entry starts cold with an empty map.
- Device proof from the 0.1.9 smoke run's own launch log: STAGE_PICK of
  Calendar at 20:43:52, then the plain RIGHT entry at 20:44:49 (fresh
  process) logging STAGE_OPEN paired=none, and both over-app STAGE_OPENs
  logging paired=none. In the 0.1.6-era verifications the naming worked
  only because the launch and the Stage entry happened inside one
  long-lived process, so the map was still warm.
- Not a 0.1.9 regression: the 0.1.9 StageActivity diff touched blur and
  insets only. CompanionPanelActivity shares the same pattern (explicit
  extra, else the same engine map), so one fix at the engine covers both.

## Fix

Persist the records. No rendering changes, no behavior changes beyond the
records surviving process death:

- DuoPrefs.kt: new key prefix `active_panel_app_` (per PanelSide name,
  mirroring the existing `strategy_pkg_` pattern) with
  setPersistedActivePanelApp / getPersistedActivePanelApp /
  clearPersistedActivePanelApps.
- SoloLauncher.kt: recordActivePanelApp, getActivePanelApp, and
  clearActivePanelApps now take a Context. Record writes through to
  DuoPrefs; get reads the in-memory map first and falls back to the
  persisted record (backfilling memory); clear clears both. Listener
  notifications unchanged.
- Call sites updated to pass a Context (9): StageActivity (readIntentState,
  syncPairedFromEngine, performAppPick), PickerActivity (intent-state read,
  both formation clears, pick), CompanionPanelActivity (readIntentState,
  syncPairedPackageFromEngine), and SoloLauncher.launchSoloShortcut's
  internal record.
- Honest limit kept: when an app in front was never launched through
  DuoPanel and no record exists, the package is genuinely unknowable
  without usage-stats access, and the companion keeps the state-only text.
  No name is ever fabricated.

## Build state

- versionName 0.1.10, versionCode 11 (app/build.gradle.kts).
- PC lane build: BUILD SUCCESSFUL (gradle assembleDebug --no-daemon
  --max-workers=2). APK dist/DuoPanel-0.1.10-debug.apk, 2,877,686 bytes,
  sha256 c74c50d14a0aca3062c44197ec51b21755f11f62e425b83995c80720e41f3920.
  Sources synced VM to PC and verified byte-exact by SHA-256 on the
  changed files before building.
- INSTALLED on the Duo 2026-11-21 (adb push + pm install -r; read-back
  versionName 0.1.10 / versionCode 11, lastUpdateTime 2026-11-21 21:45:41
  EST). Device Assistance untouched at 1.29.0; alpha panels policy OFF.

## Verification (Surface Duo, DUO-DE v2026.10.28; evidence in fix-0110-2026-11-21/)

- Regression first (LEFT entry): one tap on the search pill gave
  EditText focused="true" (dp0110-focus.xml); `input text Calendar`
  filtered the grid to "1 apps" (dp0110-search.xml/png); tapping the
  Calendar cell launched it via the Stage pick flow (topResumed =
  Calendar AllInOneCalendarActivity), Stage finished.
- Persistence proof: after the pick, run-as prefs read-back shows
  `active_panel_app_LEFT = com.google.android.calendar`. The DuoPanel
  process was then force-stopped; the process that served the next entry
  (respawned by the notification-listener binding) never saw the pick in
  memory.
- (a) Plain entry: RIGHT entry STAGE_OPEN logs paired=com.google.android.calendar
  (0.1.9 logged paired=none at the same step). The companion dump contains
  "Calendar" and "Right Panel • Active app" (dp0110-right.xml), and
  dp0110-right.png shows the companion card reading Calendar with its icon,
  plus the picker header "Paired with Calendar (Left Panel)".
- (b) Over-app entry over Calendar: STAGE_OPEN logs
  paired=com.google.android.calendar and STAGE_CAPTURE_OK (one frame,
  memory only). dp0110-overapp2.png shows the companion card naming
  Calendar beside the captured Calendar frame; the dump contains
  "Calendar" and "Right Panel • Active app" and no "App running" text.
  (The first capture chain ended with the selector's known behavior of
  bringing Calendar itself to the front; re-firing the intent raised the
  already-built Stage task for the evidence shot.)
- Final device state, verified by read-back: prefs at defaults
  (picker_app_size MEDIUM, picker_grid AUTO, picker_sort_order
  ALPHABETICAL, picker_show_labels true, picker_show_search true,
  badges_enabled false, badge_style DOT), hidden_packages empty set,
  flip_sides true (calibration, untouched), active_panel_app_LEFT =
  Calendar (the fix's own record of true state, left in place),
  screen_off_timeout restored to 60000, getprop
  persist.debug.phh.duo.panels = false, DA 1.29.0. Duo debug mode was
  re-armed through the relay before device work (cmd-f61bfeaa, delivered
  21:43 EST, auto-expires 22:43 EST) so test wakes did not fire the wake
  ritual.
