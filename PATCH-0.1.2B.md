# DuoPanel PATCH 0.1.2B — the background is a blur, not a color (2026-11-21, Jonathan's correction)

Applies on top of PATCH-0.1.2 (already implemented). Same build: versionName "0.1.2", versionCode 3. UI only, no behavior or flow changes, no new permissions or dependencies.

## Problem
The 0.1.2 restyle painted the Picker and Companion backgrounds solid #0B0E1A. In the One UI reference screenshots, the drawer background is a frosted BLUR of whatever is behind it (the wallpaper), darkened with a translucent tint. Jonathan corrected this explicitly: "The background is a blur of the background not the actual color."

## Changes
1. **PickerActivity and CompanionPanelActivity windows become translucent with blur-behind**:
   - Give both activities a translucent window: root background becomes a semi-transparent dark scrim (sample: #0B0E1A at about 72% opacity, i.e. alpha ~0xB8) instead of the solid color, and the window itself must not paint an opaque background behind the root view (transparent window background drawable in code or theme).
   - Enable Android cross-window blur on API 31+: set the window background blur radius (Window.setBackgroundBlurRadius, around 48 to 64px) and the blur-behind layout flag/attribute as the platform requires; guard everything behind an SDK_INT >= 31 check and a runtime isCrossWindowBlurEnabled check where the API offers one.
   - Graceful fallback: on devices or states where blur is unavailable (older API, blur disabled system-wide, power saver), the translucent scrim alone over the underlying content is acceptable; if even translucency causes artifacts in split, fall back to the previous solid #0B0E1A. Never crash or leave a fully transparent unreadable surface: text and icons sit on the scrim and on the grouped cards, which stay as opaque as in 0.1.2.
   - Cards, search pill, grid, and all other 0.1.2 styling stay exactly as implemented.
2. **SettingsActivity, MainActivity, LaunchLabActivity, AboutActivity keep their solid backgrounds** (One UI settings screens are solid dark; the blur is a drawer/panel-surface treatment only).
3. **Manifest/themes**: if a theme change is needed for translucency, add a dedicated translucent theme for PickerActivity and CompanionPanelActivity only (windowIsTranslucent, transparent window background, no dim behind beyond what the scrim provides). Do not change any launchMode, task affinity, or resizeable flags.
4. **Docs**: README and BUILD_NOTES v0.1.2 section note the blur treatment and its fallback; PROGRESS.md append marked DONE.
