# DuoPanel design reference — Samsung One UI (Jonathan, 2026-11-21)

Jonathan supplied three screenshots from his S24+ (saved in `design-reference/`) as the design reference for DuoPanel's UI. The Picker, Companion, Settings, and Main screens should look like they belong to One UI, not like a custom app.

## References
- `design-reference/oneui-app-drawer.png` — the One UI app drawer.
- `design-reference/oneui-home-settings.png` — One UI Home screen settings.
- `design-reference/oneui-drawer-sort-menu.png` — the drawer's sort menu card.

## Tokens and patterns to match
- **Background (corrected by Jonathan, 2026-11-21)**: NOT a solid color. The One UI drawer floats over a **blur of whatever is behind it** (the wallpaper / underlying screen), frosted and darkened with a translucent tint so content stays readable. DuoPanel's Picker and Companion must do the same: a transparent/translucent window with Android's blur-behind enabled (FLAG_BLUR_BEHIND / background blur radius on API 31+) plus a semi-transparent dark scrim over the blur. Do not sample a navy hex from the screenshots and paint it solid; the navy in the screenshots is his wallpaper showing through the blur. Cards then sit on that blurred surface, one step more opaque, with large rounded corners (about 24 to 28dp) and generous padding. If blur-behind is unavailable on a device, fall back to the translucent dark scrim alone.
- **App drawer layout (the Picker's model)**: 4-column grid of full-color rounded-square app icons at natural size, white 12sp labels under each icon, wide even spacing. A favorites row at the top separated from the full list ("All apps" divider in the current DuoPanel maps to this). Three-dot overflow at top right.
- **Search**: a single dark pill at the BOTTOM of the drawer, magnifier icon left, "Search" placeholder, mic icon right. (The current Picker has the pill at the top; One UI puts it at the bottom.)
- **Settings layout (DuoPanel Settings and Main)**: grouped cards exactly like Home screen settings: each row is a title with an optional gray subtitle under it (e.g. "Home screen grid / 4X6"), hairline dividers inside the card only, One UI pill switches right-aligned, sliders with round thumbs where a level is needed. Section cards separated by open background space, never bordered boxes inside boxes.
- **Floating menus**: sort/menu popups are a single floating rounded card (about 20dp radius) with plain rows and a check mark on the selected item, no title bar.
- **Type and color**: Samsung's rounded sans (Roboto fallback is acceptable), white primary text, gray secondary text, Samsung blue #0381FE only for links, selection, and active states. Switches follow One UI: light track with white thumb when on in this theme.
- **What NOT to do**: no custom blue-outlined pill buttons in rows (the current Picker header buttons), no boxed section headers with borders, no dense text blocks. If a control exists in the reference screenshots, copy its One UI form.

## Scope
Restyle only: PickerActivity, CompanionPanelActivity, SettingsActivity, MainActivity, LaunchLabActivity where practical. No behavior changes, no flow changes, no new permissions. Ships as v0.1.2 after the 0.1.1 panel-model retest passes.
