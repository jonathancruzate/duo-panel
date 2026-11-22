# DuoPanel search-field input focus: code diagnosis, 2026-11-21

Code diagnosis only. No source file was changed. Device evidence:
`~/workspace/surface-duo-duo-de/input-focus-github-check-2026-11-21.md`
plus the on-device UI dumps `~/workspace/duo-panel/evidence-stage-nocommit.xml`
and `~/workspace/duo-panel/evidence-adbkeyboard-chrome-commit.xml`.

Symptom to explain: on the Surface Duo (DUO-DE v2026.10.28), synthetic taps
never give DuoPanel's Picker or Stage search EditText input focus
(`focused=false`; `dumpsys input_method` shows the IME session live but
`mStartedInputConnection=null` and a dummy EditorInfo, inputType 0x0), while
the identical tap + ADBKeyBoard broadcast in Chrome produced `focused=true`
and committed text minutes later on the same device and IME. A tap on the
Picker search pill's bounds has also intermittently finished PickerActivity;
the Stage survives the same tap but focus never lands.

## Q1. How the search EditText is built (both windows)

The two fields are near-identical programmatic constructions:

- PickerActivity: `PickerActivity.kt:840` (`buildOneUiContentView`), an
  `EditText(this)` with hint "Search", `setSingleLine(true)`,
  `background = null`, zero padding, weight-1 width inside the bottom
  `searchPill` LinearLayout, and a TextWatcher that filters the grids.
- StageActivity: `StageActivity.kt:727` (`buildPickerPane`), the same
  construction, additionally restoring `searchQuery` text/selection.

Configuration facts, identical in both:

- No explicit `isFocusable` / `isFocusableInTouchMode` is set. The field
  relies on EditText defaults, and the device dump proves the defaults are
  in effect: the Stage field reports `focusable="true"`, `clickable="true"`,
  `enabled="true"` (`evidence-stage-nocommit.xml`). View-level focusability
  is therefore NOT the defect.
- No `inputType` is set (only the side effect of `setSingleLine(true)`),
  and no `imeOptions`. Chrome's URL bar, the working control, carries an
  explicit input type from its own code.
- No `requestFocus()` call exists anywhere in the DuoPanel source tree
  (grep over all `.kt`: zero hits for `requestFocus`), and nothing calls
  `showSoftInput`. Focus is left entirely to the framework's touch-focus
  path: tap lands, EditText takes focus, window calls startInput.
- Parents: no `descendantFocusability` is set anywhere in the tree (grep:
  zero hits). The ancestors of each EditText (search pill LinearLayout,
  wrapper LinearLayout, root layout) are not clickable and not focusable.
  The search bar is a sibling BELOW the ScrollView, not inside it, in both
  layouts (PickerActivity `screenRoot`: ScrollView then
  `bottomSearchBarWrapper`; StageActivity `paneRoot`: ScrollView then
  `searchWrapper`), so ScrollView touch interception cannot reach it.

## Q2. How the two windows are configured

Both failing windows, and only these windows in the app, share one exact
window configuration:

- Manifest: `AndroidManifest.xml:57` (PickerActivity) and `:77`
  (StageActivity) both use theme `@style/Theme.DuoPanel.TranslucentPanel`
  and `android:windowSoftInputMode="stateHidden|adjustResize"` (`:58`, `:78`).
- Theme: `res/values/styles.xml:15-24` sets `windowIsTranslucent=true`,
  transparent `windowBackground`, `backgroundDimEnabled=false`, transparent
  status/navigation bars.
- Code: PickerActivity's window is treated by
  `OneUiViews.applyTranslucentBlurWindow` (`OneUiViews.kt:124`, called from
  `buildOneUiContentView` and again on every `onResume` /
  `onMultiWindowModeChanged`): transparent background drawable,
  `FLAG_DIM_BEHIND` cleared, and on API 31+ `applyApi31WindowBlur`
  (`OneUiViews.kt:218`) sets `setBackgroundBlurRadius(120)` and adds
  `FLAG_BLUR_BEHIND` with `lp.blurBehindRadius = 120`.
  StageActivity does the same itself in `applyStageWindow`
  (`StageActivity.kt:212-216`, blur radius and `FLAG_BLUR_BEHIND` at
  `:225-230`).
- Absent everywhere: a grep over the whole source tree finds zero
  occurrences of `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCHABLE`, or
  `FLAG_ALT_FOCUSABLE_IM`. Neither window is declared non-focusable, and
  the blur code copies existing LayoutParams and only touches
  `blurBehindRadius`.
- No full-screen scrim or overlay VIEW sits over the content in either
  window: the "scrim" is a background color painted on the root layout
  itself (`OneUiViews.COLOR_BG_SCRIM`, `OneUiViews.kt:36`), not a separate
  view in the hierarchy.

## Q3. finish() paths and the tap-closes-Picker observations

Every `finish()` in the two activities is bound to an explicit click
handler, none to a touch event:

- PickerActivity: `:595` (top-bar "Close" action), `:917` (overflow menu
  "Close" item). App picks finish through the SoloLauncher launch flow.
- StageActivity: `:390` (app-backdrop pane tap, over-app mode only),
  `:512` ("Close" action), `:1105`, `:1306`, `:1383` (pick/close actions).
- Neither activity overrides `onTouchEvent` or `dispatchTouchEvent`; there
  is no outside-touch dismissal in either activity. The only
  `setCanceledOnTouchOutside(true)` in the codebase is on the overflow
  floating-menu Dialog (`OneUiViews.kt:699`), which is not showing during
  search taps.

Conclusion: no DuoPanel code path can finish PickerActivity in response to
a touch on the search pill. The observed finishes were also intermittent
(the Picker survived the identical tap in the Voice-IME test and finished
in two other sessions), which no deterministic app handler would produce.
The tap-closes-Picker events are ruled out as app behavior; they belong to
the same GSI window/input anomaly as the focus failure (a translucent
window whose task the ROM intermittently drops or misdelivers under
synthetic input), not to a DuoPanel handler.

## Q4. Anything above the search field

No. In PickerActivity the search wrapper is the last child of the root
layout, topmost among siblings, with nothing drawn over it. In
StageActivity's picker pane the same holds. In Stage over-app mode the
captured-frame backdrop pane is a sibling HALF of the root (left or right),
never overlapping the picker pane; its tap-to-dismiss click listener
(`StageActivity.kt:390`) covers only its own half. The Companion pane
likewise occupies only its own half. No transparent view overlays the
search field in any layout.

## Ranked candidate causes

1. **TOP: focus is left entirely to the implicit touch-focus path inside
   the app's only translucent, blur-behind windows, and that is the path
   this ROM fails to complete.** Two halves:
   (a) Proven-from-code: neither activity ever establishes input focus
   programmatically. No `requestFocus`, no `showSoftInput`, no explicit
   `inputType`/`imeOptions`/`focusableInTouchMode` on either search field
   (Q1). If the touch-focus sequence does not complete, nothing in the app
   recovers it, and the field sits exactly as observed: focusable, enabled,
   never focused, no input connection.
   (b) Plausible mechanism: both failing windows are
   `windowIsTranslucent` + transparent background + `FLAG_BLUR_BEHIND`
   with a 120px background blur radius + `stateHidden` (Q2), a
   configuration Chrome's opaque window does not share. On this GSI the
   synthetic tap demonstrably still reaches click targets throughout
   DuoPanel (grid cells, toggles, checkboxes all worked under adb taps all
   day), so touch dispatch itself functions; what never happens is the
   EditText focus gain that triggers startInput. The window configuration
   is the only app-side variable that tracks the failure boundary exactly:
   same device, same IME, same injection, Chrome binds and DuoPanel does
   not, in both of DuoPanel's translucent windows.
2. **Plausible, minor: `windowSoftInputMode="stateHidden|adjustResize"`**
   (manifest `:58`, `:78`). Stock semantics say stateHidden only suppresses
   the IME on window entry and does not block tap focus, so it is ruled
   out as the sole cause, but on a ROM already misbehaving around IME
   state (duo-de #99, discussion #106) it is a contributing suspect worth
   removing in the same patch.
3. **Ruled out: the EditText is not focusable / parents block focus.**
   The device dump shows `focusable="true"`; no `descendantFocusability`
   exists in the tree; the field's ancestors are non-clickable, and the
   field is outside the ScrollView in both layouts.
4. **Ruled out: a window flag makes the window non-focusable.** Zero
   occurrences of `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCHABLE`, or
   `FLAG_ALT_FOCUSABLE_IM` in the source tree; the blur code does not add
   any.
5. **Ruled out: an overlay/scrim view intercepts the pill's touches.**
   No overlay view exists over the search field in either layout (Q4); the
   scrim is the root's background color.
6. **Ruled out (as app behavior): the tap-closes-Picker finishes.** No
   touch-driven finish path exists in the code (Q3), and the intermittency
   contradicts a deterministic handler.

## Proposed minimal fix for the top candidate

Make the search fields take input focus explicitly instead of relying on
the touch-focus path, in both windows. This is small, touches no visual
design, and is decisive as a test: a programmatic `requestFocus()` triggers
the window's startInput path directly, so if `dumpsys input_method` still
shows `mStartedInputConnection=null` afterward, the translucent/blur window
configuration itself is proven to be the blocker and the follow-up is an
opaque-window variant of the Picker.

1. `PickerActivity.kt`, the `searchBox` block at `:840`: add to the
   `apply { }` block: `isFocusableInTouchMode = true`,
   `inputType = InputType.TYPE_CLASS_TEXT`,
   `imeOptions = EditorInfo.IME_ACTION_SEARCH`. Then, after
   `searchPill.addView(searchBox)` in the same function, add a click
   handler on the pill that forces the connection:
   `searchPill.setOnClickListener { searchBox.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT) }`
   (the pill currently has no click listener, so taps on its padding or
   icon area do nothing at all today).
2. `StageActivity.kt`, the `searchBox` block at `:727`: the identical
   three property lines, and the identical `searchPill.setOnClickListener`
   after `searchPill.addView(searchBox)` in `buildPickerPane`.
3. Optional in the same patch, addressing candidate 2:
   `AndroidManifest.xml:58` and `:78`, change
   `android:windowSoftInputMode="stateHidden|adjustResize"` to
   `"adjustResize"` for PickerActivity and StageActivity.

Imports needed in both activities: `android.text.InputType`,
`android.view.inputmethod.EditorInfo`,
`android.view.inputmethod.InputMethodManager`.
