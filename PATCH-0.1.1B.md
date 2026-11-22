# DuoPanel PATCH 0.1.1B — Duo window management is the default (2026-11-21, Jonathan's order)

Applies on top of PATCH-0.1.1 (already implemented). Same build: versionName stays "0.1.1", versionCode stays 2. No new permissions, no new dependencies, framework-only, and do not change the launch flags or strategies of the existing flows.

## Principle
Out of the box, DuoPanel mimics Surface Duo window management: an app opens on ONE panel only; the next app goes to the OTHER panel; the free panel shows the app picker by default; spanning happens only via the explicit Full action; swap is one tap. Today the Left/Right panel flows already behave this way, but a per-app Solo shortcut launches its app into split WITHOUT bringing the picker up on the free panel. This patch closes that gap and makes the default explicit in Settings and the README.

## Changes
1. **DuoPrefs**: add `pickerOnFreePanel: Boolean` (default **true**) with getter/setter, persisted in the existing prefs.
2. **Solo launches pair with the Picker when split engages** (wide window and SplitMode allows split) and `pickerOnFreePanel` is true:
   - Side LEFT: launch the target app as primary exactly like the existing LEFT flow step 1, then reuse the existing AnchorActivity re-anchor mechanism to spawn PickerActivity adjacent on the RIGHT, paired with the target package. Log `SOLO_WITH_PICKER` (side LEFT) in the Launch Lab log.
   - Side RIGHT: start PickerActivity carrying a new extra `EXTRA_AUTO_TARGET` = target package and side RIGHT; PickerActivity, once created and resumed the first time, immediately performs the same action as a manual pick of that package in the RIGHT flow (target launches adjacent, Picker stays alive on the left). Guard so it fires exactly once per Picker instance. Log `SOLO_WITH_PICKER` (side RIGHT).
   - Reuse existing SoloLauncher functions (the LEFT flow steps, reanchorPicker, launchFromPicker) instead of inventing parallel flag logic.
   - When `pickerOnFreePanel` is false, or split does not engage (narrow/cover screen, SplitMode OFF), Solo behavior is exactly what it is today.
   - Pair shortcuts are unchanged.
3. **SettingsActivity**: add a switch row "Picker on the free panel", subtitle "Duo style: when an app opens on one panel, the app picker waits on the other panel so the next app is one tap away." Default ON, wired to the new pref. Also add a small non-interactive info row near the top of Settings: "Default behavior mimics Surface Duo window management: one panel per app, picker on the free panel, span only via Full."
4. **README**: in the setup/defaults area, state plainly that the default behavior mimics Surface Duo window management (one panel per app, picker on the free panel by default, span only via the Full button, one-tap swap), and mention the Settings toggle for turning the free-panel picker off.
5. **BUILD_NOTES**: extend the 0.1.1 section (Section 6) with a short "Duo defaults (patch 0.1.1B)" subsection, still marked PENDING VERIFICATION BY MUSE. Append a PROGRESS.md step for 0.1.1B marked DONE.
