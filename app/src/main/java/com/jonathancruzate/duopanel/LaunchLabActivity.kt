package com.jonathancruzate.duopanel

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * LaunchLabActivity — Arrival-day hardware diagnostic screen for Galaxy Z Fold8 & Galaxy S24+.
 *
 * Implements SPEC.md line 36, SPEC-ADDENDUM.md lines 32-33, TECH-NOTES.md notes 2, 3, & 6,
 * and PATCH-0.1.2.md One UI card/row restyle:
 * - Pick any installed app and target side (LEFT or RIGHT).
 * - Run "Left Flow Test" (SPEC-ADDENDUM re-anchor sequence), "Right Flow Test" (SPEC-ADDENDUM
 *   natural adjacent sequence), "Try Strategy A", "Try Strategy B", "Try Strategy C", or "Try Normal (Strategy D)".
 * - Displays compile-time/runtime flag probe (`FLAG_LAUNCH_ADJACENT_TO_RECENT = 0x00100000` vs
 *   `Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT = 0x00001000`, `distinct = true`) and hidden-API
 *   reflection status for `ActivityOptions.setLaunchWindowingMode(int)`.
 * - Persists and displays the live diagnostic attempt log with exact hex flag bits and results.
 */
class LaunchLabActivity : Activity() {

    private var rootContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        val margin = OneUiViews.screenMarginPx(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            setPadding(margin, OneUiViews.dp(this@LaunchLabActivity, 18), margin, margin)
        }
        rootContainer = container
        scroll.addView(container)
        setContentView(scroll)
        rebuildUI()
    }

    override fun onResume() {
        super.onResume()
        rebuildUI()
    }

    private fun rebuildUI() {
        val container = rootContainer ?: return
        container.removeAllViews()
        container.addView(
            buildLaunchLabSection(
                activity = this,
                showBackHeader = true,
                onRefreshRequested = { rebuildUI() }
            )
        )
    }

    companion object {
        private var selectedTargetPkg: String? = null
        private var selectedSide: DuoPrefs.PanelSide = DuoPrefs.PanelSide.LEFT

        fun buildLaunchLabSection(
            activity: Activity,
            showBackHeader: Boolean,
            onRefreshRequested: () -> Unit
        ): View {
            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            if (showBackHeader) {
                wrapper.addView(OneUiViews.createSettingsTopBar(activity, "Launch Lab Diagnostics"))
            }

            // Card 1: Toolchain / Flag / Posture Probe
            val probeCard = OneUiViews.createGroupedCard(activity)
            probeCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "SDK 36 multi-window & flag probe",
                    subtitle = "Live inspection of Intent flag constants, posture width, and reflection status"
                )
            )
            probeCard.addView(OneUiViews.createHairlineDivider(activity))

            val (_, reflectionProbeStatus) = SoloLauncher.tryCreateWindowingModeBundle(
                SoloLauncher.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
            )
            val cfg = activity.resources.configuration
            val halfBounds = SoloLauncher.computeHalfScreenBounds(activity, selectedSide)

            val hPad = OneUiViews.dp(activity, 20)
            val vPad = OneUiViews.dp(activity, 14)
            val probeText = TextView(activity).apply {
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(hPad, vPad, hPad, vPad)
                text = buildString {
                    appendLine("FLAG_LAUNCH_ADJACENT_TO_RECENT : 0x00100000")
                    appendLine(
                        String.format(
                            "Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT : 0x%08X (distinct=%b)",
                            SoloLauncher.PUBLIC_FLAG_LAUNCH_ADJACENT,
                            SoloLauncher.IS_PUBLIC_ADJACENT_DISTINCT
                        )
                    )
                    appendLine("Combined adjacentFlags()       : ${SoloLauncher.describeFlags(SoloLauncher.adjacentLaunchFlags())}")
                    appendLine("setLaunchWindowingMode probe   : $reflectionProbeStatus")
                    appendLine("setLaunchBounds(${selectedSide.name})          : ${halfBounds.toShortString()} (public API)")
                    appendLine(
                        "Window widthDp / swDp          : ${cfg.screenWidthDp}dp / ${cfg.smallestScreenWidthDp}dp (isWide=${SoloLauncher.isWideScreen(activity)})"
                    )
                    append("SplitMode setting              : ${DuoPrefs.getSplitMode(activity).name} (engage=${SoloLauncher.shouldEngageSplit(activity)})")
                }
            }
            probeCard.addView(probeText)
            wrapper.addView(probeCard)

            // Card 2: Interactive Hardware Flow & Strategy Tester
            val apps = AppRepository.getLaunchableApps(activity)
            if (selectedTargetPkg == null || apps.none { it.packageName == selectedTargetPkg }) {
                selectedTargetPkg = apps.firstOrNull()?.packageName
            }
            val currentPkg = selectedTargetPkg
            val currentLabel = if (currentPkg != null) {
                AppRepository.getAppLabel(activity, currentPkg)
            } else {
                "No launchable apps found"
            }
            val remembered = if (currentPkg != null) {
                DuoPrefs.getRememberedStrategy(activity, currentPkg)?.name ?: "none"
            } else {
                "none"
            }

            val runnerCard = OneUiViews.createGroupedCard(activity)
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Hardware flow & strategy tester",
                    subtitle = "Test Left/Right shortcut flows and Strategies A/B/C/D per app on Galaxy S24+ and Z Fold8"
                )
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            val appIcon = if (currentPkg != null) AppRepository.getAppIcon(activity, currentPkg) else null
            val leadingIconView = if (appIcon != null) {
                ImageView(activity).apply {
                    setImageDrawable(appIcon)
                    val sz = OneUiViews.dp(activity, 32)
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                }
            } else {
                null
            }

            // Target App Picker Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Pick Test App… ($currentLabel)",
                    subtitle = "Package: ${currentPkg ?: "none"} • Remembered Strategy: $remembered",
                    leadingView = leadingIconView,
                    titleAccent = true
                ) {
                    showAppPickerDialog(activity, apps) { chosen ->
                        selectedTargetPkg = chosen.packageName
                        onRefreshRequested()
                    }
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Target Side Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Side: ${selectedSide.name}",
                    subtitle = "${selectedSide.title} — tap to toggle between Left and Right"
                ) {
                    selectedSide = selectedSide.opposite()
                    onRefreshRequested()
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Left Flow Test Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Left Flow Test (App Left + Re-anchor Picker Right)",
                    subtitle = "Launches target app as primary on Left Panel and re-anchors Picker on Right Panel",
                    titleAccent = true
                ) {
                    val pkg = selectedTargetPkg
                    if (pkg == null) {
                        Toast.makeText(activity, "Select an app first", Toast.LENGTH_SHORT).show()
                    } else {
                        selectedSide = DuoPrefs.PanelSide.LEFT
                        SoloLauncher.launchPickedApp(
                            activity = activity,
                            pkg = pkg,
                            side = DuoPrefs.PanelSide.LEFT,
                            replaceMySide = false
                        )
                        onRefreshRequested()
                    }
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Right Flow Test Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Right Flow Test (App Right + Keep Left Alive)",
                    subtitle = "Launches target app adjacent on Right Panel while keeping Left Panel alive",
                    titleAccent = true
                ) {
                    val pkg = selectedTargetPkg
                    if (pkg == null) {
                        Toast.makeText(activity, "Select an app first", Toast.LENGTH_SHORT).show()
                    } else {
                        selectedSide = DuoPrefs.PanelSide.RIGHT
                        SoloLauncher.launchPickedApp(
                            activity = activity,
                            pkg = pkg,
                            side = DuoPrefs.PanelSide.RIGHT,
                            replaceMySide = false
                        )
                        onRefreshRequested()
                    }
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Strategy A Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Try Strategy A (Target Normal + Picker Adjacent)",
                    subtitle = "Launches target normally (NEW_TASK | CLEAR_TOP), then Picker with adjacent flags"
                ) {
                    val pkg = selectedTargetPkg ?: return@createSettingsRow
                    SoloLauncher.launchSoloShortcut(
                        context = activity,
                        targetPkg = pkg,
                        side = selectedSide,
                        forcedStrategy = DuoPrefs.Strategy.A
                    ) {
                        onRefreshRequested()
                    }
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Strategy B Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Try Strategy B (Target Split Flags + Picker Adjacent)",
                    subtitle = "Launches target itself with adjacent flags + split primary reflection, then Picker adjacent"
                ) {
                    val pkg = selectedTargetPkg ?: return@createSettingsRow
                    SoloLauncher.launchSoloShortcut(
                        context = activity,
                        targetPkg = pkg,
                        side = selectedSide,
                        forcedStrategy = DuoPrefs.Strategy.B
                    ) {
                        onRefreshRequested()
                    }
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Strategy C Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Try Strategy C (Freeform Half Bounds)",
                    subtitle = "Launches target with public ActivityOptions.setLaunchBounds(Rect) sized to half screen"
                ) {
                    val pkg = selectedTargetPkg ?: return@createSettingsRow
                    SoloLauncher.executeStrategyC(activity, pkg, selectedSide)
                    onRefreshRequested()
                }
            )
            runnerCard.addView(OneUiViews.createHairlineDivider(activity))

            // Strategy D Row
            runnerCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Try Normal (Strategy D Fullscreen)",
                    subtitle = "Graceful fullscreen launch without split flags"
                ) {
                    val pkg = selectedTargetPkg ?: return@createSettingsRow
                    SoloLauncher.executeStrategyD(activity, pkg, selectedSide, showRefusedToast = false)
                    onRefreshRequested()
                }
            )
            wrapper.addView(runnerCard)

            // Card 3: Diagnostic Attempt Log
            val logs = DuoPrefs.getLaunchLogs(activity)
            val logCard = OneUiViews.createGroupedCard(activity)
            logCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Attempt Log (${logs.size})",
                    subtitle = "Most recent multi-window launch attempts and flag bitmasks",
                    trailingView = OneUiViews.createTextAction(
                        context = activity,
                        label = "Clear Log",
                        activeOrLink = true
                    ) {
                        DuoPrefs.clearLaunchLogs(activity)
                        onRefreshRequested()
                    }
                )
            )

            if (logs.isEmpty()) {
                logCard.addView(OneUiViews.createHairlineDivider(activity))
                logCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = "No launch attempts recorded yet",
                        subtitle = "Tap any flow or strategy row above to inspect the exact Intent flags and ActivityOptions sent."
                    )
                )
            } else {
                for (entry in logs) {
                    logCard.addView(OneUiViews.createHairlineDivider(activity))
                    val entryBox = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(hPad, OneUiViews.dp(activity, 12), hPad, OneUiViews.dp(activity, 12))
                    }
                    val topLine = TextView(activity).apply {
                        text = "${entry.timestamp} • ${entry.flowOrStrategy} [side=${entry.side}]"
                        setTextColor(OneUiViews.COLOR_ONEUI_BLUE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    val detailLine = TextView(activity).apply {
                        typeface = Typeface.MONOSPACE
                        setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setPadding(0, OneUiViews.dp(activity, 4), 0, 0)
                        text = buildString {
                            appendLine("Target : ${entry.targetDesc}")
                            appendLine("Flags  : ${entry.flagsHex}")
                            appendLine("Options: ${entry.optionsInfo}")
                            append("Result : ${entry.result}")
                        }
                    }
                    entryBox.addView(topLine)
                    entryBox.addView(detailLine)
                    logCard.addView(entryBox)
                }
            }
            wrapper.addView(logCard)
            return wrapper
        }

        fun showAppPickerDialog(
            activity: Activity,
            apps: List<AppRepository.InstalledApp>,
            onPicked: (AppRepository.InstalledApp) -> Unit
        ) {
            if (apps.isEmpty()) {
                Toast.makeText(activity, "No launchable apps found", Toast.LENGTH_SHORT).show()
                return
            }
            OneUiViews.showFloatingMenu(
                activity = activity,
                items = apps.map { app ->
                    OneUiViews.FloatingMenuItem(
                        title = app.label,
                        subtitle = app.packageName,
                        icon = app.icon,
                        selected = (app.packageName == selectedTargetPkg)
                    ) {
                        onPicked(app)
                    }
                }
            )
        }
    }
}
