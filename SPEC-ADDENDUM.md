# DuoPanel — Addendum v2 (Jonathan's idea, 2026-11-20 00:40 ET) — SUPERSEDES the entry-point design in SPEC.md

Jonathan's words: "an app that opens an app picked, or the existing app picker that matches one ui, when an app is launched it opens the app on the left or right with the app picker on the other side, the app comes with left and right shortcuts available to add to the home screen so I can pin left shortcut in the bottom left corner and right shortcut to the bottom right shortcut"

## Primary entry points: LEFT and RIGHT home-screen shortcuts (this is now THE main flow)
DuoPanel ships two ready-made pinned shortcuts, offered on its first screen with big "Add LEFT shortcut" / "Add RIGHT shortcut" buttons (requestPinShortcut, same mechanism as SPEC.md):
- **LEFT shortcut** — label "Left Panel", icon: two panels with the LEFT one highlighted in One UI blue (#0381FE).
- **RIGHT shortcut** — label "Right Panel", icon: two panels with the RIGHT one highlighted.
- In-app and README placement guidance, in Jonathan's own plan: pin the LEFT shortcut at the **bottom-left corner** of the One UI Home screen and the RIGHT shortcut at the **bottom-right corner**, so each corner opens its side. (Portrait/top-bottom: LEFT means top/primary, RIGHT means bottom/secondary, and the icons/labels still read correctly as first/second panel; note this in About.)

## The flow (per shortcut)
Common: tapping either shortcut opens **PickerActivity fullscreen first**, styled as One UI's own app picker/drawer (see Design). The user picks an app. What happens next depends on the side:

- **RIGHT flow (app goes RIGHT, picker stays LEFT) — the natural adjacent launch:**
  1. Picker is the current activity. On pick, Picker launches the chosen app with FLAG_ACTIVITY_LAUNCH_ADJACENT_TO_RECENT | FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK (+ ActivityOptions split-secondary where honored). The existing Picker shrinks to the LEFT/primary half, the app opens in the RIGHT/secondary half.
  2. Picker STAYS ALIVE on the left in this flow (do not finish). From there the user can pick another app for the left side: picking again in "replace me" mode launches that app from the Picker WITHOUT the adjacent flag (NEW_TASK | MULTIPLE_TASK), which lands it in the Picker's own split slot, and only then does the Picker finish. Picker UI therefore needs two pick modes, shown as a small segmented control at the top: "Open on my other side" (default for the first pick) and, after a split exists, tapping an app replaces the Picker's side. Keep this dead simple: first pick = other side; any pick while already in multi-window = replace my side and finish. Show a one-line hint bar in the Picker stating which will happen ("Next app opens on the RIGHT — tap an app" / "Next app takes this panel's place").
  3. Also add explicit buttons in the Picker header: Swap (relaunch the current pair reversed via SoloLauncher), Full (if a paired app exists, relaunch it fullscreen and finish), Close panel (finish, leaving the app fullscreen-able by dragging the divider).

- **LEFT flow (app goes LEFT, picker stays RIGHT) — needs the re-anchor trick:**
  1. Picker is open fullscreen (launched by the LEFT shortcut). On pick, Picker launches the chosen app NORMALLY (NEW_TASK, no adjacent flag) so it becomes the primary task.
  2. Immediately after, Picker relaunches a fresh Picker instance with FLAG_ACTIVITY_LAUNCH_ADJACENT_TO_RECENT | FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK (+ split-secondary options) intending the new Picker to land in the RIGHT/secondary half beside the app, then the old Picker finishes. Implement this as a tiny AnchorActivity trampoline if a direct self-launch proves awkward in code structure: the sequence is launch app -> start AnchorActivity (translucent, excludeFromRecents) -> AnchorActivity starts PickerActivity with the adjacent flags and finishes.
  3. Fallback if the re-anchor lands wrong on Samsung hardware: the pair still ends up split (app + Picker side by side, sides possibly swapped); the Picker's Swap button fixes sides in one tap. Note this in the Launch Lab checklist, do not over-engineer.
  4. Once split exists, further picks behave as in the RIGHT flow: a pick while in multi-window replaces the Picker's side and the Picker finishes.

Per-app Solo shortcuts and Pair shortcuts from SPEC.md remain, as secondary sections below the Left/Right buttons. The trampoline strategies A/B/C/D in SPEC.md still govern per-app Solo and Pair launches, and SoloLauncher is still the shared engine; the Left/Right flows are implemented in SoloLauncher as `launchPickedApp(activity, pkg, side)` and `reanchorPicker(activity, side)` so Launch Lab can test them.

## Design: the Picker matches One UI
- Follow Samsung One UI visual language (Jonathan's standing design rule): dark theme = true black background, primary #0381FE, Roboto/system font, large rounded "focus block" containers (about 26dp corner radius for grouped blocks, 20/12dp nested), viewing area on top (search + favorites), interaction area at the bottom, generous margins (>=24dp on the wide screen).
- Picker layout mirrors the One UI app drawer: pill-shaped search bar at top ("Search apps"), a Favorites focus block, then a grid of app icons with labels (4-5 columns on a half panel, adaptive), rounded icon shapes. Header shows "Left Panel" or "Right Panel" with its panel icon so the user always knows which side they are filling.
- No Material You color overrides, no purple. Keep it looking like a Samsung system panel.

## Launch Lab additions
Add tests: "Left flow test" (pick an app, run the LEFT sequence, log each step's return), "Right flow test", plus the existing A/B/C. Log entries must record which flow/strategy ran and the side requested.

## Acceptance additions
- Manifest/Main screen offers Add LEFT and Add RIGHT shortcut buttons that successfully call requestPinShortcut with distinct intents (extras: side=LEFT / side=RIGHT) and distinct icons.
- PickerActivity reads the side extra, renders the correct header, and implements first-pick/other-side vs in-split/replace behavior above.
- README's setup section leads with the Left/Right corner-pin setup (bottom-left / bottom-right), per-app Solo shortcuts second.
