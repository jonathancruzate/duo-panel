package com.jonathancruzate.duopanel

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast

/**
 * Publishes pinned shortcuts onto One UI Home using ShortcutManager.requestPinShortcut
 * with a legacy INSTALL_SHORTCUT broadcast fallback per SPEC.md & SPEC-ADDENDUM.md:
 * 1. Primary entry points: "Left Panel" (bottom-left corner) & "Right Panel" (bottom-right corner)
 * 2. Secondary per-app Solo shortcuts (target SoloTrampolineActivity)
 * 3. Secondary two-app Pair shortcuts (target SoloTrampolineActivity)
 */
object ShortcutHelper {

    const val ACTION_OPEN_PICKER = "com.jonathancruzate.duopanel.ACTION_OPEN_PICKER"
    const val ACTION_OPEN_STAGE = "com.jonathancruzate.duopanel.ACTION_OPEN_STAGE"
    const val ACTION_LAUNCH_SOLO = "com.jonathancruzate.duopanel.ACTION_LAUNCH_SOLO"
    const val ACTION_LAUNCH_PAIR = "com.jonathancruzate.duopanel.ACTION_LAUNCH_PAIR"
    const val ACTION_LEGACY_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"

    const val EXTRA_SIDE = "extra_side"
    const val EXTRA_TARGET_PKG = "extra_target_pkg"
    const val EXTRA_TARGET_LABEL = "extra_target_label"
    const val EXTRA_PRIMARY_PKG = "extra_primary_pkg"
    const val EXTRA_PRIMARY_LABEL = "extra_primary_label"
    const val EXTRA_SECONDARY_PKG = "extra_secondary_pkg"
    const val EXTRA_SECONDARY_LABEL = "extra_secondary_label"
    const val EXTRA_PAIRED_PKG = "extra_paired_pkg"
    const val EXTRA_REANCHORED = "extra_reanchored"
    const val EXTRA_PANEL_ENTRY = "extra_panel_entry"
    const val EXTRA_DEFAULT_REPLACE = "extra_default_replace"
    const val EXTRA_ANCHOR_TARGET = "extra_anchor_target"
    const val EXTRA_PHASE2_COMPANION = "extra_phase2_companion"
    const val EXTRA_EFFECTIVE_SIDE = "extra_effective_side"
    const val EXTRA_STAGE_ENTRY = "extra_stage_entry"
    const val EXTRA_STAGE_OVER_APP = "extra_stage_over_app"
    /**
     * PATCH 0.2.0: marks a Stage entry as the after-pick re-formed state: the
     * first picked app already runs on the entry side (its captured frame is
     * shown there) and the picker sits on the opposite side so the second app
     * of the pair can be picked immediately.
     */
    const val EXTRA_STAGE_SECOND_PICK = "extra_stage_second_pick"

    const val ANCHOR_TARGET_PICKER = "PICKER"
    const val ANCHOR_TARGET_COMPANION = "COMPANION"
    const val ANCHOR_TARGET_APP = "APP"

    fun syncDynamicPanelShortcuts(context: Context) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        try {
            val leftShortcut = buildPanelShortcutInfo(context, DuoPrefs.PanelSide.LEFT)
            val rightShortcut = buildPanelShortcutInfo(context, DuoPrefs.PanelSide.RIGHT)
            sm.dynamicShortcuts = listOf(leftShortcut, rightShortcut)
        } catch (_: Throwable) {
            // Ignore if launcher throttles dynamic shortcut updates
        }
    }

    fun requestPinPanelShortcut(context: Context, side: DuoPrefs.PanelSide): Boolean {
        val info = buildPanelShortcutInfo(context, side)
        val iconRes = if (side == DuoPrefs.PanelSide.LEFT) {
            R.drawable.ic_shortcut_left
        } else {
            R.drawable.ic_shortcut_right
        }
        val launchIntent = buildStageIntent(context, side)
        val label = side.title

        val pinned = requestPinWithFallback(
            context = context,
            shortcutInfo = info,
            legacyIntent = launchIntent,
            legacyLabel = label,
            legacyIconRes = iconRes
        )

        val cornerHint = if (side == DuoPrefs.PanelSide.LEFT) {
            "Pin 'Left Panel' to the bottom-left corner of One UI Home."
        } else {
            "Pin 'Right Panel' to the bottom-right corner of One UI Home."
        }
        Toast.makeText(
            context,
            if (pinned) "Requested '$label' shortcut. $cornerHint"
            else "Sent legacy shortcut install for '$label'.",
            Toast.LENGTH_LONG
        ).show()
        return pinned
    }

    fun requestPinSoloAppShortcut(
        context: Context,
        packageName: String,
        label: String,
        side: DuoPrefs.PanelSide
    ): Boolean {
        val shortcutId = "solo_${side.name.lowercase()}_${packageName}"
        val sideTag = if (side == DuoPrefs.PanelSide.LEFT) "Left" else "Right"
        val shortLabel = "$label ($sideTag)"
        val longLabel = "$label — $sideTag Panel Solo"

        val launchIntent = Intent(ACTION_LAUNCH_SOLO).apply {
            setClass(context, SoloTrampolineActivity::class.java)
            putExtra(EXTRA_TARGET_PKG, packageName)
            putExtra(EXTRA_TARGET_LABEL, label)
            putExtra(EXTRA_SIDE, side.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val defaultIconRes = if (side == DuoPrefs.PanelSide.LEFT) {
            R.drawable.ic_shortcut_left
        } else {
            R.drawable.ic_shortcut_right
        }

        val badgedBitmap = AppRepository.createBadgedSoloIconBitmap(context, packageName, side)
        val icon = if (badgedBitmap != null) {
            Icon.createWithBitmap(badgedBitmap)
        } else {
            Icon.createWithResource(context, defaultIconRes)
        }

        val info = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        DuoPrefs.addSavedSoloShortcut(
            context,
            DuoPrefs.SavedSoloShortcut(packageName = packageName, label = label, side = side)
        )

        val pinned = requestPinWithFallback(
            context = context,
            shortcutInfo = info,
            legacyIntent = launchIntent,
            legacyLabel = shortLabel,
            legacyIconRes = defaultIconRes
        )

        Toast.makeText(
            context,
            "Added Solo shortcut: $shortLabel",
            Toast.LENGTH_SHORT
        ).show()
        return pinned
    }

    fun requestPinPairShortcut(
        context: Context,
        primaryPackage: String,
        primaryLabel: String,
        secondaryPackage: String,
        secondaryLabel: String
    ): Boolean {
        val pairId = "${primaryPackage}__${secondaryPackage}"
        val shortcutId = "pair_$pairId"
        val shortLabel = "$primaryLabel + $secondaryLabel"
        val longLabel = "Pair: $primaryLabel | $secondaryLabel"

        val launchIntent = Intent(ACTION_LAUNCH_PAIR).apply {
            setClass(context, SoloTrampolineActivity::class.java)
            putExtra(EXTRA_PRIMARY_PKG, primaryPackage)
            putExtra(EXTRA_PRIMARY_LABEL, primaryLabel)
            putExtra(EXTRA_SECONDARY_PKG, secondaryPackage)
            putExtra(EXTRA_SECONDARY_LABEL, secondaryLabel)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val info = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_pair))
            .setIntent(launchIntent)
            .build()

        DuoPrefs.addSavedPairShortcut(
            context,
            DuoPrefs.SavedPairShortcut(
                id = pairId,
                primaryPackage = primaryPackage,
                primaryLabel = primaryLabel,
                secondaryPackage = secondaryPackage,
                secondaryLabel = secondaryLabel
            )
        )

        val pinned = requestPinWithFallback(
            context = context,
            shortcutInfo = info,
            legacyIntent = launchIntent,
            legacyLabel = shortLabel,
            legacyIconRes = R.drawable.ic_shortcut_pair
        )

        Toast.makeText(
            context,
            "Added Pair shortcut: $shortLabel",
            Toast.LENGTH_SHORT
        ).show()
        return pinned
    }

    /**
     * PATCH 0.1.6: The Left Panel / Right Panel shortcuts open the Simulated Stage
     * (StageActivity) first. The PickerActivity path stays reachable from the companion
     * "Pick app" action and standalone drawer use.
     */
    fun buildStageIntent(
        context: Context,
        side: DuoPrefs.PanelSide,
        overApp: Boolean = false,
        pairedPackage: String? = null,
        secondPick: Boolean = false
    ): Intent {
        return Intent(ACTION_OPEN_STAGE).apply {
            setClass(context, StageActivity::class.java)
            putExtra(EXTRA_SIDE, side.name)
            putExtra(EXTRA_STAGE_ENTRY, side.name)
            if (overApp) {
                putExtra(EXTRA_STAGE_OVER_APP, true)
            }
            if (!pairedPackage.isNullOrBlank()) {
                putExtra(EXTRA_PAIRED_PKG, pairedPackage)
            }
            if (secondPick) {
                putExtra(EXTRA_STAGE_SECOND_PICK, true)
            }
        }
    }

    fun buildPanelPickerIntent(
        context: Context,
        side: DuoPrefs.PanelSide,
        pairedPackage: String? = null,
        reanchored: Boolean = false,
        panelEntry: Boolean = false,
        defaultReplaceMode: Boolean = false,
        phase2Companion: Boolean = false
    ): Intent {
        return Intent(ACTION_OPEN_PICKER).apply {
            setClass(context, PickerActivity::class.java)
            putExtra(EXTRA_SIDE, side.name)
            if (!pairedPackage.isNullOrBlank()) {
                putExtra(EXTRA_PAIRED_PKG, pairedPackage)
            }
            if (reanchored) {
                putExtra(EXTRA_REANCHORED, true)
            }
            if (panelEntry) {
                putExtra(EXTRA_PANEL_ENTRY, side.name)
            }
            if (defaultReplaceMode) {
                putExtra(EXTRA_DEFAULT_REPLACE, true)
            }
            if (phase2Companion) {
                putExtra(EXTRA_PHASE2_COMPANION, true)
            }
        }
    }

    private fun buildPanelShortcutInfo(
        context: Context,
        side: DuoPrefs.PanelSide
    ): ShortcutInfo {
        val isLeft = side == DuoPrefs.PanelSide.LEFT
        val shortcutId = if (isLeft) "duopanel_left_panel" else "duopanel_right_panel"
        val shortLabel = if (isLeft) "Left Panel" else "Right Panel"
        val longLabel = if (isLeft) {
            "Left Panel (List Left, Companion Right)"
        } else {
            "Right Panel (List Right, Companion Left)"
        }
        val iconRes = if (isLeft) R.drawable.ic_shortcut_left else R.drawable.ic_shortcut_right
        val intent = buildStageIntent(context, side).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, iconRes))
            .setIntent(intent)
            .build()
    }

    private fun requestPinWithFallback(
        context: Context,
        shortcutInfo: ShortcutInfo,
        legacyIntent: Intent,
        legacyLabel: String,
        legacyIconRes: Int
    ): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java)
        try {
            if (sm != null && sm.isRequestPinShortcutSupported) {
                val accepted = sm.requestPinShortcut(shortcutInfo, null)
                if (accepted) {
                    return true
                }
            }
        } catch (_: Throwable) {
            // Fall back to legacy broadcast
        }

        // Legacy broadcast fallback per SPEC.md line 17
        return try {
            @Suppress("DEPRECATION")
            val broadcast = Intent(ACTION_LEGACY_INSTALL_SHORTCUT).apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, legacyIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, legacyLabel)
                putExtra(
                    Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(context, legacyIconRes)
                )
                putExtra("duplicate", false)
            }
            context.sendBroadcast(broadcast)
            false
        } catch (_: Throwable) {
            false
        }
    }
}
