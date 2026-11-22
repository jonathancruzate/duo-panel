# DuoPanel Design Pass 0.2.0 — Light/Dark Theme & Accent Color Spec

> **Scope:** Complete visual and token specification for DuoPanel 0.2.0 (Kotlin, framework-only, no AndroidX). Covers **Feature 1 (Theme: System / Light / Dark)** and **Feature 2 (Color: One UI / System palette / Stephany pink / Custom)** across `OneUiViews`, `PickerActivity`, `StageActivity`, `CompanionPanelActivity`, `MainActivity`, `SettingsActivity`, `HideAppsActivity`, `LaunchLabActivity`, and `AboutActivity`.
>
> **Reference note:** `design-reference/oneui-home-settings.png` in the repository is a dark-mode One UI Home screen settings capture; the Light palette below preserves its exact One UI layout, 26dp borderless grouped-card hierarchy, inset hairlines, pill-switch proportions, and quiet bottom Finder pill (`design-reference/oneui-app-drawer.png`) while anchoring every Light surface on Samsung One UI's canonical Light settings palette (cool off-white canvas with crisp `#FFFFFF` cards).

---

## Feature 1 — Theme Option (`System` / `Light` / `Dark`)

### 1.1 Mode Resolution & System Chrome
- **Preference:** `ThemeMode` with values **System** (default), **Light**, and **Dark**.
- **System resolution:** Follows `resources.configuration.uiMode` night-mode bit (`UI_MODE_NIGHT_YES` -> Dark palette; otherwise -> Light palette). Activities re-resolve the active palette in `onCreate`, `onResume`, and configuration changes so toggling the device Dark Mode quick tile updates open panels cleanly.
- **System bar colors & icon contrast:**
  - **Solid activities** (`MainActivity`, `SettingsActivity`, `LaunchLabActivity`, `AboutActivity`, `HideAppsActivity`, `CompanionPanelActivity`): `statusBarColor` and `navigationBarColor` match `solidBackground` (`#0B0E1A` Dark / `#F2F2F6` Light).
  - **Translucent panel activities** (`PickerActivity`, `StageActivity`): `statusBarColor` and `navigationBarColor` remain `Color.TRANSPARENT` (edge-to-edge, preserving 0.1.8/0.1.9 insets).
  - **System bar icons (all activities):** Enable light system bar icon appearance (`APPEARANCE_LIGHT_STATUS_BARS` and `APPEARANCE_LIGHT_NAVIGATION_BARS` on API 30+; `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` / `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR` on API 29) whenever the Light palette is active so dark status/nav icons read cleanly over `#F2F2F6` and light scrims; clear both flags in Dark palette.

---

### 1.2 Full Surface, Text, Switch & Graphic Token Table

| Token (`OneUiViews` equivalent) | Dark (existing) | Light (0.2.0) | Short Reasoning |
|---|---|---|---|
| **Solid background** (`COLOR_BG_NAVY` / `COLOR_BG_BLACK`) | `#0B0E1A` | **`#F2F2F6`** | Canonical One UI Light settings canvas with a subtle cool-slate tint matching `#0B0E1A`'s cool DNA; cleanly separates borderless `#FFFFFF` grouped cards (luminance step 0.89 -> 1.00) without looking muddy. |
| **Panel scrim over blur** (`COLOR_BG_SCRIM`, blur active) | `#A60B0E1A` (65% alpha) | **`#CCF2F2F6`** (80% alpha) | 65% light scrim fails over dark blurred wallpapers (`#000000` -> `#9D9DA0`, dropping secondary text to 2.28:1). At **80% (`0xCC`)**, 20% of the 140px blurred backdrop still breathes through as frosted glass while keeping `#15171F` primary text >= 10.06:1 and `#5C6170` secondary text >= 3.48:1 even over pure black. |
| **No-blur fallback scrim** (`COLOR_BG_SCRIM_NO_BLUR`) | `#D90B0E1A` (85% alpha) | **`#F0F2F2F6`** (94% alpha) | **Hard constraint (DUO-DE ROM reports `mBlurEnabled=false`):** see Section 1.3 below. Compresses sharp underlying text contrast to **1.15:1** (imperceptible) while guaranteeing **4.83:1** secondary text even over solid `#000000`. |
| **Grouped card background** (`COLOR_CARD_BG` / `COLOR_FOCUS_BLOCK`) | `#1A1E2E` | **`#FFFFFF`** | Pure white 26dp One UI Light grouped cards floating cleanly on `#F2F2F6` with zero borders needed. |
| **Subtle surface** (`COLOR_SUBTLE_SURFACE` / `COLOR_NESTED_CARD`) | `#22273B` | **`#E8EBF3`** | Used for active segment pills (on `#FFFFFF` in `MainActivity` tab bar AND inside `#F2F2F6` segmented trough in `PickerActivity`/`StageActivity`), secondary buttons, nested cards, and drag drop-target highlights. Steps clearly darker than both `#FFFFFF` and `#F2F2F6`. |
| **Hairline divider** (`COLOR_HAIRLINE_DIVIDER` / `COLOR_NESTED_STROKE`) | `#282D42` | **`#E2E5EE`** | Quiet 1dp cool hairline inset 20dp inside `#FFFFFF` cards (1.26:1 vs `#FFFFFF`), separating rows without visual clutter. |
| **Floating menu background** (`COLOR_FLOATING_MENU`) | `#252A3E` | **`#FFFFFF`** | Crisp elevated One UI Light menu card (`22dp` radius, `12dp` elevation) matching One UI Light overflow menus. |
| **Floating menu stroke** (`COLOR_FLOATING_STROKE`) | `#313750` | **`#C2C7D6`** | 1dp border (1.69:1 on `#FFFFFF`) prevents floating menus from merging into `#FFFFFF` cards below, and keeps unchecked `HideAppsActivity` checkboxes (which reuse this token) clearly outlined on `#FFFFFF`. |
| **Search pill background** (`COLOR_SEARCH_PILL`) | `#151928` | **`#FFFFFF`** (+ 1dp `#DCE0EA` stroke) | Matches One UI Light's bright white bottom Finder pill floating above `#F2F2F6` scrim; the quiet 1dp `#DCE0EA` stroke preserves pill edges even when bright white content sits behind the 94% scrim. |
| **Text primary** (`COLOR_TEXT_PRIMARY`) | `#FAFAFA` | **`#15171F`** | Deep cool ink echoing `#0B0E1A` (17.88:1 on `#FFFFFF`, 16.02:1 on `#F2F2F6`) — crisp without pure-black halation. |
| **Text secondary** (`COLOR_TEXT_SECONDARY`) | `#9A9FAE` | **`#5C6170`** | Cool slate secondary copy passing WCAG AA everywhere (6.18:1 on `#FFFFFF`, 5.53:1 on `#F2F2F6`, 5.18:1 on `#E8EBF3`). |
| **Switch track ON** (`COLOR_SWITCH_TRACK_ON`, One UI default) | `#8FA8D8` | **`#0381FE`** | Canonical One UI Light active switch track (3.77:1 against both `#FFFFFF` card and `#FFFFFF` thumb). Dark's `#8FA8D8` would drop to 2.38:1 on `#FFFFFF`. Non-default colors derive per Section 2.2. |
| **Switch track OFF** (`COLOR_SWITCH_TRACK_OFF`) | `#525668` | **`#8D92A0`** | Medium cool-gray One UI Light off-track; hits **3.11:1** against both the `#FFFFFF` card and the `#FFFFFF` thumb (passes 3:1 UI component contrast on both boundaries). |
| **Switch thumb** (`COLOR_SWITCH_THUMB`) | `#FFFFFF` | **`#FFFFFF`** | Fixed pure white circular thumb in both palettes per One UI switch geometry. |
| **Panel graphic tile background** (`PanelGraphicView`) | `#121624` | **`#E6E9F2`** | Recessed cool-slate tile clearly bounded on both `#FFFFFF` cards (1.21:1) and `#F2F2F6` headers. |
| **Panel graphic inactive fill** (`PanelGraphicView`) | `#262B3E` | **`#D2D7E4`** | Steps darker toward mid-slate from `#E6E9F2` (mirroring Dark's step lighter from `#121624`), ensuring the active accent panel dominates immediately. |
| **Panel graphic inactive stroke** (`PanelGraphicView`) | `#565D78` | **`#8E96AC`** | Crisp 2.43:1 outline on `#E6E9F2`, preserving the recessed two-pane hardware silhouette. |

*Hardware-acceleration fallback (0.1.9 safety path):* When `isHardwareAccelerated == false` or window setup throws, `applyTranslucentBlurWindow` falls back to solid `solidBackground` (`#0B0E1A` Dark / `#F2F2F6` Light).

---

### 1.3 Light No-Blur Scrim Alpha Rationale (`94%` / `0xF0` -> `#F0F2F2F6`)
On the DUO-DE Android 16 test ROM (`mBlurEnabled=false`), `PickerActivity` and `StageActivity` render over the no-blur scrim alone — frequently directly on top of a dense, high-contrast Settings screen. Light scrims require **higher opacity than dark scrims** for two perceptual reasons: sRGB gamma makes dark letterforms on a bright field significantly more salient than light letterforms on a dark field, and an 85% light scrim allows dark text behind it to drop luminance steeply.

| Light Scrim Alpha (base `#F2F2F6`) | Ghost Contrast Over Light Settings (`#111111` on `#FFFFFF`) | Ghost Contrast Over Dark Settings (`#FFFFFF` on `#121212`) | Worst-Case Backdrop (over `#000000`) | Panel Primary `#15171F` / Secondary `#5C6170` Over Worst-Case | Verdict |
|---|---|---|---|---|---|
| 85% (`0xD9`, same as Dark) | **1.40:1** | 1.40:1 | `#CECED1` | 11.39:1 / **3.93:1** | **Reject** — sharp ghost text stays readable behind 12sp app labels; secondary text dips below 4.5:1 over dark apps. |
| 90% (`0xE6`) | 1.24:1 | 1.24:1 | `#DADADD` | 12.82:1 / 4.43:1 | Borderline — bold Settings headers still faintly visible; secondary text just misses 4.5:1. |
| **94% (`0xF0`) — Chosen** | **1.15:1** | **1.14:1** | `#E3E3E7` | **13.97:1 / 4.83:1** | **Optimal** — ghost text falls cleanly below the ~1.25:1 legibility floor for sharp body copy, secondary text passes WCAG AA (4.83:1) even over solid black, and 6% translucency keeps subtle wallpaper warmth + intact `PixelFormat.TRANSLUCENT` blur plumbing. |
| 96% (`0xF5`) | 1.09:1 | 1.09:1 | `#E8E8EC` | 14.64:1 / 5.06:1 | Visually indistinguishable from flat opaque paint. |

---

### 1.4 Colors That Must NOT Be Palette-Swapped

| Element / Token | Fixed Value | Why It Must Stay Fixed |
|---|---|---|
| **Semantic status colors** (`COLOR_STATUS_OK`, `COLOR_STATUS_WARN`, `COLOR_DANGER`) | `#2ECC71`, `#F39C12`, `#E74C3C` | Universal traffic-light hue semantics should not drift between themes. *(If ever rendered as body text on `#FFFFFF` in future screens, place them on a dark pill rather than altering the semantic constants.)* |
| **Switch thumb** (`COLOR_SWITCH_THUMB`) | `#FFFFFF` | One UI pill switches always pair a pure white thumb against the ON/OFF tracks in both modes. |
| **Stage over-app captured frame & caption** (`StageActivity.buildAppBackdropPane`) | Raw captured `Bitmap` (no tint/scrim); caption in fixed dark translucent pill **`#BF0B0E1A`** (~75% alpha) with fixed **`#FAFAFA`** text | The captured frame represents the live underlying app and must stay untouched. Its `"Tap to return to the app"` caption cannot depend on DuoPanel's palette because the underlying app content may be bright white or dark black regardless of DuoPanel's theme — wrapping it in a fixed `#BF0B0E1A` pill with `#FAFAFA` text guarantees high contrast (18.43:1 on pill base) over any captured frame AND prevents the current un-pilled `#9A9FAE` fallback caption from failing at 2.37:1 on the `#F0F2F2F6` light scrim when capture is unavailable. |
| **Pinned launcher shortcut icons & Solo badged bitmaps** (`ic_shortcut_*.xml`, `AppRepository.createBadgedSoloIconBitmap`) | `#121215` / `#EE121215` pill, `#0381FE` active half, `#3E3E46` / `#222226` inactive half | Rendered permanently on One UI Home over arbitrary wallpapers and baked at pin time; must remain palette-invariant so home-screen icons stay legible on any wallpaper and do not go stale when DuoPanel's in-app theme changes. |
| **Installed app icons** | System `Drawable` (untinted) | Third-party brand artwork must always render in natural full color (`DESIGN-REFERENCE.md`). |
| **Structural window/root clears & centered menu modal dim** | `Color.TRANSPARENT`; `dimAmount = 0.45f` (black) | Transparent root containers (`screenRoot`, `paneRoot`, `clearTranslucentBlurWindow`) are required for `DecorView`'s single-scrim blur composition (0.1.9); modal dialog dim stays neutral black in both One UI modes. |

---

## Feature 2 — Color Option (Meta Muse Widget Parity)

### 2.1 Accent Options & Scope
Replaces every `COLOR_ONEUI_BLUE` (`#0381FE`) usage across the app: `PanelGraphicView` active pane, segmented pick-mode active label (`PickerActivity`, `StageActivity`), `MainActivity` active tab label, `Clock` active state (`PickerActivity`, `StageActivity`, `CompanionPanelActivity`), accented link/action rows (`titleAccent`, `subtitleAccent`, `activeOrLink`, `HideAppsActivity` `"Done"`, `SettingsActivity` active `App size` step label, `LaunchLabActivity` log header), selected settings titles and `CheckmarkView` checkmarks, `HideAppsActivity` checked checkbox fills, primary buttons (`createButton(primary = true)`), notification badge dot and number pill fills, `SettingsActivity` `App size` `SeekBar` progress/thumb tint, switch ON track (via 2.2), and soft accent tint fills (via 2.3).

| Option | Light Palette Accent | Dark Palette Accent | Resolution Rule |
|---|---|---|---|
| **One UI** *(default)* | `#0381FE` | `#0381FE` | Fixed Samsung One UI blue; unchanged default look. |
| **System palette** | `android.R.color.system_accent1_600` (API 31+); fallback `#0381FE` (< 31) | **`android.R.color.system_accent1_300`** (API 31+); fallback `#0381FE` (< 31) *(see 2.4 for why raw `accent1_600` fails Dark contrast)* | Material You Monet dynamic accent on API 31+; clean `#0381FE` fallback below API 31. |
| **Stephany pink** | **`#B46C5D`** | **`#C98374`** | Exact Meta Muse Widget terracotta-rose pair. |
| **Custom** *(default: `H=205`, `S=70`)* | `HSV(H, S_eff, 0.40)` -> default **`#144466`** | `HSV(H, S_eff, 0.92)` -> default **`#2E9CEB`** | `H` in `0..359` (default `205`); `S_in` in `0..100` (default `70`); `S_eff = min(1.0, (S_in / 100.0) * 1.15)` (default `0.805`); `V = 0.40` Light, `0.92` Dark. *(Dark indigo contrast guard in 2.4.)* |

---

### 2.2 (a) Switch ON Track Derivation Formula

- **One UI default** keeps One UI's native tracks untouched: Dark **`#8FA8D8`**, Light **`#0381FE`**.
- **Non-default colors (`System palette`, `Stephany pink`, `Custom`)** derive the switch ON track from the resolved active palette accent's `HSV(H, S, V)` (post-contrast-guard on Dark):
  - **Dark palette formula:** `HSV(H, min(S * 0.55, 0.42), 0.78)`
    - *Why:* Replicates One UI Dark's calm desaturated pastel pill (`#8FA8D8` has `S=0.34, V=0.85`) without glaring neon tracks, while keeping `V = 0.78` so the `#FFFFFF` thumb maintains >= 2.3:1 contrast against the track and the track maintains >= 6.2:1 against `#1A1E2E` (even worst-case `H=240°` hits 3.94:1).
  - **Light palette formula:** `HSV(H, S, clamp(V, 0.55, 0.75))`
    - *Why:* Mirrors One UI Light's confident saturated track while lifting very dark `V = 0.40` Custom accents up to `V = 0.55` (so the switch reads clearly as colored-ON rather than near-black) and capping `V <= 0.75` so bright System hues never wash out the `#FFFFFF` thumb (>= 3.5:1 against both `#FFFFFF` card and `#FFFFFF` thumb).
  - *Achromatic edge case (`S = 0` in Custom):* Valid with zero NaN/division — yields `#C7C7C7` on Dark (9.79:1 vs card) and `#8C8C8C` on Light (3.36:1 vs card/thumb).

| Accent Option | Palette | Resolved Accent | Derived Switch ON Track | Track vs Card | White Thumb (`#FFFFFF`) vs Track |
|---|---|---|---|---|---|
| **One UI** *(native default)* | Dark | `#0381FE` | **`#8FA8D8`** | 6.90:1 | 2.40:1 |
| **One UI** *(native default)* | Light | `#0381FE` | **`#0381FE`** | 3.77:1 | 3.77:1 |
| **Stephany pink** | Dark | `#C98374` (`H=10.6°, S=0.423, V=0.788`) | **`#C7A199`** (`H=10.6°, S=0.233, V=0.78`) | 7.08:1 | 2.34:1 |
| **Stephany pink** | Light | `#B46C5D` (`H=10.3°, S=0.483, V=0.706`) | **`#B46C5D`** (`V` already in `[0.55, 0.75]`) | 4.00:1 | 4.00:1 |
| **Custom default** (`H=205, S=70`) | Dark | `#2E9CEB` (`H=205°, S=0.805, V=0.92`) | **`#73A4C7`** (`H=205°, S=0.420, V=0.78`) | 6.20:1 | 2.67:1 |
| **Custom default** (`H=205, S=70`) | Light | `#144466` (`H=205°, S=0.805, V=0.40`) | **`#1B5D8C`** (`H=205°, S=0.805, V=0.55`) | 7.02:1 | 7.02:1 |

---

### 2.3 (b) Soft Accent Tint Variant (`accentTint`)
Today `COLOR_ONEUI_BLUE_TINT` is `0x240381FE` (**14% alpha**, `0x24`).
- **Dark palette — Confirm `14%` (`0x24` alpha):** Composited over `#1A1E2E`, 14% gives a crisp, subtle 1.18–1.24:1 surface lift with `#FAFAFA` primary text at 12.8–13.4:1 and accent at 3.7–4.5:1. Leaves default `#240381FE` unchanged.
- **Light palette — Refine to `12%` (`0x1F` alpha):** Because Light Custom accents use `V = 0.40` (`#144466`) and Monet Tone-40 System accents are dark, 14% on `#FFFFFF` turns slightly heavy/grayish (`#DEE5E9`, 1.27:1). Lowering Light tint alpha to **12% (`0x1F`)** keeps every accent an airy One UI Light wash (1.15–1.24:1 off `#FFFFFF`) while keeping `#15171F` primary text >= 14.47:1.

| Accent Option | Dark Tint (`0x24`, 14%) -> Composited on `#1A1E2E` | Light Tint (`0x1F`, 12%) -> Composited on `#FFFFFF` |
|---|---|---|
| **One UI** | `#240381FE` -> `#172C4B` (1.18:1 vs card) | `#1F0381FE` -> `#E0F0FF` (1.16:1 vs card) |
| **Stephany pink** | `#24C98374` -> `#332C38` (1.23:1 vs card) | `#1FB46C5D` -> `#F6EDEB` (1.15:1 vs card) |
| **Custom default** | `#242E9CEB` -> `#1D3049` (1.24:1 vs card) | `#1F144466` -> `#E2E8EC` (1.24:1 vs card) |

---

### 2.4 (c) WCAG Contrast Table, Flags (`< 3:1`) & Remedies

**Text on Card Background (Baseline)**
- **Dark (`#1A1E2E`):** Text Primary `#FAFAFA` = **15.85:1** (PASS AAA); Text Secondary `#9A9FAE` = **6.26:1** (PASS AA).
- **Light (`#FFFFFF`):** Text Primary `#15171F` = **17.88:1** (PASS AAA); Text Secondary `#5C6170` = **6.18:1** (PASS AA).

**Accent on Card Background (and Subtle Surface for Segmented Controls)**

| Accent Option | Palette | Raw Hex | Accent on Card (`#1A1E2E` / `#FFFFFF`) | Accent on Subtle (`#22273B` / `#E8EBF3`) | Status (`>= 3:1` UI) | Remedy & Remediated Contrast |
|---|---|---|---|---|---|---|
| **One UI** | Dark | `#0381FE` | **4.39:1** | 3.92:1 | PASS | None required. |
| **One UI** | Light | `#0381FE` | **3.77:1** | 3.16:1 | PASS (`< 4.5:1` small text) | Keep `#0381FE` for all fills/icons/switches/badges; optional Light text-only token `accentText` = **`#006CE0`** (**4.97:1** on `#FFFFFF`, **4.17:1** on `#E8EBF3`) if strict AA normal-text compliance is desired for 12.5–14sp links. |
| **System palette** *(raw `accent1_600`)* | Dark | Monet T40 (e.g. `#3B5BA9`, `#006A60`, `#984061`) | **2.55–2.57:1** | 2.27–2.29:1 | **FAIL (`< 3:1`)** | **Required remedy:** Material You `system_accent1_600` is calibrated to Monet Tone 40 (for Light backgrounds). On Dark palette (API 31+), read **`android.R.color.system_accent1_300`** (Monet Tone 70, e.g. `#7CA8F8` / `#4DB6AC` / `#EC8DAB` -> **6.74–7.54:1** on `#1A1E2E`, **6.02–6.74:1** on `#22273B`). If an implementation must seed from `system_accent1_600` alone, apply the Dark contrast-floor guard below. |
| **System palette** *(`accent1_600`)* | Light | Monet T40 (e.g. `#1B6EF3`, `#3B5BA9`, `#006A60`) | **4.59–6.50:1** | 3.85–5.45:1 | PASS | None required; `system_accent1_600` is native to Light surfaces. |
| **Stephany pink** | Dark | `#C98374` | **5.50:1** | 4.91:1 | PASS | None required (passes both 3:1 UI and 4.5:1 text). |
| **Stephany pink** | Light | `#B46C5D` | **4.00:1** | 3.36:1 | PASS (`< 4.5:1` small text) | Keep `#B46C5D` for fills/icons/switches/badges; optional Light text-only token `accentText` = **`#9D584A`** (**5.35:1** on `#FFFFFF`, **4.48:1** on `#E8EBF3`). |
| **Custom default** (`H=205, S=70`) | Dark | `#2E9CEB` | **5.57:1** | 4.97:1 | PASS | None required for default hue. |
| **Custom default** (`H=205, S=70`) | Light | `#144466` | **10.25:1** | 8.60:1 | PASS | None required; all Light `V=0.40` hues (`0°–359°`) stay **>= 6.04:1** on `#FFFFFF` and **>= 5.07:1** on `#E8EBF3`. |
| **Custom extreme** (`H=225°–260°, S>=70`, e.g. `H=240°`) | Dark | `#2E2EEB` | **2.13:1** | 1.90:1 | **FAIL (`< 3:1`)** | **Required remedy (Dark contrast-floor guard):** Whenever a Dark accent measures `< 3.5:1` on `#1A1E2E`, iteratively reduce HSV `S` in `0.05` steps (down to `0.35`) and raise `V` to `0.95` until contrast >= `3.5:1`. Example: `H=240°, S=70` lifts `#2E2EEB` -> **`#7A7AF2`** (**4.64:1** on `#1A1E2E`, **4.15:1** on `#22273B`). |

**Critical Companion Rule — On-Accent Foreground (`onAccent`)**
Primary buttons (`createButton(primary = true)`), notification number badge pills (`PickerActivity`/`StageActivity`), and checked checkbox checkmarks (`HideAppsActivity`) currently hardcode white foregrounds (`#FAFAFA` / `#FFFFFF`). On Dark palette, Custom default `#2E9CEB` drops white text to **2.97:1**, lifted System `accent1_300` drops white text to **~2.38:1**, and bright Custom yellow/green hues drop white text to **1.28–1.61:1**.
- **Rule:** Compute `onAccent` dynamically from the resolved accent: use **`#FFFFFF`** whenever `contrast(#FFFFFF, accent) >= 3.7` (preserves white on `#0381FE` at 3.77:1, `#B46C5D` at 4.00:1, `#144466` at 10.25:1, and Light `system_accent1_600` at 4.59–6.50:1); otherwise use **`#15171F`** (gives **6.02:1** on `#2E9CEB`, **5.95:1** on `#C98374`, **7.51:1** on `#7CA8F8`, and **14.01:1** on `#EBEB2E`).

---

### 2.5 (d) Search Pill Accent Treatment (Preserving the Quiet Finder Look)
Samsung's One UI bottom Search pill (`oneui-app-drawer.png`) is deliberately achromatic at rest so the full-color app icon grid stays the visual focal point.
- **At rest (unfocused, empty query):** **Zero accent fill, zero accent border, zero accent placeholder.** Keep the pill completely neutral (`#151928` Dark; `#FFFFFF` with a 1dp `#DCE0EA` hairline stroke Light), with `textSecondary` for both the magnifier icon and `"Search"` hint.
- **Active / focused or typing state (subtle accent cue only):**
  1. **Magnifier icon (`MagnifierIconView`):** Tints from `textSecondary` to the resolved `accent` while the search `EditText` has focus or `searchQuery` is non-empty; reverts to `textSecondary` when unfocused and empty.
  2. **Cursor & text selection:** Tint the `EditText` cursor (`textCursorDrawable`) to `accent` and selection highlight (`highlightColor`) to `accentTint`.
- **Do not:** Tint the search pill fill with `accent` or `accentTint`, or add a full-perimeter colored outline ring around the pill (matching `DESIGN-REFERENCE.md`'s no-outlined-pills rule).

---

## Implementation Placement (Settings UI)
Add an **Appearance** section header + single grouped card in `SettingsActivity` (above **Picker display**, following the existing grouped-card + hairline-divider + accented-selected-title + `CheckmarkView` pattern):
1. **Theme** summary row + 3 selectable rows: `System` *(default)*, `Light`, `Dark`.
2. **Accent color** summary row + 4 selectable rows: `One UI` *(default)*, `System palette` *(note below API 31: "Android 12+ dynamic color • Uses One UI blue on this version")*, `Stephany pink`, `Custom` — each with an `18dp` circular leading swatch showing its resolved color for the active theme (outlined by a 1dp `hairlineDivider` stroke so swatches stay crisp on both `#1A1E2E` and `#FFFFFF`).
3. **Custom sliders** (visible inside the same card only when `Custom` is selected, matching the existing `App size` `SeekBar` block): **Hue** (`0–359`, default `205`) and **Saturation** (`0–100`, default `70`), updating the swatch and live preview immediately on release or drag.
