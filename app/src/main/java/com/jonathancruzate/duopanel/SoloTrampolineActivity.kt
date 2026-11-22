package com.jonathancruzate.duopanel

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * SoloTrampolineActivity — Invisible trampoline for per-app Solo and two-app Pair pinned shortcuts.
 *
 * Architecture notes (per SPEC.md line 47):
 * - Uses `@style/Theme.DuoPanel.Translucent` (`Theme.Translucent.NoTitleBar`) so no window flash
 *   occurs when tapped on One UI Home.
 * - Declares `android:excludeFromRecents="true"` and `android:taskAffinity=""` in AndroidManifest.xml:
 *   An empty `taskAffinity=""` is critical because without it, tapping a pinned shortcut on One UI Home
 *   would bring DuoPanel's main configuration task (`MainActivity`) to the foreground behind the
 *   trampoline and interfere with `FLAG_ACTIVITY_LAUNCH_ADJACENT` task pairing.
 * - Calls `finishAndRemoveTask()` after the two-step launch completes (with a tiny handler delay so
 *   WindowManager finishes binding the primary and secondary split tasks before the trampoline leaves).
 */
class SoloTrampolineActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incoming = intent
        if (incoming == null) {
            safeFinishAndRemove()
            return
        }

        val action = incoming.action ?: ShortcutHelper.ACTION_LAUNCH_SOLO
        when (action) {
            ShortcutHelper.ACTION_LAUNCH_PAIR -> {
                val rawPrimaryPkg = incoming.getStringExtra(ShortcutHelper.EXTRA_PRIMARY_PKG)
                val rawSecondaryPkg = incoming.getStringExtra(ShortcutHelper.EXTRA_SECONDARY_PKG)
                if (rawPrimaryPkg.isNullOrBlank() || rawSecondaryPkg.isNullOrBlank()) {
                    Toast.makeText(this, "Invalid Pair shortcut target", Toast.LENGTH_SHORT).show()
                    safeFinishAndRemove()
                    return
                }
                val flip = DuoPrefs.isFlipSides(this)
                val primaryPkg = if (flip) rawSecondaryPkg else rawPrimaryPkg
                val secondaryPkg = if (flip) rawPrimaryPkg else rawSecondaryPkg
                SoloLauncher.launchPairShortcut(
                    context = this,
                    primaryPkg = primaryPkg,
                    secondaryPkg = secondaryPkg,
                    onComplete = {
                        scheduleFinish()
                    }
                )
            }
            else -> {
                val targetPkg = incoming.getStringExtra(ShortcutHelper.EXTRA_TARGET_PKG)
                val requestedSide = DuoPrefs.PanelSide.fromString(
                    incoming.getStringExtra(ShortcutHelper.EXTRA_SIDE),
                    DuoPrefs.getPreferredSide(this)
                )
                val side = DuoPrefs.effectiveSide(this, requestedSide)
                if (targetPkg.isNullOrBlank()) {
                    Toast.makeText(this, "Invalid Solo shortcut target", Toast.LENGTH_SHORT).show()
                    safeFinishAndRemove()
                    return
                }
                SoloLauncher.launchSoloShortcut(
                    context = this,
                    targetPkg = targetPkg,
                    side = side,
                    onComplete = {
                        scheduleFinish()
                    }
                )
            }
        }

        // Safety fallback timeout so the trampoline always removes its task even if a callback is delayed
        handler.postDelayed({ safeFinishAndRemove() }, 450L)
    }

    private fun scheduleFinish() {
        handler.postDelayed({ safeFinishAndRemove() }, 120L)
    }

    private fun safeFinishAndRemove() {
        if (finished) return
        finished = true
        try {
            finishAndRemoveTask()
        } catch (_: Throwable) {
            finish()
        }
    }
}
