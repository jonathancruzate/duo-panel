# DuoPanel Design Pass 0.2.0 — Picker Sections (`Recent apps` & `Frequently used`)

Visual specification for the two new horizontal app-shelf sections in `PickerActivity` and `StageActivity` (`buildPickerPane()`), plus their configuration card in `SettingsActivity`.

**Scope boundary:** Light palette hex values and accent color presets are owned by the sibling 0.2.0 palette pass. This spec references semantic color roles (`textPrimary`, `textSecondary`, `textAccent`, `cardBg`, `surfaceSubtle`, `hairlineDivider`, `bgScrim`) alongside current Dark values from `OneUiViews.kt` where helpful, and calls out Light-palette-specific behavioral adjustments.

---

## 1. Section Header Treatment

All four drawer sections (`Recent apps`, `Frequently used`, `Favorites`, `All apps`) share one unified header layout so the stack reads as a single One UI app drawer regardless of user ordering.

| Property | Spec | Reference / Rationale |
|---|---|---|
| Container | Horizontal `LinearLayout`, `MATCH_PARENT × WRAP_CONTENT`, `gravity = CENTER_VERTICAL` | Matches existing `favHeaderRow` / `allHeaderRow` in `PickerActivity.kt` & `StageActivity.kt`. |
| Padding | `start = 20dp`, `end = 20dp` from pane edge (`6dp` inside the `14dp` drawer rail); `top = 2dp`, `bottom = 6dp` | Exact match to existing `outerMargin (14dp) + hPad (6dp) = 20dp`. |
| Title text | `"Recent apps"` and `"Frequently used"` (sentence case, single line, `ellipsize = END`) | Matches `"Favorites"` and `"All apps"`. |
| Title typography | `12.5sp`, `Typeface.NORMAL` (regular), `textSecondary` (`#9A9FAE` Dark) | Matches existing headers exactly. Never bold, never all-caps, never accent-colored. |
| Right-aligned note slot | **Empty (`null`)** on both `Recent apps` and `Frequently used` (both populated and ungranted states) | **Argue & standardize:** See rationale below. |
| Right-note typography (where used) | `11.5sp`, `Typeface.NORMAL`, `textSecondary` | Minor cleanup: unifies existing `Favorites` (`11.5sp`) and `All apps` (`12sp`) to `11.5sp` so notes stay subtly subordinate to the `12.5sp` title. |

**Why the right-aligned note slot stays empty on the two new rows:**
- On a capped horizontal shelf, a count (`"8 apps"`, `"10 apps"`) repeats low-value metadata across two consecutive headers while the peeking next cell already communicates overflow.
- In the narrower `StageActivity` picker pane (`~340–420dp`), four stacked right-aligned labels create visual clutter on the trailing edge.
- In the ungranted state, leaving the header right slot empty prevents echoing the `"Usage access needed / Allow"` prompt immediately below it.
- Keep the shared header builder parameterized with an optional `note: String? = null` so `Favorites` keeps `"Long-press app to star"` and `All apps` keeps `"${filtered.size} apps"`.

---

## 2. Horizontal App Row (`Recent apps` & `Frequently used`)

Each populated usage section renders ONE single-row `HorizontalScrollView` containing a horizontal `LinearLayout` of app cells built with the same icon+badge and label visual grammar as `createAppGridCell`.

### Edge Alignment & Full-Bleed Scroll
- To align cleanly with the grids below **without** a hard vertical clipping wall `14dp` from the pane edge, the `HorizontalScrollView` spans full pane width (`MATCH_PARENT`) with `paddingStart = 14dp`, `paddingEnd = 14dp`, and `clipToPadding = false`.
  - Implementation note: move the `14dp` left/right padding off `drawerContent` onto the non-bleeding children (`topBar`, `statusCard`, headers, grids, grant rows), leaving `drawerContent` at `0dp` horizontal padding (keeping `top = 12dp + statusBars.top` and `bottom = 8dp`).
- Each horizontal cell keeps `hPad = 6dp` left and right (matching `createAppGridCell`). At scroll `x = 0`, the first cell's left content edge lands at `14dp + 6dp = 20dp` — flush with the section header text and the first column of `Favorites` and `All apps`.

### Cell Width, Inter-Cell Spacing & Scroll Affordance (Partial Next Cell)
- **Inter-cell gap:** `4dp` (`marginEnd = 4dp` on all cells except the last).
- **Deterministic scroll affordance:** When the strip contains more apps than fit in one viewport, the trailing edge **must always reveal ~40% of the next cell** (`peekFraction = 0.40`, roughly `34–42dp` visible inside the pane edge, exposing more than a third of the next icon plus the start of its label). A fixed cell width fails across foldable/Stage widths by accidentally landing on whole multiples; an exact grid column width (`innerWidth / cols`) hides the next icon behind the `14dp` margin.
- **Adaptive cell width rule** (identical in `PickerActivity` and `StageActivity`):
  1. Let `availDp = viewportWidthDp - 14` (fallback before first layout pass: `screenWidthDp` in `PickerActivity`, `screenWidthDp / 2` in `StageActivity` picker pane).
  2. Define per-`AppSize` cell width bounds (accommodating `iconDp` + `12dp` cell `hPad` + comfortable 1-line label width):
     - `SMALL` (`48dp` icon): `min = 68dp`, `max = 96dp`
     - `MEDIUM` (`56dp` icon): `min = 76dp`, `max = 104dp`
     - `LARGE` (`66dp` icon): `min = 84dp`, `max = 112dp`
  3. Start with `visibleFull = activeGridColumns` (the pane's `computeAdaptiveColumns()` / `paneColumns()`). Compute `cellWidthDp = (availDp / (visibleFull + 0.40)) - 4`.
  4. If `cellWidthDp < min`, decrement `visibleFull` (floor `2`) until `cellWidthDp >= min`. If `cellWidthDp > max`, increment `visibleFull` until `cellWidthDp <= max`. Round final `cellWidthDp` to integer dp.
- **Concrete check across target viewports (`MEDIUM`, `56dp` icon):**

| Viewport | `activeCols` | `visibleFull` | `cellWidth` | Stride (`+4dp`) | Next-Cell Peek |
|---|---|---|---|---|---|
| Narrow Stage pane (`360dp`) | `3` (or forced `4`) | `3` | `98dp` | `102dp` | `~41dp` (~40%) |
| Phone / Fold panel (`412dp`) | `4` | `4` | `86dp` | `90dp` | `~36dp` (~40%) |
| Duo half-screen (`538dp`) | `4` | `5` | `93dp` | `97dp` | `~39dp` (~40%) |
| Unfolded full Picker (`720dp`) | `5` | `7` | `91dp` | `95dp` | `~38dp` (~40%) |

- **When `itemCount <= visibleFull`:** Keep the same computed `cellWidth` and left-align cells from `14dp`. Never stretch 2–3 icons across the pane width.

### Row Height & Cell Internals
- Container and cell height are `WRAP_CONTENT` (never a hardcoded px height, so `AppSize`, `showLabels`, and system font scaling work without clipping).
- **Cell padding:** `hPad = 6dp`, `vPad = 8dp` top and bottom (tightened `3dp` top/bottom from the grid's `11dp` so up to three stacked top sections leave `All apps` visible above the search pill without feeling cramped).
- **Icon + badge:** Identical to `buildIconWithBadge(app, iconSize)` (`48 / 56 / 66dp`, `DOT` or `NUMBER` badge at top-right).
- **Label (when `DuoPrefs.isShowLabels` is true):** Identical to grid cells — `textPrimary`, `labelSp` (`11.5 / 12 / 13sp`), `CENTER`, `maxLines = 1`, `ellipsize = END`, `paddingTop = 6dp`.
- **Resulting nominal row heights** (at `1.0×` font scale):
  - `SMALL`: `85dp` (labels on) / `64dp` (labels off)
  - `MEDIUM`: `94dp` (labels on) / `72dp` (labels off)
  - `LARGE`: `105dp` (labels on) / `82dp` (labels off)

### Scroll & Interaction Behavior
- **Scroll feel:** Continuous free horizontal fling (no forced snap/paging).
- **Chrome:** `isHorizontalScrollBarEnabled = false`; `overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS`; no fading-edge mask (the sharp partial icon at the right edge is the affordance).
- **Item cap:** Display up to `12` apps per strip so the shelf stays scannable and lightweight.
- **Gestures:** Tap launches via `performAppPick`; long-press toggles Favorite (with toast + refresh, matching non-draggable grid cells). Drag-to-reorder remains exclusive to `All apps` in Custom sort mode.

---

## 3. Vertical Rhythm & Unified Drawer Stack

The picker stack (top to bottom) is: `topBar` → `statusCard` → up to 3 visible reorderable sections (default: `Recent apps`, `Frequently used`, `Favorites`) → `All apps` (pinned last).

### Spacing & Divider Rules (Identical in `PickerActivity` and `StageActivity`)
1. **`topBar` → `statusCard`:** `8dp` (`topBar.bottomMargin = 8dp`, unchanged).
2. **`statusCard` → first visible section:** `12dp` (`statusCard.bottomMargin = 12dp`, unchanged). Clearly separates the grouped chrome card from the open drawer stack.
3. **Header → own content (internal affinity):**
   - Header `bottomPad (6dp)` + horizontal cell `topPad (8dp)` = **`14dp`** (or `6dp + 11dp = 17dp` for `Favorites` / `All apps` grids; `6dp + 0dp = 6dp` to the grant prompt's container top edge, `16dp` to its text).
4. **Between any two visible top sections (no divider between them):**
   - Horizontal app section (`Recent apps` / `Frequently used`) uses `bottomMargin = 10dp` → optical gap to next header = `8dp (cell bottom) + 10dp + 2dp (next header top)` = **`20dp`**.
   - Grant prompt section uses `bottomMargin = 10dp` → optical gap from grant container bottom to next header = `10dp + 2dp` = **`12dp`** (`22dp` from text).
   - `Favorites` block (when NOT immediately above `All apps`) uses `bottomMargin = 8dp` → optical gap to next header = `11dp (grid cell bottom) + 8dp + 2dp` = **`21dp`**.
   - **Why no dividers between top sections:** Three stacked hairline rules chop a translucent frosted drawer into horizontal bands—especially in the narrower Stage pane and when two grant prompts appear consecutively. Open `~20dp` spacing cleanly outperforms `14dp` internal affinity (Gestalt proximity) so the top block reads as one cohesive quick-access area.
5. **Single hairline divider directly above `All apps`:**
   - Render **exactly ONE** `1dp` hairline divider (`hairlineDivider` role) immediately above `All apps` whenever **at least one** section above `All apps` is visible (`View.VISIBLE`). Hide it when all three top sections are hidden/empty.
   - **Harmonize Stage:** Add this same single pre-`All apps` hairline to `StageActivity.buildPickerPane()` (which previously omitted the `Favorites` divider), so both full Picker and Stage share one visual grammar.
   - **Divider geometry:** `height = 1dp`, `marginStart = 20dp`, `marginEnd = 20dp` (aligned to header text rails instead of the old `22dp`), `topMargin = 6dp`, `bottomMargin = 10dp`.
   - Remove the bottom margin on the *last* visible top section right above the divider (or fold it so the gap above the hairline is consistently `6dp` below the last top block's own padding, never doubled).

### Visibility & Search Behavior
- **Hidden in Settings:** Section container is `View.GONE` (contributes `0dp`).
- **Usage access granted, zero usage data:** Section container is `View.GONE` (contributes `0dp`).
- **Active search query (`searchQuery.isNotBlank()`):** Hide `Recent apps` and `Frequently used` (`View.GONE`) while filtering so search matches jump immediately to the top without duplicating apps across three shelves (`Favorites` retains its existing filter-and-hide-if-empty behavior).

---

## 4. The Grant Prompt Row

Shown inside `Recent apps` and/or `Frequently used` in place of the horizontal app strip whenever the section is enabled in Settings and Android Usage Access (`PACKAGE_USAGE_STATS`) is not granted.

| Property | Spec |
|---|---|
| Layout | Single-line horizontal `LinearLayout`, `MATCH_PARENT × WRAP_CONTENT`, `minHeight = 44dp`, `gravity = CENTER_VERTICAL` |
| Outer margins | `marginStart = 14dp`, `marginEnd = 14dp` (aligned to the drawer rail; `bottomMargin = 10dp` when followed by another top section, `0dp` when followed by the pre-`All apps` divider) |
| Inner padding | `start = 14dp`, `end = 14dp`, `top = 10dp`, `bottom = 10dp` |
| Surface | Rounded rectangle, `cornerRadius = 16dp`, quiet translucent subtle fill (`cardBg` at ~60% alpha in Dark, `0x991A1E2E`; see Section 5 for Light), **no border/stroke**, **no elevation shadow** |
| Leading copy | `"Usage access needed"` — `13sp`, `Typeface.NORMAL`, `textSecondary`, `maxLines = 1`, `ellipsize = END`, `layout_weight = 1f`, `marginEnd = 12dp` |
| Trailing action | `"Allow"` — `13sp`, `Typeface.BOLD`, **`textAccent`** (accent text/link role only), `maxLines = 1` |
| Tap affordance | Whole row is clickable & focusable (`44dp+` touch target), rounded `16dp` press-state feedback, opens `Settings.ACTION_USAGE_ACCESS_SETTINGS` |

**Tone & narrow-width verification:**
- No warning icon, no amber/red status color, no solid blue pill button. It reads as an honest, low-key placeholder—even when both `Recent apps` and `Frequently used` display it together (`~150dp` combined height including headers).
- Width budget on a narrow `320dp` Stage pane: `28dp` rails + `28dp` inner padding + `~124dp` (`"Usage access needed"`) + `12dp` gap + `~36dp` (`"Allow"`) = **`228dp`** (`92dp` headroom for larger font scales before ellipsizing).

---

## 5. Palette & Accent Discipline

### Accent Inventory (Explicit)
| Element | Uses Accent? | Role |
|---|---|---|
| Picker section headers (`Recent apps`, `Frequently used`) | **No** | `textSecondary` |
| Horizontal strip container & app cell chrome/labels | **No** | Transparent container; `textPrimary` labels |
| Notification badges on horizontal row icons | **Yes** | Follows existing badge accent spec from palette pass |
| Grant prompt row background & `"Usage access needed"` copy | **No** | Translucent `cardBg` / `surfaceSubtle` + `textSecondary` |
| Grant prompt trailing `"Allow"` action | **Yes** | **`textAccent`** (text/link accent role only — no accent fill) |
| Pre-`All apps` hairline divider | **No** | `hairlineDivider` |
| Settings `Picker sections` header, card, row titles/subtitles | **No** | `textPrimary` / `textSecondary` on `cardBg` |
| Settings move up / move down chevrons | **No** | Neutral `textSecondary` (dimmed at bounds) |
| Settings `OneUiSwitch` checked track | **Yes** | Follows palette pass's switch-on accent track role |
| Settings `Usage access` row title | **Yes** | **`textAccent`** (`titleAccent = true`, matching `Notification access`) |

All section chrome remains completely quiet in both Dark and Light palettes: no tinted section cards, no colored header bars, and no accent outlines.

### Light-Palette-Specific Adjustments
1. **Cell & grant-row press feedback:** Flip pressed/ripple highlight from subtle white (`0x1AFFFFFF`, ~10% white) in Dark to subtle dark ink (`0x12000000`, ~7% black) in Light, clipped to a `16dp` rounded rectangle on both horizontal app cells and the grant prompt row.
2. **Grant prompt row fill:** In Dark mode, `cardBg` at ~60% alpha lifts gently off the dark navy frosted scrim. In Light mode, a near-white `cardBg` washes out against a light frosted scrim; use a **cool subtle-ink translucent fill** (`surfaceSubtle` at ~75% alpha, or ~6% dark ink `0x0F000000` over the light scrim) with **no border** so the tappable row stays legible without looking heavy.
3. **Pre-`All apps` hairline divider:** In Light mode, avoid an opaque light-gray divider that looks chalky over blurred wallpaper colors; use a **translucent dark-ink hairline** (`0x1C000000`, ~11% black, or the Light palette's translucent divider token).
4. **Accent text legibility on `"Allow"` and `"Usage access"`:** Bind strictly to the Light palette's contrast-verified **`textAccent`** role (targeting ≥ 4.5:1 against both the grant prompt surface and `cardBg`), never a bright dark-mode-only tint.
5. **Disabled reorder chevrons in Settings:** On Light `cardBg`, raise disabled chevron opacity slightly from `0.28f` (Dark) to **`0.35f`** of `textSecondary` so top/bottom boundary chevrons stay visibly anchored instead of vanishing.
6. **App icons on Light frosted glass:** Do not add per-icon shadows, rings, or backing plates in the horizontal rows; icon treatment stays 1:1 with `Favorites` and `All apps`.

---

## 6. Settings: `Picker sections` Card

Placed in `SettingsActivity.buildSettingsSection()` immediately after the **`Picker display`** card and before **`Notification badges`**, using the exact One UI grouped-card structure of `SettingsActivity.kt`.

### Section Header & Card Shell
- **Header (`OneUiViews.createSectionHeader`):**
  - Title: `"Picker sections"` (`16.5sp` Bold, `textPrimary`)
  - Subtitle: `"Choose which sections appear above All apps and set their order. All apps always stays last."` (`13sp` Regular, `textSecondary`, `topPad = 3dp`, `bottomMargin = 10dp`)
- **Card (`OneUiViews.createGroupedCard`):**
  - `radiusDp = 26`, `fillColor = cardBg`, `verticalPadDp = 6`, `bottomMarginDp = 14`.
  - Rows separated by `OneUiViews.createHairlineDivider(activity, horizontalInsetDp = 20)`.
- **Row sequence inside the card:**
  1–3. The three reorderable sections in their **current persisted order** (default: `Recent apps`, `Frequently used`, `Favorites`). `All apps` is intentionally omitted as a row because it cannot be hidden or reordered; the section subtitle states its pinned position.
  4. `Usage access` permission row (after a `20dp`-inset hairline divider).

### Reorderable Section Row Layout (`Recent apps`, `Frequently used`, `Favorites`)
Matches `createSettingsRow` padding and typography so every row title in `SettingsActivity` sits on the same `20dp` leading rail.

| Region | Layout & Styling |
|---|---|
| Row container | Horizontal `LinearLayout`, `MATCH_PARENT × WRAP_CONTENT`, `gravity = CENTER_VERTICAL`, `padding = (20dp, 14dp, 20dp, 14dp)`. Tapping the row toggles its switch (matching all other switch rows in `SettingsActivity.kt`). |
| Text column (left) | Vertical `LinearLayout`, `width = 0, weight = 1f`, `marginEnd = 8dp`. Title: `16sp` Regular `textPrimary`. Subtitle: `13sp` Regular `textSecondary`, `topPad = 3dp`. |
| Reorder cluster (middle-right) | Horizontal `LinearLayout` (`WRAP_CONTENT × WRAP_CONTENT`), `marginEnd = 10dp`, containing two side-by-side custom Canvas chevron buttons (`Move up` `⌃`, then `Move down` `⌄`) separated by `2dp`. Each button is **`36dp × 36dp`** (matching `BackChevronView` / `ThreeDotMenuView`), drawing a centered `12dp × 7dp` chevron with `2.0dp` rounded stroke (`STROKE`, `Cap.ROUND`, `Join.ROUND`, matching `CheckmarkView`). Enabled color: `textSecondary`; circular `36dp` press feedback. At boundaries (`index == 0` for Up, `index == 2` for Down), keep the view in place to preserve column alignment, set `isEnabled = false`, `isClickable = false`, and dim alpha to `0.28f` (Dark) / `0.35f` (Light). Tapping swaps with the adjacent section and calls `onRefreshRequested()` without toggling the switch. Content descriptions: `"Move <Title> up"` / `"Move <Title> down"`. |
| Switch (far right) | Standard `OneUiViews.OneUiSwitch` (`46dp × 26dp`), flush to the `20dp` trailing padding — identical right-edge alignment to every other switch row in `SettingsActivity`. |

**Row titles and dynamic subtitles** (kept concise so they fit cleanly beside the `130dp` trailing control cluster on `360dp` screens):

| Section | Title | Subtitle (Enabled + Access Granted) | Subtitle (Enabled + Access Not Granted) | Subtitle (Off) |
|---|---|---|---|---|
| Recent | `"Recent apps"` | `"Horizontal row of recently used apps"` | `"Horizontal row • Needs Usage access (below)"` | `"Hidden from the app picker"` |
| Frequent | `"Frequently used"` | `"Horizontal row of most used apps"` | `"Horizontal row • Needs Usage access (below)"` | `"Hidden from the app picker"` |
| Favorites | `"Favorites"` | `"Starred apps pinned above All apps"` | *(N/A — no permission needed)* | `"Hidden from the app picker"` |

### `Usage access` Grant Row
Exact structural mirror of `Notification access` in the `Notification badges` card (`SettingsActivity.kt` lines 425–448), built via `OneUiViews.createSettingsRow`:

| Property | Spec |
|---|---|
| Title | `"Usage access"`, `titleAccent = true` (`16sp` Bold, `textAccent`) |
| Subtitle — **Granted** | `"Granted • DuoPanel can read recent and frequent app usage"` (`13sp` Regular, `textSecondary`) |
| Subtitle — **Not granted** | `"Not granted • Tap to open system settings and allow DuoPanel"` (`13sp` Regular, `textSecondary`) |
| Trailing view | `null` |
| On click | Opens `Settings.ACTION_USAGE_ACCESS_SETTINGS`; on failure shows a short Toast (`"Could not open usage access settings on this device."`). Returning to Settings refreshes both this row and the section subtitles via `SettingsActivity.onResume()` → `rebuildUI()`. |
