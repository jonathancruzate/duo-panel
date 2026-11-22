package com.jonathancruzate.duopanel

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * AnchorActivity — Translucent, zero-history trampoline for the SPEC-ADDENDUM LEFT shortcut flow.
 *
 * Sequence in LEFT flow (app goes LEFT/primary, Picker stays RIGHT/secondary):
 * 1. User taps an app in PickerActivity (opened via the "Left Panel" shortcut).
 * 2. PickerActivity launches the chosen app NORMALLY (FLAG_ACTIVITY_NEW_TASK) so it becomes
 *    the primary task.
 * 3. PickerActivity starts AnchorActivity (translucent, excludeFromRecents=true, taskAffinity="")
 *    and finishes itself.
 * 4. AnchorActivity immediately launches a fresh PickerActivity with adjacent flags
 *    (0x00100000 | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | NEW_TASK | MULTIPLE_TASK)
 *    so the new Picker lands in the RIGHT/secondary half beside the primary app, then
 *    AnchorActivity finishes with finishAndRemoveTask().
 */
class AnchorActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val sideRaw = intent?.getStringExtra(ShortcutHelper.EXTRA_SIDE)
            val targetSide = DuoPrefs.PanelSide.fromString(sideRaw, DuoPrefs.PanelSide.RIGHT)
            val effectiveSideRaw = intent?.getStringExtra(ShortcutHelper.EXTRA_EFFECTIVE_SIDE)
            val effectiveSide = DuoPrefs.PanelSide.fromString(
                effectiveSideRaw,
                DuoPrefs.effectiveSide(this, targetSide)
            )
            val pairedPkg = intent?.getStringExtra(ShortcutHelper.EXTRA_PAIRED_PKG)
            val phase2Companion =
                intent?.getBooleanExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION, false) == true
            val anchorTarget = intent?.getStringExtra(ShortcutHelper.EXTRA_ANCHOR_TARGET)
                ?: ShortcutHelper.ANCHOR_TARGET_PICKER
            val appPkg = intent?.getStringExtra(ShortcutHelper.EXTRA_TARGET_PKG)

            when (anchorTarget) {
                ShortcutHelper.ANCHOR_TARGET_COMPANION -> {
                    if (phase2Companion) {
                        SoloLauncher.spawnCompanionFromAnchorRelay(
                            anchor = this,
                            companionSide = targetSide,
                            effectiveCompanionSide = effectiveSide,
                            pairedPackage = pairedPkg
                        )
                    } else {
                        SoloLauncher.spawnAdjacentCompanionFromAnchor(
                            anchor = this,
                            companionSide = targetSide,
                            pairedPackage = pairedPkg
                        )
                    }
                }
                ShortcutHelper.ANCHOR_TARGET_APP -> {
                    if (!appPkg.isNullOrBlank()) {
                        SoloLauncher.spawnAdjacentAppFromAnchor(
                            anchor = this,
                            targetPkg = appPkg,
                            appSide = targetSide
                        )
                    }
                }
                else -> {
                    SoloLauncher.spawnAdjacentPickerFromAnchor(
                        anchor = this,
                        pickerSide = targetSide,
                        pairedPackage = pairedPkg,
                        phase2Companion = phase2Companion,
                        effectivePickerSide = effectiveSide
                    )
                }
            }
        }

        // Small delay before removing the translucent anchor task so WindowManager registers
        // the spawned activity launch before the anchor task is removed.
        handler.postDelayed({
            try {
                finishAndRemoveTask()
            } catch (_: Throwable) {
                finish()
            }
        }, 140L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
