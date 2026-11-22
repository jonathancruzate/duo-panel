# DuoPanel PATCH 0.1.2 — One UI restyle + Home button renamed to Clock (2026-11-21)

Applies on top of v0.1.1 (patches 0.1.1, 0.1.1B, 0.1.1C). New version: versionName "0.1.2", versionCode 3. UI ONLY: no behavior, flow, flag, permission, or dependency changes. Framework-only, views are built in code as they are today (OneUiViews.kt helpers plus per-activity layout code).

## Part 1 — rename Home to Clock (Jonathan approved 2026-11-21)
The button currently labeled Home in PickerActivity and CompanionPanelActivity only shows or hides the big clock and date block on that panel (homePanelClockExpanded). Rename the visible label to **Clock** in both places, and rename the field/method names accordingly (e.g. panelClockExpanded). Update any UI copy, README, and BUILD_NOTES mentions of that button. Do not change what the button does.

## Part 2 — One UI restyle (design reference: DESIGN-REFERENCE.md + design-reference/ screenshots)
Read DESIGN-REFERENCE.md in the project root first and follow it. The three screenshots are from Jonathan's S24+ One UI: the app drawer, Home screen settings, and the drawer sort menu. DuoPanel should look like it belongs to One UI.

- **Colors**: background deep navy-black (sample the screenshots, roughly #0B0E1A); cards one step lighter (roughly #1A1E2E) with 24 to 28dp rounded corners and comfortable padding; white primary text, gray secondary text; Samsung blue #0381FE only for links, selection, and active states.
- **PickerActivity as the One UI drawer**: 4-column grid of full-color app icons with white labels underneath and even drawer-like spacing; Favorites as the top group like the drawer's favorites row, then the All Apps group; the search pill moves to the BOTTOM of the screen (magnifier icon, Search hint, mic icon optional if it does nothing, better to omit the mic than fake it); the Swap / Full / Clock / Close actions become a simple top row with the three-dot style overflow where the reference uses one, or a plain icon row, but NOT blue-outlined pill buttons. The mode toggle (Open on other side / Replace this panel) becomes a One UI segmented control or a settings-style row, visually quiet.
- **CompanionPanelActivity**: one grouped card on the navy background: side graphic, clock block, other-panel app row (icon + name like a settings row), and the Pick app / Swap / Full / Clock / Close actions as settings-style rows or a quiet button row inside the card, no outlined blue pills.
- **SettingsActivity and MainActivity**: rebuild the layouts as One UI settings screens: grouped cards, each row a title with an optional gray subtitle, hairline dividers inside cards only, One UI pill switches for toggles, plain text rows for actions. Keep every existing setting and action working with the same prefs and handlers; only the presentation changes.
- **LaunchLabActivity**: apply the same card/row styling where practical without touching its logging behavior.
- **Floating menus** anywhere they appear: single floating rounded card, plain rows, check mark on the selected item.
- Keep the app functional at phone widths (the Fold8 cover screen and the S24+): the drawer grid stays 4 columns on narrow widths and may go wider on unfolded panels if the current grid code already adapts; do not break the existing span/panel layouts.

## Docs
README: note the One UI styling and the Clock rename. BUILD_NOTES: add a v0.1.2 section marked PENDING VERIFICATION BY MUSE. PROGRESS.md: append a step marked DONE.
