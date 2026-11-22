# PATCH 0.1.9: Gemini design pass, blur fix + top status-bar inset (2026-11-21)

Direction: Jonathan reviewed the 0.1.8 Picker screenshot (v018-pill-visible.png)
and called out two defects: the blur is missing (backdrop text fully legible
through the translucent Picker) and the top overlaps (header row runs into the
status bar, the underlying screen's header ghosts into the companion card). He
directed that Gemini in agy take the design pass. Executed by Gemini 4 Neon
High via agy on the PC (standing rule: Gemini in agy is the design hand);
Muse coordinated, reviewed the diff, built, installed, and verified.
Gemini's full diagnosis and change log: DESIGN-PASS-0.1.9.md in this folder.

## Gemini's diagnosis (defect 1, blur)

Compound failure, evidenced from a device window dump (win.txt) plus code:

1. Primary cause: this DUO-DE Android 16 GSI's WindowManagerService
   BlurController reports mBlurEnabled=false (ro.surface_flinger
   background-blur support off on this build). The app gated the whole blur
   request on isCrossWindowBlurEnabled, and its
   addCrossWindowBlurEnabledListener fired with false on attach, so
   applyApi31WindowBlur actively set both radii to 0 and cleared
   FLAG_BLUR_BEHIND (the Picker's mAttrs in the dump carry neither). The
   55% scrim (0x8C0B0E1A, lightened in 0.1.7 Part C on the assumption of a
   strong blur behind it) was left painting over sharp, unblurred text.
   Consequence that shapes the fix: on this GSI, true cross-window
   blur-behind cannot render no matter what the app requests; the platform
   has it disabled. The app-side fix keeps the blur request permanently
   installed (for devices where the OS allows blur, e.g. One UI on the
   Fold8) and stops letting the listener zero it, and uses the enabled
   signal only to pick the scrim: 65% when blur is active, a deeper 85%
   fallback when the OS reports blur disabled, so backdrop text cannot
   collide with Picker labels either way.
2. Scrim was painted on the root view while the window background was
   repeatedly reset to a fresh transparent ColorDrawable, churning
   DecorView's LayerDrawable on every onCreate/onResume/listener callback.
   Fix: the scrim now lives on the window background itself
   (setWindowScrimColor, updated only on change); roots are transparent.
3. FLAG_BLUR_BEHIND and blurBehindRadius were dispatched non-atomically
   (addFlags fired before the radius was set). Fix: format, flags, and
   radius are set together in one attributes dispatch, alongside
   setBackgroundBlurRadius.
4. StageActivity applied its duplicated blur code before setContentView
   (no ViewRootImpl yet, so DecorView dropped the radius) and never
   re-applied it. Fix: the duplicate applyStageWindow is deleted; Stage
   uses the shared OneUiViews treatment after setContentView and again in
   onResume, and clearTranslucentBlurWindow for the over-app capture frame.

## Changes

- OneUiViews.kt: PANEL_BLUR_RADIUS_PX 120 -> 140; COLOR_BG_SCRIM
  0x8C0B0E1A (55%) -> 0xA60B0E1A (65%, blur active); new
  COLOR_BG_SCRIM_NO_BLUR 0xD90B0E1A (85%, OS blur disabled or API < 31);
  applyTranslucentBlurWindow reworked per the diagnosis (window-background
  scrim, unconditional atomic blur install on API 31+, re-apply on attach,
  listener only switches scrim); applyApi31WindowBlur now takes a radius
  and dispatches atomically; new clearTranslucentBlurWindow; new
  applyTopStatusBarInsetPadding (mirror of the 0.1.8 bottom listener:
  statusBars().top on API 30+, systemWindowInsetTop on API 29, added to
  base top padding, insets unconsumed). Hue 0B0E1A and accent 0381FE
  unchanged.
- PickerActivity.kt: screenRoot background transparent (window carries the
  scrim); applyTopStatusBarInsetPadding(drawerContent). The existing
  screenRoot.requestApplyInsets() dispatches to both the new top listener
  and the 0.1.8 bottom listener.
- StageActivity.kt: duplicated applyStageWindow deleted;
  clearTranslucentBlurWindow for the pre-capture frame;
  applyTranslucentBlurWindow after setContentView(root) in buildPanes()
  and in onResume; paneRoot / companion scroll / app-pane fallback
  transparent; applyTopStatusBarInsetPadding on the picker pane's
  drawerContent and the companion pane's rootCol; root.requestApplyInsets()
  after setContentView.
- CompanionPanelActivity.kt: applyTopStatusBarInsetPadding(root);
  scroll.requestApplyInsets() after setContentView. (Its window stays the
  solid Theme.DuoPanel per 0.1.5; only the inset is new.)

## Defect 2 (top overlap) basis

targetSdk 36 means Android 15/16 edge-to-edge is enforced (the dump shows
EDGE_TO_EDGE_ENFORCED with mContentInsets [0,60][0,140]): the header
containers' fixed 12dp/18dp top padding started content at 30px, inside
the 60px status bar. The top inset listener mirrors 0.1.8's bottom fix.

## Untouched (verified by diff review)

styles.xml, AndroidManifest.xml, colors.xml, all 0.1.8 search-focus and
bottom-inset code, launch flags, and all 0.1.7 options logic. Coordinator
diff review confirmed only the four files above changed.

## Build state

- versionName 0.1.9, versionCode 10 (app/build.gradle.kts).
- Gemini ran its own assembleDebug on the PC copy as a smoke check during
  the pass; the shipping build is the coordinator's rebuild below.
- Coordinator rebuild: BUILD SUCCESSFUL; APK staged at
  dist/DuoPanel-0.1.9-debug.apk (2,876,346 bytes); aapt2 badging confirms
  versionName 0.1.9 / versionCode 10, minSdk 29, targetSdk 36.
- INSTALLED and VERIFIED on the Duo 2026-11-21 (pm install -r; read-back
  versionName 0.1.9 / versionCode 10). Top overlap: FIXED in both windows
  (header title top edge y=90 in Picker and Stage dumps, below the 60px
  status bar; screenshots v019-picker-blur.png and v019-stage-blur.png).
  Blur: true cross-window blur cannot render on this GSI (device-verified
  mBlurEnabled=false in WindowManagerService), so the 85% no-blur fallback
  scrim is the rendering path here; it heavily dims the worst-case
  text-heavy backdrop versus 0.1.8 but larger titles remain partially
  legible, so the full dissolution bar is not met on this device and the
  definitive judgment belongs to the Fold8 (blur-enabled One UI, where the
  140px / 65% pairing applies). Regression: pill bounds identical to
  0.1.8 in both windows, one tap focuses, input text commits and filters.
  Full record: BUILD_NOTES.md section 16.
