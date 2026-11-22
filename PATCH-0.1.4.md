# DuoPanel PATCH 0.1.4: anchor-relay Phase 2 (companion docking, second attempt)

Target: versionName 0.1.4, versionCode 5. Builds on 0.1.3 as-is. No new
permissions, no new dependencies, no pref key changes, no UI changes.

## Measured evidence from 0.1.3 on the test device (DUO-DE Android 16, StageSplit, two-panel policy OFF)

Two formations were run and measured by window bounds and the app's own launch log, one per entry side (flip_sides=true on this device, so LEFT entry ran effective RIGHT and RIGHT entry ran effective LEFT; both paths were therefore exercised). Both runs behaved identically:

1. Phase 1 WORKS. PANEL_PAIR_FORMED queued the anchor confinement; LEFT_FLOW_STEP2_ANCHOR dispatched; REANCHOR_SPAWN_PICKER reported OK; at Phase 2 time the confined Picker measured inMultiWindow=true with frame [0,0][1338,1800] (one stage, as intended).
2. Phase 2 FAILS. The confined Picker launched CompanionPanelActivity with the adjacent flag set (0x18101000 family). The log claimed OK, but FORMATION_CHECK 1.2s later read: picker inMultiWindow=true but frame [2700,0][4050,1800] (off the 2700-wide display), companion inMultiWindow=false with frame [0,0][2700,1800] (fullscreen). bothDocked=false in both runs.
3. dumpsys activity activities agreed: the Companion task was fullscreen, visible, resumed; the Picker task was fullscreen and not visible; the SplitRoot existed with sz=2 but visible=false. In other words, the companion's adjacent launch collapsed the existing split instead of filling its empty stage, and the picker was relocated off-display.

Established facts about this ROM from all runs so far (0.1.1 to 0.1.3):
- The AnchorActivity relay is the ONLY mechanism that has reliably placed a DuoPanel activity into a split stage: REANCHOR_SPAWN_PICKER has confined a fresh Picker in every single run.
- A confined activity can sit in a stage while the other stage is empty (proven repeatedly).
- Launching a second activity with adjacent flags from inside a stage does NOT fill the empty stage on this ROM; it breaks the split.
- The ActivityOptions windowing-mode reflection bundle throws NoSuchMethodException here; never rely on it. Intent flags only.

## The change: Phase 2 goes through the anchor relay

Replace the Phase 2 direct adjacent launch of the Companion with an anchor relay that mirrors the proven Phase 1 mechanism:

1. The confined Picker starts AnchorActivity with the adjacent flag set, carrying EXTRA_ANCHOR_TARGET set to CompanionPanelActivity (the extra already exists since 0.1.1C) plus the companion's own extras (paired app label, side) forwarded.
2. AnchorActivity, when its target is the Companion, launches CompanionPanelActivity with NO adjacent flags (plain NEW_TASK | MULTIPLE_TASK only), waits about 140ms, then calls finishAndRemoveTask, exactly the way it already does when spawning the Picker. The intent is that the Companion takes the anchor's place inside the stage the anchor was placed into.
3. If the anchor finds itself NOT in multi-window mode (the relay failed to place it in a stage), it falls back to launching the Companion WITH the adjacent flag set (the 0.1.3 behavior) and the Phase 2 log entry records path=relayFallbackDirect.
4. Log the relay as flowOrStrategy PANEL_PAIR_PHASE2 with optionsInfo naming path=relayAnchor (or relayFallbackDirect), the anchor's own inMultiWindow state, and the target. Keep the existing direct-path code available behind the fallback; do not delete it.

## One bounded retry

After FORMATION_CHECK, if bothDocked is false AND the picker is confined in multi-window AND the companion is not docked, retry Phase 2 exactly once using the OTHER path (relay if direct was used, direct if relay was used). Track the retry in memory only (a field on the Picker or SoloLauncher), never more than one retry per formation. Log the retry as FORMATION_RETRY with the path used and let the existing FORMATION_CHECK pattern (a second check after the retry, about 1.2s later) record the final state. If the retry also fails, leave the state as is; no further attempts, no loops.

## Unchanged

- The 0.1.3 calibration guard (Part A) stays exactly as is.
- FORMATION_CHECK stays, including its frames and inMultiWindow reporting.
- Solo launches, pair shortcuts, swap, full, replace-this-panel pick behavior: untouched.
- The mirror handling (effectiveSide / flipSides) stays exactly as is; both effective sides must use the same relay shape.

## Acceptance (will be verified on device by window bounds)

From home, for each entry (LEFT and RIGHT) and each flip state: after formation settles, the SplitRoot holds both DuoPanel tasks, the Picker frame equals its panel half, the Companion frame equals the other panel half, and no DuoPanel task is fullscreen. FORMATION_CHECK (or its post-retry successor) reports bothDocked=true.
