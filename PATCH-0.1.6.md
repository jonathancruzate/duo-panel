# PATCH 0.1.6: Simulated Stage (Jonathan's instruction, 2026-11-21)

His words: if the docked two-surface route is too difficult, instead of rendering the companion over an app, simulate it with a fullscreen app that LOOKS like half of it is over the other app.

## Design
- New StageActivity: ONE spanned fullscreen window, transparent background with the One UI blur/scrim treatment from DESIGN-REFERENCE.md. No split formation, no second DuoPanel surface, no overlay window.
- Two panes split at the display midpoint. Entry shortcut decides sides: Left Panel shortcut opens Stage with the picker pane on the LEFT and the companion pane on the RIGHT; Right Panel mirrors.
- Picker pane: the existing picker UI (grid, bottom search pill) drawn inside its half.
- Companion pane: the existing companion content (header, clock/date, other-panel app name when known; Pick app / Swap / Full / Clock / Close).
- Picking an app: launch it with the existing proven per-side flow and finish the Stage. If the app lands confined, the companion returns as the existing overlay surface, unchanged.
- Over-a-running-app case (CORRECTED by Jonathan, 2026-11-21 11:14 EST, supersedes the transparent-half idea): an ILLUSION. When the Stage opens while a real app is in front, DuoPanel first takes a ONE-TIME temporary screenshot of the current screen (MediaProjection one-shot, consent once per process), then renders that still frame as the backdrop of the app half while the companion half renders live. It looks like the companion floats over the live app; underneath it is just a photo. The bitmap is memory-only: never written to storage, released when the Stage closes. If capture consent is denied, fall back to the blur/scrim backdrop on that half. A tap on the screenshot half dismisses the Stage back to the real app.
- The docked two-surface formation (0.1.3 to 0.1.5 designs) stays parked and is bypassed in the Stage flow; keep the code, gate it off for Stage entries.

## Acceptance
- dumpsys shows ONE DuoPanel task for the Stage, spanned fullscreen; no second DuoPanel task, no overlay window.
- Screenshots: picker pane confined visually to its half; companion pane rendered on the other half; over-app case shows the captured frame as the app-half backdrop with the live companion beside it, and the frame file is never persisted.
- Pick from Stage lands the app the same way the 0.1.5 picker flow does (bounds read-back).
