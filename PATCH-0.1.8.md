# PATCH 0.1.8: Search fields take input focus explicitly (focus fix, 2026-11-21)

Source of truth: SEARCH-FOCUS-DIAGNOSIS.md (code diagnosis, 2026-11-21) and the
device evidence in ~/workspace/surface-duo-duo-de/input-focus-github-check-2026-11-21.md.
Change 1 (below) applies the diagnosis's proposed minimal fix exactly. The
diagnosis's optional manifest change (dropping `stateHidden` from
windowSoftInputMode) is NOT in this patch. Change 2 (bottom of this doc) is a
second fix added the same day from separate device evidence: the spanned
Picker's search pill was laid out but covered by the ROM's bottom system
strip. 0.1.8 was never installed between the two changes, so both ship in the
one staged build.

## Problem

On the Surface Duo (DUO-DE v2026.10.28), synthetic taps never give DuoPanel's
Picker or Stage search EditText input focus: the field reports `focused=false`,
and `dumpsys input_method` shows the IME session live but
`mStartedInputConnection=null` with a dummy EditorInfo (inputType 0x0). The
identical tap + ADBKeyBoard broadcast in Chrome, minutes later on the same
device and IME, produced `focused=true` and committed text. The refusal is
specific to DuoPanel's two translucent, blur-behind windows.

Diagnosis findings behind the fix (SEARCH-FOCUS-DIAGNOSIS.md):

- Neither activity ever establishes input focus programmatically: no
  `requestFocus`, no `showSoftInput`, no explicit `inputType` / `imeOptions` /
  `focusableInTouchMode` on either search field. Focus is left entirely to the
  framework's touch-focus path, and when that path does not complete on this
  ROM, nothing in the app recovers it.
- Ruled out by code and dumps: the field is focusable (`focusable="true"` on
  device); no `descendantFocusability` anywhere; no `FLAG_NOT_FOCUSABLE` /
  `FLAG_NOT_TOUCHABLE` / `FLAG_ALT_FOCUSABLE_IM` anywhere; no overlay view
  sits over the search field; no touch-driven `finish()` path exists (the
  intermittent tap-closes-Picker events are the same GSI window/input anomaly,
  not an app handler).
- The search pill itself has no click listener today, so taps on its padding
  or magnifier area do nothing at all.

## The change

In BOTH search field constructions, PickerActivity.kt (`searchBox`, was
line 840) and StageActivity.kt (`searchBox`, was line 727):

1. In the `EditText.apply { }` block, add:
   - `isFocusableInTouchMode = true`
   - `inputType = InputType.TYPE_CLASS_TEXT`
   - `imeOptions = EditorInfo.IME_ACTION_SEARCH`
2. After `searchPill.addView(searchBox)`, give the pill a click listener that
   forces the input connection:
   `searchPill.setOnClickListener { searchBox.requestFocus(); getSystemService(InputMethodManager::class.java)?.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT) }`

Imports added in both activities: `android.text.InputType`,
`android.view.inputmethod.EditorInfo`,
`android.view.inputmethod.InputMethodManager`.

Nothing else changes: no visual change, no behavior change beyond the search
field taking focus explicitly, no manifest change, no new permissions.

## Build state

- versionName 0.1.8, versionCode 9 (app/build.gradle.kts). Unchanged by
  change 2: the build was amended and restaged before ever being installed.
- Built on the PC lane; APK staged at dist/DuoPanel-0.1.8-debug.apk
  (restaged after change 2).
- INSTALLED and VERIFIED on the Duo 2026-11-21 (pm install -r; read-back
  versionName 0.1.8 / versionCode 9, lastUpdateTime 2026-11-21 16:23:13 EST).
  Every item in the verification plan below passed, including the
  typed-search hidden-app test that had been open since 0.1.7; the decisive
  fallback (item 5) was not triggered, because mStartedInputConnection is
  live after the fix. Full record: BUILD_NOTES.md section 15.

## Verification plan (after install, on the Duo)

1. Open the Picker; synthetic tap on the search pill, then `input text`:
   the text commits into the search box and the grid filters (the exact
   sequence that failed on 0.1.1 through 0.1.7).
2. While the field is focused, `dumpsys input_method` shows a live
   `mStartedInputConnection` (non-null) with a real EditorInfo
   (inputType TYPE_CLASS_TEXT), replacing the dummy EditorInfo.
3. The typed-search hidden-app test runs end to end: hide Calculator,
   search "Calculator" by synthetic input, Calculator absent from results;
   search "Calendar" as the positive control; unhide and verify the hidden
   set empty.
4. Same focus check in the StageActivity picker pane.
5. Decisive fallback: if `mStartedInputConnection` is STILL null after this
   fix, the translucent/blur window configuration itself is proven to be the
   blocker, and the follow-up is an opaque-window variant of the Picker
   (no further focus-path permutations).
6. Change 2: in the spanned fullscreen Picker, a screenshot shows the search
   pill visibly rendered ABOVE the ROM's bottom system strip (compare the
   pre-fix state: pill bounds inside the strip, pixels uniform near-black),
   and a synthetic tap on the now-visible pill reaches the field (feeds
   item 1). Same visual check for the StageActivity picker pane's search
   bar.

## Change 2: bottom search bar respects the bottom system window inset

### Evidence (device-verified 2026-11-21, Duo, DUO-DE v2026.10.28)

- In the spanned fullscreen Picker (window 2700x1800), the search pill IS
  laid out: the EditText's UI-dump bounds are [177,1688][2600,1740], and the
  pref `picker_show_search` reads true. The "Show search bar" setting was
  never off.
- A full-width strip at [0,1660][2700,1800], the ROM's taskbar strip, which
  no DuoPanel code adds, renders over the window's bottom ~140px. The pill's
  bounds sit entirely inside that strip.
- Pixel samples across the pill's rect are uniform near-black: the pill is
  not merely hard to see, it is covered. It is likewise untappable in that
  view, which also explains part of the day's synthetic-tap weirdness at
  those coordinates (taps landed on the system strip, not the app).
- The StageActivity picker pane's search bar sits at the same bottom
  position in its pane and is presumed covered the same way in the spanned
  Stage window.

### The fix

Both search-bar containers now lift themselves by the bottom system window
inset, added to (not replacing) their designed bottom padding (14dp):

- PickerActivity.kt: `bottomSearchBarWrapper` (the container holding
  `searchPill`, bottom child of `screenRoot`) gets a
  `View.setOnApplyWindowInsetsListener` that sets its bottom padding to the
  captured base padding plus the bottom inset, and returns the insets
  unconsumed; `screenRoot.requestApplyInsets()` is called after
  `setContentView`.
- StageActivity.kt: `searchWrapper` (the container holding the picker pane's
  `searchPill`, bottom child of `paneRoot`) gets the identical listener, and
  `searchWrapper.requestApplyInsets()` is called.

Inset value, framework API only, guarded the codebase's usual way
(`Build.VERSION.SDK_INT`): API 30+ uses
`insets.getInsets(WindowInsets.Type.navigationBars()).bottom`; API 29
(minSdk) uses `insets.systemWindowInsetBottom`. No other layout value
changes in either activity. Before this change neither window installed any
insets listener and nothing in the app consumed window insets, so dispatch
reaches the containers from the window unchanged.

### Other bottom-140px elements, checked from layout code

- PickerActivity: `screenRoot` has exactly two children, the drawer
  ScrollView (weight 1) and the search wrapper. The top bar, Favorites, and
  All Apps grid all live inside the scroll content, so nothing else is
  pinned in the bottom 140px. The search pill was the only covered control.
- StageActivity companion pane: every interactive element (the "Pick app"
  row; the Swap / Full / Clock / Close actions) lives inside the grouped
  card, and the card sits in a vertically centered column
  (`gravity = CENTER_VERTICAL`, screen-margin padding) inside a ScrollView.
  Nothing in the companion pane is bottom-pinned, so the strip covers no
  discrete companion control.
- StageActivity app-backdrop pane (over-app mode): the fallback caption
  "Tap to return to the app" is Gravity.BOTTOM with a 28dp bottom margin,
  so the label itself sits inside the strip zone. It is a non-interactive
  label, and the pane's tap target is the full pane (the container is
  clickable over its whole height), so dismissal stays tappable above the
  strip. Noted, deliberately not changed in this patch.
