# PATCH 0.1.7: One UI customization options for the Picker (Jonathan, 2026-11-21)

Source: Jonathan's three S24+ screenshots (Home screen settings, drawer with sort
menu, drawer), saved in design-reference/ with the -2026-11-21 suffix. His words:
"Don't forget to add these customization options."

## What maps to DuoPanel (the Picker is DuoPanel's drawer)

Current state (verified in code): AppRepository sorts alphabetically, hardcoded;
grid columns are width-derived (4 panel / 5 wide); icon size fixed 52/56dp;
labels always on; search pill always on; no display prefs exist in DuoPrefs.

Add a "Picker display" group in SettingsActivity, One UI grouped-card style
(subtitle rows, pill switches, per the home-settings reference), backed by new
DuoPrefs keys, and apply the prefs in BOTH PickerActivity and the StageActivity
picker pane:

1. **App size** (mirrors "App size on Home and Apps screens"): a 3-step slider,
   Small / Medium / Large, driving the Picker grid icon size (about 48 / 56 /
   64dp). Default Medium (current look).
2. **Show app labels** (mirrors "Show app labels on Home screen"): pill switch,
   default ON. Off hides the label text under Picker grid icons.
3. **Picker grid** (mirrors "Apps screen grid"): Auto / 4 columns / 5 columns.
   Default Auto (today's width-derived behavior). A forced value wins over Auto
   in both the standalone Picker and the Stage pane.
4. **Sort order** (mirrors the drawer sort menu): Alphabetical / Custom.
   Default Alphabetical (today's behavior). Custom uses a persisted package
   order list in DuoPrefs: first switch to Custom seeds it from the current
   alphabetical order; in Custom mode, long-press drag in the Picker All Apps
   grid reorders, and the order persists across opens; newly installed apps
   append at the end; uninstalled packages drop out. Settings carries a
   "Reset custom order" row (reseeds alphabetical). Favorites keep their
   existing behavior and are not part of the custom order.
5. **Show search bar** (mirrors "Show search on Home screen"): pill switch,
   default ON. Off hides the bottom search pill in the Picker (filtering via
   the header search affordance, if any, is out of scope).

## What does NOT map (do not fake these)

Home screen layout, Home screen grid, Folder grid, Add media page / Google
Discover, and featured-widget labels belong to One UI Home itself. DuoPanel
does not own the home screen, folders, or widgets, so 0.1.7 does not add them.

## Acceptance

- versionName 0.1.7, versionCode 8; build clean on the PC lane; install -r on
  the Duo; Device Assistance untouched at 1.29.0.
- Screenshots proving each option live: labels OFF vs ON; grid forced to 5 on a
  panel-width Picker; App size Large; Custom order after a drag, still in that
  order after closing and reopening the Picker; search bar hidden when off.
- Defaults reproduce today's look exactly (Medium, labels on, Auto, Alpha, on).
- BUILD_NOTES section 12; VM canonical sources synced; GitHub backup pushed
  after verification per the standing rule (if the approval times out, leave
  staged and report, do not retry blindly).

## Part B (added by Jonathan, 2026-11-21 11:45 EST): Hide apps + notification badges

Two more screenshots (oneui-hide-apps-2026-11-21.png,
oneui-app-icon-badges-2026-11-21.png). His words: "Hide apps too setting and
notification badges."

6. **Hide apps**: a Hide Apps screen from Settings, mirroring One UI's: a
   "Hidden apps" section on top showing currently hidden apps, then the full
   app list with checkboxes and a Done action. Hidden packages persist in
   DuoPrefs. Hidden apps disappear from the Picker everywhere: All Apps grid,
   Favorites (a hidden favorite is skipped, not deleted), search results, and
   the StageActivity picker pane. They still launch normally from One UI Home;
   this only affects DuoPanel's Picker, and the Settings screen says so.
7. **Notification badges on Picker icons**: settings group mirroring his
   screenshot: Badges on/off (default OFF until access granted), Badge style
   Dot / Number (radio rows, default Dot, matching his phone). Counts come
   from a NotificationListenerService in DuoPanel reading active notifications
   per package; that requires the one-time system "Notification access" grant,
   and the Settings group shows the grant state with a row that opens the
   system notification-access settings. No grant = no badges and the UI says
   why; never fake counts. Badges render on Picker grid cells (and the Stage
   pane): Dot = small Samsung-blue dot at the icon's top-right; Number = pill
   with the active-notification count, 99+ capped. The listener is a bound
   system service only; it stores counts in memory, persists nothing about
   notification content, and DuoPanel still ships no INTERNET permission.
   "Notifications on app icons" (long-press peek) does not map to the Picker;
   long-press is already drag-reorder in Custom sort mode. Noted, not faked.

Acceptance additions: screenshot of the Hide Apps screen with one app hidden
and that app absent from the Picker; badge screenshot on the Duo after
granting notification access via adb (cmd notification allow_listener) with a
live notification present (e.g. from Device Assistance or a test post),
showing Dot, then Number style.

## Part C (added by Jonathan, 2026-11-21 11:56 EST): Blur amount reference

Two more screenshots (oneui-drawer-blur-2026-11-21.png over
oneui-home-wallpaper-2026-11-21.png). His words: "Blur amount reference."
The One UI drawer over his Daisy wallpaper is a STRONG blur: the photo reads
only as soft light shapes and tones, no detail, and the wallpaper's tones come
through rather than being crushed under a dark tint. Tune the Picker /
Companion / Stage blur treatment to that strength: raise the cross-window
blur radius well above the current 56px (target the 100-140px range on the
Duo's density, adjust by eye against the reference) and lighten the navy
scrim if the result reads darker than the reference over a light background.
Keep the API < 31 / blur-disabled fallback behavior unchanged. Verify with a
screenshot of the Picker over a detailed screen (the Duo's launcher over its
wallpaper, or over Chrome content) and compare shape softness to the
reference qualitatively in BUILD_NOTES.

Calibration for item 1 (Jonathan, 2026-11-21 11:57 EST): he sent drawer
screenshots labeled Medium and Large (oneui-drawer-icons-medium-2026-11-21.png,
oneui-drawer-icons-large-2026-11-21.png). Calibrate the App size steps so the
Medium step matches his Medium drawer and the Large step matches his Large
drawer (icons visibly ~15-20% larger in a 4-column grid, labels scaling
slightly with them). Small sits one step below Medium by the same proportion.
