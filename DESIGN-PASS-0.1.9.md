# DuoPanel Design Pass 0.1.9 — Frosted Blur Fix & Top Status-Bar Inset

## 1. Diagnosis

### Defect 1: Cross-window blur not rendering (`failing.png`)

Inspecting `OneUiViews.kt`, `PickerActivity.kt`, `StageActivity.kt`, `styles.xml`, and the Surface Duo device window dump (`win.txt`, captured from the DUO-DE Android 16 GSI) reveals a compound code-level and platform-interaction failure across all five layers of Android's cross-window blur model:

1. **Primary code-level cause — `isCrossWindowBlurEnabled` gating & listener zeroing out the blur request while keeping a 55%-alpha scrim:**
   - In the Surface Duo DUO-DE Android 16 GSI window dump (`win.txt:1086`), `WindowManagerService`'s `BlurController` reports `mBlurEnabled=false` (as occurs on AOSP/GSI builds where `ro.surface_flinger.supports_background_blur` is disabled/unset, or when system blurs are off / Battery Saver is active). `win.txt:374–378` confirms `PickerActivity` had `fmt=TRANSPARENT` with **neither `FLAG_BLUR_BEHIND` nor `blurBehindRadius` present in `mAttrs`**.
   - In `OneUiViews.applyTranslucentBlurWindow` (previously lines 150–174 & 218–230) and `StageActivity.applyStageWindow` (previously lines 222–238), the app gated `Window.setBackgroundBlurRadius`, `FLAG_BLUR_BEHIND`, and `LayoutParams.blurBehindRadius` on `windowManager.isCrossWindowBlurEnabled == true`. When `false`, `applyApi31WindowBlur(win, false)` explicitly set `setBackgroundBlurRadius(0)`, cleared `FLAG_BLUR_BEHIND`, and set `lp.blurBehindRadius = 0`.
   - Worse, `OneUiViews.kt`'s `addCrossWindowBlurEnabledListener(blurListener)` fired immediately upon `onViewAttachedToWindow` with `enabled = false`, overwriting both blur radii back to `0` and clearing `FLAG_BLUR_BEHIND` even if set earlier.
   - Crucially, when `enabled == false`, **neither `OneUiViews.kt` nor `StageActivity.kt` compensated the scrim alpha**: both painted the same light `COLOR_BG_SCRIM = 0x8C0B0E1A` (~55% opacity, lowered in 0.1.7 Part C assuming a strong blur behind it) directly over an unblurred screen. That is why `failing.png` shows crisp Play Protect settings text colliding directly with `Favorites`, `All apps`, and app labels.
   - In Android's cross-window blur architecture, `WindowState.shouldDrawBlurBehind()` and `DecorView.updateBackgroundBlurRadius()` already check the OS blur controller internally; the app should keep the blur request installed on API 31+ (never letting the listener zero it out) and use `isCrossWindowBlurEnabled` / `addCrossWindowBlurEnabledListener` to switch between the translucent blurred-glass scrim and a deeper non-blur fallback scrim.

2. **Window background drawable vs. root view scrim & repeated `DecorView` background churn:**
   - `Theme.DuoPanel.TranslucentPanel` sets `windowIsTranslucent=true` and `windowBackground=@android:color/transparent`, so the window itself was transparent (`fmt=TRANSPARENT`). The root view (`screenRoot` / `paneRoot` / companion `scroll`) painted a translucent (`0x8C`, not opaque) scrim, so it did not opaquely block blur-behind — however, `Window.setBackgroundBlurRadius(int)` works in `DecorView` by wrapping `mOriginalBackgroundDrawable` into `LayerDrawable([mBackgroundBlurDrawable, mOriginalBackgroundDrawable])`.
   - Previously, `OneUiViews.applyTranslucentBlurWindow` called `win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))` (zero-alpha drawable) on every `onCreate`, `onResume`, `onMultiWindowModeChanged`, and `blurListener` callback, creating a fresh `ColorDrawable` object each time and forcing `DecorView.setWindowBackground()` to repeatedly tear down and rebuild its `LayerDrawable`, while `rootView` added a second full-screen overdraw pass for the scrim.
   - Carrying the translucent scrim directly on the window background (`ColorDrawable(scrimColor)`, updated only when the color changes) while setting the root view background to `Color.TRANSPARENT` gives `DecorView` a single composited `LayerDrawable([mBackgroundBlurDrawable, ColorDrawable(scrimColor)])` with non-zero alpha and zero overdraw, while keeping the surface `PixelFormat.TRANSLUCENT` so `FLAG_BLUR_BEHIND` + `blurBehindRadius` also shows cleanly through.

3. **Non-atomic `FLAG_BLUR_BEHIND` / `blurBehindRadius` attribute dispatch:**
   - Both `OneUiViews.applyApi31WindowBlur` and `StageActivity.applyStageWindow` fetched `val lp = win.attributes`, then called `win.addFlags(FLAG_BLUR_BEHIND)` (which dispatches `onWindowAttributesChanged` while `lp.blurBehindRadius` is still `0`), and only afterward mutated `lp.blurBehindRadius = radius` and called `win.attributes = lp`. Setting `lp.format = PixelFormat.TRANSLUCENT`, `lp.flags` (adding `FLAG_BLUR_BEHIND` and clearing `FLAG_DIM_BEHIND`), and `lp.blurBehindRadius = radius` together before a single `win.attributes = lp` dispatches all blur parameters atomically.

4. **Timing relative to `setContentView` and window attach (`ViewRootImpl`):**
   - `DecorView.updateBackgroundBlurRadius()` returns early when `getViewRootImpl() == null` (before window attach).
   - `StageActivity.kt` called its duplicated `applyStageWindow(blur = true)` **before** `buildPanes()` (`setContentView(root)`), never re-applied blur after `setContentView`, on window attach, or in `onResume()`, and never registered `addCrossWindowBlurEnabledListener`.
   - `PickerActivity.kt` called `applyTranslucentBlurWindow` right after `setContentView` (before attach); its `onViewAttachedToWindow` only registered `blurListener` (which zeroed the radius when `isCrossWindowBlurEnabled == false`). Re-applying `applyApi31WindowBlur` inside `onViewAttachedToWindow` ensures `DecorView` binds `BackgroundBlurDrawable` once `ViewRootImpl` exists.

5. **Radius semantics and dual blur paths:**
   - Both `Window.setBackgroundBlurRadius(int)` and `WindowManager.LayoutParams.blurBehindRadius` take physical pixels (`px`), with AOSP advising `<= 150px` for GPU performance. Because `PickerActivity` and `StageActivity` are non-floating panel activities (`windowIsFloating` is intentionally `false`), applying the radius on both `setBackgroundBlurRadius` and `FLAG_BLUR_BEHIND` + `blurBehindRadius` guarantees full frosted dissolution regardless of which path a given Android 12–16 compositor/OEM honors.

---

### Defect 2: Top overlap under the status bar (`failing.png`)

- `app/build.gradle.kts` targets SDK 36 (`targetSdk = 36`), so Android 15/16 enforces edge-to-edge layout (`pfl=... EDGE_TO_EDGE_ENFORCED ...` in `win.txt:376`, with `mContentInsets=[0,60][0,140]` in `win.txt:1093`).
- Patch 0.1.8 added a `WindowInsets.Type.navigationBars().bottom` inset listener to the bottom search bar wrappers to clear the `140px` taskbar strip, but no matching top `statusBars` inset listener existed on the top header containers. As a result, `topBar` started at `12dp` (`30px`), colliding directly with the `60px` status bar icons in `failing.png`.

---

## 2. File-by-File Changes

### `app/src/main/java/com/jonathancruzate/duopanel/OneUiViews.kt`
- Updated frosted blur constants (preserving hue `#0B0E1A` and accent `#0381FE`):
  - `COLOR_BG_SCRIM = 0xA60B0E1A.toInt()` (~65% opacity when cross-window blur is active)
  - Added `COLOR_BG_SCRIM_NO_BLUR = 0xD90B0E1A.toInt()` (~85% opacity fallback when the OS reports `isCrossWindowBlurEnabled == false` or API < 31)
  - `PANEL_BLUR_RADIUS_PX = 140` (up from `120`)
- Reworked `applyTranslucentBlurWindow(activity, rootView, attachListener)`:
  - Sets `rootView` background to `Color.TRANSPARENT` and applies the scrim to the window background drawable via `setWindowScrimColor(win, color)` (which inspects `win.peekDecorView()?.background` — including inside a `LayerDrawable` — and skips redundant `setBackgroundDrawable` calls when the color is unchanged).
  - Sets `win.setFormat(PixelFormat.TRANSLUCENT)` and transparent status/navigation bars.
  - On API 31+, unconditionally applies `applyApi31WindowBlur(win, PANEL_BLUR_RADIUS_PX)` so the blur request is always installed and never zeroed by `blurListener`.
  - Uses `isCrossWindowBlurEnabled` and `addCrossWindowBlurEnabledListener` strictly to switch the window scrim between `COLOR_BG_SCRIM` and `COLOR_BG_SCRIM_NO_BLUR`, re-applying `applyApi31WindowBlur(win, PANEL_BLUR_RADIUS_PX)` on `onViewAttachedToWindow` (when `ViewRootImpl` is attached) and immediately if `rootView.isAttachedToWindow` is already true.
  - Preserves the hardware-acceleration-unavailable / exception fallback to solid `COLOR_BG_NAVY` (`#0B0E1A`).
- Updated `applyApi31WindowBlur(win, radiusPx)` to set `lp.format = PixelFormat.TRANSLUCENT`, `lp.flags` (`FLAG_BLUR_BEHIND` on, `FLAG_DIM_BEHIND` off), and `lp.blurBehindRadius = radius` atomically in a single `win.attributes = lp` dispatch alongside `win.setBackgroundBlurRadius(radius)`.
- Added `clearTranslucentBlurWindow(activity)` for `StageActivity`'s pre-capture transparent state in `overApp` mode.
- Added `applyTopStatusBarInsetPadding(view)` mirroring Patch 0.1.8's bottom inset listener: captures `baseTopPad = view.paddingTop`, installs `setOnApplyWindowInsetsListener` using `insets.getInsets(WindowInsets.Type.statusBars()).top` on API 30+ (`Build.VERSION_CODES.R`) and `insets.systemWindowInsetTop` on API 29, sets `paddingTop = baseTopPad + topInset` while preserving left/right/bottom padding, and returns `insets` unconsumed.

### `app/src/main/java/com/jonathancruzate/duopanel/PickerActivity.kt`
- Set `screenRoot` background to `Color.TRANSPARENT` so `OneUiViews.applyTranslucentBlurWindow` carries the single scrim on the window background without double-layering.
- Applied `OneUiViews.applyTopStatusBarInsetPadding(drawerContent)` on `drawerContent` (preserving its `12dp` base top padding above `topBar`). Existing `screenRoot.requestApplyInsets()` right after `setContentView(screenRoot)` dispatches insets to both `drawerContent` (top) and `bottomSearchBarWrapper` (bottom).

### `app/src/main/java/com/jonathancruzate/duopanel/StageActivity.kt`
- Removed the duplicated `applyStageWindow(blur: Boolean)` implementation (and unused `ColorDrawable` / `WindowManager` imports).
- Used `OneUiViews.clearTranslucentBlurWindow(this)` during the `overApp` pre-capture transparent frame (`onCreate` and `onNewIntent`).
- Moved blur window setup into `buildPanes()` **after** `setContentView(root)` via `OneUiViews.applyTranslucentBlurWindow(this, root, attachListener = true)`, plus `OneUiViews.applyTranslucentBlurWindow(this, rootLayout, attachListener = false)` in `onResume()` when `panesBuilt` is true.
- Set `paneRoot` (picker pane), companion `scroll`, and the no-bitmap `appPane` fallback container to `Color.TRANSPARENT` so the window background carries the single scrim cleanly without double-scrimming (while captured bitmaps in `overApp` mode still draw opaquely over their half).
- Applied `OneUiViews.applyTopStatusBarInsetPadding(drawerContent)` in `buildPickerPane()` (base `12dp` top padding) and `OneUiViews.applyTopStatusBarInsetPadding(rootCol)` in `buildCompanionPane()` (base `18dp` top padding), and called `root.requestApplyInsets()` right after `setContentView(root)` in `buildPanes()`.

### `app/src/main/java/com/jonathancruzate/duopanel/CompanionPanelActivity.kt`
- Applied `OneUiViews.applyTopStatusBarInsetPadding(root)` on `root` (preserving its `18dp` base top padding above the companion card) and called `scroll.requestApplyInsets()` right after `setContentView(scroll)`.

---

## 3. Final Parameter Values & Rationale

| Parameter | Old Value | New Value | Rationale |
|---|---|---|---|
| `PANEL_BLUR_RADIUS_PX` | `120` px | `140` px | Stronger Gaussian dissolution (~56dp at 400dpi) matching the complete text/detail dissolution in `reference.png`, while staying safely under Android's `150px` GPU performance guideline. Applied to both `setBackgroundBlurRadius` and `lp.blurBehindRadius`. |
| `COLOR_BG_SCRIM` (blur active) | `0x8C0B0E1A` (~55% alpha) | `0xA60B0E1A` (~65% alpha) | Keeps hue `#0B0E1A` unchanged; modestly deeper than 55% so white/gray Picker labels stay legible over bright blurred screens while preserving the luminous frosted-glass feel of `reference.png` (lighter than 0.1.2B's 72% `0xB8`). |
| `COLOR_BG_SCRIM_NO_BLUR` (OS blur disabled) | *(none; reused 55% `0x8C`)* | `0xD90B0E1A` (~85% alpha) | Keeps hue `#0B0E1A` unchanged; prevents unblurred underlying settings text from colliding with Picker labels on builds/states where `mBlurEnabled=false` (e.g., DUO-DE GSI, Battery Saver, API < 31) while still reading as a dark translucent overlay rather than a flat solid panel. |
| `COLOR_ONEUI_BLUE` | `0xFF0381FE` | `0xFF0381FE` | Unchanged per requirement. |
| Top inset padding | None (`12dp` / `18dp` fixed) | Base + `statusBars().top` (API 30+) / `systemWindowInsetTop` (API 29) | Clears the `60px` status bar on Duo (and any taller cutout/status bar on other devices) while preserving the designed `12dp` (Picker/Stage picker) and `18dp` (Companion/Stage companion) breathing room below it. |

---

## 4. Deliberately Not Changed

- **`styles.xml` and `AndroidManifest.xml`**: Not modified. `Theme.DuoPanel.TranslucentPanel` already sets `android:windowIsTranslucent="true"`, `android:windowBackground="@android:color/transparent"`, `android:backgroundDimEnabled="false"`, and transparent system bar colors. Adding `android:windowIsFloating="true"` would shrink full/split-screen panel activities into dialog-sized floating windows, and hardcoding `android:windowBlurBehindEnabled="true"` on the shared theme would taint `StageActivity`'s `overApp` pre-capture transparent frame. `CompanionPanelActivity` intentionally retains solid `@style/Theme.DuoPanel` per Patch 0.1.5.
- **`app/build.gradle.kts`, `colors.xml`, dependencies, permissions**: Untouched (framework-only, no new dependencies or permissions).
- **Patch 0.1.8 search focus fix & bottom inset listeners**: Completely preserved (`isFocusableInTouchMode`, `inputType = InputType.TYPE_CLASS_TEXT`, `imeOptions = EditorInfo.IME_ACTION_SEARCH`, `searchPill` click listener with `requestFocus()` + `showSoftInput(...)`, and `navigationBars().bottom` inset listeners in both `PickerActivity` and `StageActivity`).
- **Launch flags, multi-window/split flows, and all Patch 0.1.7 customization options**: Completely preserved (app size, show labels, picker grid, sort order / custom drag reorder, show search bar, hide apps, and notification badges).
