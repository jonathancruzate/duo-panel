# DuoPanel — Solo (one-panel) launch for Galaxy Z Fold8

Owner order (2026-11-20): "Build a solution for the one panel by default using agy that works with the default launcher without root"

## Problem
Surface Duo 1 had two logical displays: tapping an app filled ONE panel, and a second app / picker lived on the other panel. Galaxy Z Fold8 has ONE continuous inner display, so One UI opens apps full-screen by default. Research (2026-11-20) confirmed no Play Store or GitHub app restores true one-panel-by-default, and the Duo Microsoft Launcher does not transfer.

## Honest scope (swarm triage 2026-11-20, Mode B, 3 seats)
- Verdict: Yes-with-conditions as a curated **Solo shortcuts** product. NOT a universal system default, and the app/README must never claim it intercepts all launches.
- Real One UI Home icons in the drawer, taskbar, Recents, notifications, and intents from other apps still open full-screen. That is an Android/Samsung platform limit without root/launcher replacement, and DuoPanel does not hide it.
- Ship v1 as a spike whose core mechanism must be proven on real Samsung hardware (S24+ split-screen as first proxy, Fold8 on arrival Dec 2-4 as the real gate), with a graceful fallback when split is refused.

## Product: DuoPanel (package com.jonathancruzate.duopanel)
An Android app that is **NOT a launcher** and requires **NO root, NO Shizuku, NO ADB, NO Accessibility permission for its core flow**. It works *with* One UI Home: its shortcuts sit on the default launcher's home screen, and Samsung's taskbar stays.

### Core flow
1. In DuoPanel, the user picks their top apps (target: works well for a curated top 10-20) and taps "Add Solo shortcut". DuoPanel publishes a **pinned shortcut** (ShortcutManager.requestPinShortcut, with a legacy broadcast fallback) onto One UI Home. The shortcut's intent targets DuoPanel's trampoline activity, carrying the target package + label. (Pinned shortcuts carry an Intent, not an ActivityOptions bundle, hence the trampoline.)
2. Tapping a Solo shortcut on One UI Home runs the trampoline, which tries launch strategies **in order**, then finishes with `finishAndRemoveTask()` / excludeFromRecents so it never pollutes Recents or the back stack:
   - **Strategy A (split-adjacent, primary):** Launch the target app normally (its main launcher intent, FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP as appropriate). Immediately launch PickerActivity with FLAG_ACTIVITY_LAUNCH_ADJACENT_TO_RECENT | FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK and ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY). On devices/apps where One UI honors adjacent launch (the recipe Split Screen Launcher-class apps use on Android 12L+), the target is pinned to one half and the Picker occupies the other half.
   - **Strategy B (split options on the target):** Launch the target itself with ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) + LAUNCH_ADJACENT/NEW_TASK/MULTIPLE_TASK flags, then launch Picker as in A.
   - **Strategy C (freeform half, fallback):** Launch the target with ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM) + setLaunchBounds() sized to the left half (portrait: top half) of the inner display. Do NOT use setLaunchBounds with split windowing mode: Android only honors bounds in freeform.
   - **Strategy D (graceful):** Launch the target normally full-screen and show a short toast: "Split was refused for this app, opened full screen. You can save it as a native App Pair instead." Never crash, never loop.
   Strategy selection: remember per-package which strategy last worked (SharedPreferences), try it first next time. A "Launch Lab" diagnostics screen (see below) lets the user test A/B/C per app manually.
3. **PickerActivity** is the Duo second panel: 
   - Header with the Solo app's name/icon.
   - Buttons: **Swap** (relaunch the pair reversed: Picker's chosen second app / Picker in primary, target in secondary, using the same strategy engine), **Full** (relaunch target full-screen, Picker finishes), **Home panel** (stay as a panel: clock/date, favorite apps grid, search).
   - Favorites grid + searchable all-apps list. Tapping a second app launches it **from the Picker** (the Picker is already in multi-window, so use LAUNCH_ADJACENT flags + split-secondary options) intending to replace the Picker in its half, then the Picker finishes. If the system instead puts it beside the Picker, that is still a valid two-app split and the Picker finishing leaves target + second app.
   - Picker must be fully usable standalone when opened from the app drawer (acts as the home panel).
4. **Cover / narrow behavior:** If the current window width is < 600dp (cover screen, folded, or a phone like the S24+ in portrait full-screen), Solo shortcuts simply launch the target normally. Split strategies only engage on wide windows (>= 600dp) OR when the user forces "always try split" in Settings (needed to test on the S24+, which is narrow but supports split-screen; on the S24+ the test is: Solo shortcut should still attempt Strategy A/B and produce a split, because split on a phone works in portrait top/bottom).
   Implementation detail: the trampoline cannot know the Fold posture before launch reliably, so the setting is: Split mode = Auto (wide only) / Always (test + Fold) / Off. Default Auto, and the first-run setup screen tells Fold users to leave Auto, and S24+ testers to use Always.
5. **Pair shortcuts too:** User can pick two apps and publish a "Pair shortcut" that launches app 1 then app 2 adjacent (same engine). This is the fallback the Skeptic/TPM required: if Solo+Picker is refused for an app, a native-style pair may still work.

### Other screens
- **MainActivity:** Tabs/sections: My Solo Shortcuts (list, add, remove, re-pin), Pairs, Launch Lab, Settings, About/Honest Limits.
- **App picker:** installed launchable apps with icons, search, favorites (star) persisted.
- **Launch Lab:** pick an app, buttons Try A / Try B / Try C / Normal, and a log view of the last attempts (timestamp, package, strategy, result = returned without exception). This is the arrival-day diagnostic.
- **Settings:** Split mode (Auto/Always/Off), preferred side (Left/Right, Top/Bottom in portrait), Picker stays vs Picker auto-replaces (default replace), per-app strategy memory reset.
- **About / Honest Limits:** plain text of the scope limits above.

### Non-goals
- No launcher, no default-home role. No root/Shizuku/ADB/Accessibility in core. No intercepting other apps' launches. No background services except none at all (v1 has zero services/receivers beyond the legacy shortcut-install fallback if used). No network permission. No ads/analytics. No data leaves the device.

### Tech requirements
- Kotlin, single-module Gradle project, **no Gradle wrapper download dependency at build time on the PC** (PC has Gradle 9.3.1 standalone at C:\Users\jonat\devtools\gradle-9.3.1, JDK 21 at C:\Users\jonat\devtools\jdk-21.0.13+9, Android SDK at C:\Users\jonat\devtools\android-sdk with platforms android-36 / 36.1 and build-tools 36). Generate the wrapper if easy, but the project MUST build with the standalone gradle: `gradle assembleDebug --no-daemon`.
- minSdk 29, targetSdk/compileSdk 36. AndroidX AppCompat + RecyclerView only if available from the local Gradle cache / Google Maven; to be safe against offline cache misses, prefer **framework-only + Jetpack WindowManager is OPTIONAL**: do not make the build depend on androidx.window if it risks a download failure; posture detection in v1 can be width-based (smallestScreenWidthDp / current window metrics) with a WindowManager TODO note. Keep dependencies minimal: appcompat is acceptable (proven in the Wear Messages build on this PC), anything exotic is not.
- All activities exported correctly for Android 12+: trampoline + Picker + Main exported=true with intent filters as needed; target app launch intents resolved via PackageManager.getLaunchIntentForPackage, falling back to ACTION_MAIN/CATEGORY_LAUNCHER query.
- Trampoline activity: Theme.Translucent.NoTitleBar, excludeFromRecents=true, taskAffinity="" handling documented in code comments, finishAndRemoveTask in onCreate after launching (with a tiny delay handler if needed for the adjacent launch to register, keep it simple and comment why).
- Icons: simple adaptive icon (two rounded panels, one highlighted) via XML drawable, no image generation needed.
- Unit sanity: code must compile; include a README.md (what it is, honest limits, how to install the APK, Fold8 arrival-day test checklist, S24+ test steps, troubleshooting per strategy) and a BUILD_NOTES.md with the exact PC build command used and its result.

### Acceptance for v1 (verified by Muse, not by the build agent's claim)
1. `gradle assembleDebug` succeeds on the PC and app-debug.apk exists.
2. APK package is com.jonathancruzate.duopanel, versionName 0.1.0.
3. Manifest declares no INTERNET permission, no Accessibility service, no launcher role.
4. README carries the honest-limits section verbatim in substance.
5. Fold8 arrival-day checklist is in README (test Outlook, Chrome, YouTube, OneNote, a non-resizable app; record which strategy worked per app).
