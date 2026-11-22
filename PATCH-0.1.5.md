# DuoPanel PATCH 0.1.5: companion affinity + opaque companion theme

Target: versionName 0.1.5, versionCode 6. Builds on 0.1.4 as-is. Formation logic (two-phase, anchor relay, bounded retry, FORMATION_CHECK, calibration guard) stays EXACTLY as in 0.1.4. This patch changes only how CompanionPanelActivity is declared and themed. No new permissions, no new dependencies, no pref key changes, no behavior changes outside this.

## Evidence this patch is based on (all measured on the test device, DUO-DE Android 16, StageSplit, policy OFF)

- Real third-party apps (Chrome, Gmail, DeskClock, Maps) dock into BOTH split stages through DuoPanel flows and stay there. StageSplit on this ROM can hold two tasks.
- PickerActivity (translucent theme, default app task affinity, exported) reliably holds a split stage: confined at [0,0][1338,1800], inMultiWindow=true, in every run from 0.1.1 to 0.1.4.
- CompanionPanelActivity has NEVER held a stage in any run: launched adjacent from a confined picker it lands fullscreen and collapses the split (0.1.3); launched plainly from a multi-window anchor that sat in the empty stage it still lands fullscreen (0.1.4 relay); launched adjacent as the retry it enters the side stage but EVICTS the picker from the split entirely (0.1.4 retry: companion alone in side stage, main stage empty, picker task fullscreen and invisible). dumpsys activity activities is the ground truth for all of these; the app's own FORMATION_CHECK frame readings on this ROM are unreliable (it reported bothDocked=true for the eviction state, with both frames read as the same off-display rect [2700,0][4050,1800]).
- The two remaining structural differences between the Companion and everything that docks: (1) it shares the app's default taskAffinity with PickerActivity; (2) it uses the translucent window theme (Theme.DuoPanel.TranslucentPanel, windowIsTranslucent=true). Real apps that dock are opaque and carry their own affinity.

## Changes

1. AndroidManifest: give CompanionPanelActivity its own taskAffinity, the string com.jonathancruzate.duopanel.companion. Nothing else in the manifest changes.
2. AndroidManifest: change CompanionPanelActivity's theme from Theme.DuoPanel.TranslucentPanel to the app's solid Theme.DuoPanel.
3. CompanionPanelActivity code: remove any runtime dependence on being translucent (for example any blur-behind window setup it performs for itself); its content root should paint the solid One UI background color (the deep navy-black from DESIGN-REFERENCE.md) so the panel looks the same as before minus the see-through. If the activity currently sets a transparent or scrim background on its root view, set the solid color instead. Do not change its layout, buttons, or behavior.
4. PickerActivity keeps the translucent theme and its blur exactly as is. It docks fine and its look is unchanged.

## Acceptance (verified on device by dumpsys, not by the app's own check)

From home, for each entry (LEFT and RIGHT) with flip on: after formation settles, dumpsys activity activities shows the SplitRoot with BOTH stage roots occupied: one stage holding the Picker task (mode=multi-window), the other holding the Companion task (mode=multi-window), and no DuoPanel task in mode=fullscreen.
