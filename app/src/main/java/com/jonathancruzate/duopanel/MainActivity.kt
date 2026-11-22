package com.jonathancruzate.duopanel

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * MainActivity — Primary setup & management hub for DuoPanel.
 *
 * Implements SPEC-ADDENDUM.md, SPEC.md, and PATCH-0.1.2.md:
 * Rebuilt as a Samsung One UI Settings screen (`design-reference/oneui-home-settings.png`):
 * - Deep navy-black (#0B0E1A) background
 * - Grouped cards (#1A1E2E, 26dp rounded corners) separated by open background space
 * - Each row is a title with an optional gray subtitle under it, separated by hairline dividers
 *   inside the card only (never bordered boxes inside boxes)
 * - Samsung blue (#0381FE) used only for links, selection, and active states
 * - Floating rounded menu cards (`OneUiViews.showFloatingMenu`) for app pickers.
 */
class MainActivity : Activity() {

    enum class Tab(val title: String) {
        SHORTCUTS("Shortcuts & Pairs"),
        LAUNCH_LAB("Launch Lab"),
        SETTINGS("Settings"),
        ABOUT("About & Limits")
    }

    private var activeTab: Tab = Tab.SHORTCUTS
    private lateinit var contentContainer: LinearLayout
    private val tabButtons = mutableMapOf<Tab, TextView>()

    // PATCH 0.2.0 (verification fix): chrome views whose colors are baked at
    // build time. refreshChromeColors() re-applies the current palette to them
    // on every render, so a live Theme/Color change in the embedded Settings
    // tab repaints the whole page immediately instead of only the rebuilt
    // section cards (found in the 0.2.0 device pass: the page background kept
    // the old palette until the activity was re-entered).
    private var rootScroll: ScrollView? = null
    private var rootColumn: LinearLayout? = null
    private var tabBarView: LinearLayout? = null
    private var heroTitleView: TextView? = null
    private var heroSubtitleView: TextView? = null
    private val heroGraphics = mutableListOf<OneUiViews.PanelGraphicView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        ShortcutHelper.syncDynamicPanelShortcuts(this)
        buildRootLayout()
        renderActiveTab()
    }

    override fun onResume() {
        super.onResume()
        renderActiveTab()
    }

    private fun buildRootLayout() {
        val margin = OneUiViews.screenMarginPx(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            isFillViewport = true
        }
        rootScroll = scroll

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            setPadding(margin, OneUiViews.dp(this@MainActivity, 22), margin, margin)
        }
        rootColumn = root

        // One UI Viewing Area Header
        val viewingHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = OneUiViews.dp(this@MainActivity, 18)
            }
        }

        val heroGraphicRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                OneUiViews.PanelGraphicView(
                    context = this@MainActivity,
                    highlightLeft = true,
                    highlightRight = false,
                    sizeDp = 38
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        OneUiViews.dp(this@MainActivity, 38),
                        OneUiViews.dp(this@MainActivity, 38)
                    ).apply {
                        marginEnd = OneUiViews.dp(this@MainActivity, 10)
                    }
                    heroGraphics.add(this)
                }
            )
            addView(
                OneUiViews.PanelGraphicView(
                    context = this@MainActivity,
                    highlightLeft = false,
                    highlightRight = true,
                    sizeDp = 38
                ).apply {
                    heroGraphics.add(this)
                }
            )
        }
        viewingHeader.addView(heroGraphicRow)

        viewingHeader.addView(
            TextView(this).apply {
                text = "DuoPanel"
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, OneUiViews.dp(this@MainActivity, 8), 0, 0)
                heroTitleView = this
            }
        )
        viewingHeader.addView(
            TextView(this).apply {
                text = "One-panel-by-default shortcuts & One UI App Picker for Galaxy Z Fold8"
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, OneUiViews.dp(this@MainActivity, 4), 0, 0)
                heroSubtitleView = this
            }
        )
        root.addView(viewingHeader)

        // Quiet One UI Segmented Tab Bar
        val tabBar = LinearLayout(this).apply {
            tabBarView = this
            orientation = LinearLayout.HORIZONTAL
            background = OneUiViews.roundedBg(this@MainActivity, OneUiViews.COLOR_CARD_BG, 24)
            val p = OneUiViews.dp(this@MainActivity, 4)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = OneUiViews.dp(this@MainActivity, 14)
            }
        }

        for (tab in Tab.values()) {
            val btn = TextView(this).apply {
                text = tab.title
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                val vp = OneUiViews.dp(this@MainActivity, 9)
                val hp = OneUiViews.dp(this@MainActivity, 6)
                setPadding(hp, vp, hp, vp)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    activeTab = tab
                    renderActiveTab()
                }
            }
            tabButtons[tab] = btn
            tabBar.addView(btn)
        }
        root.addView(tabBar)

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(contentContainer)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun updateTabBarHighlight() {
        for ((tab, btn) in tabButtons) {
            val selected = (tab == activeTab)
            btn.background = if (selected) {
                OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 20)
            } else {
                null
            }
            btn.setTextColor(
                if (selected) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_SECONDARY
            )
            btn.setTypeface(
                null,
                if (selected) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    /**
     * PATCH 0.2.0 (verification fix): re-apply the current palette to the
     * chrome built once in [buildRootLayout]. Called on every render so a
     * live Theme/Color change repaints the page background, tab bar, and hero
     * header in the same pass as the rebuilt tab content.
     */
    private fun refreshChromeColors() {
        rootScroll?.setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
        rootColumn?.setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
        tabBarView?.background = OneUiViews.roundedBg(this, OneUiViews.COLOR_CARD_BG, 24)
        heroTitleView?.setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
        heroSubtitleView?.setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
        for (graphic in heroGraphics) graphic.invalidate()
    }

    private fun renderActiveTab() {
        refreshChromeColors()
        updateTabBarHighlight()
        contentContainer.removeAllViews()

        when (activeTab) {
            Tab.SHORTCUTS -> renderShortcutsAndPairsTab()
            Tab.LAUNCH_LAB -> {
                val standaloneCard = OneUiViews.createGroupedCard(this, bottomMarginDp = 12)
                standaloneCard.addView(
                    OneUiViews.createSettingsRow(
                        context = this,
                        title = "Open Standalone Launch Lab Activity",
                        subtitle = "Open Launch Lab diagnostics in a dedicated window",
                        titleAccent = true
                    ) {
                        startActivity(Intent(this, LaunchLabActivity::class.java))
                    }
                )
                contentContainer.addView(standaloneCard)
                contentContainer.addView(
                    LaunchLabActivity.buildLaunchLabSection(
                        activity = this,
                        showBackHeader = false,
                        onRefreshRequested = { renderActiveTab() }
                    )
                )
            }
            Tab.SETTINGS -> {
                val standaloneCard = OneUiViews.createGroupedCard(this, bottomMarginDp = 12)
                standaloneCard.addView(
                    OneUiViews.createSettingsRow(
                        context = this,
                        title = "Open Standalone Settings Activity",
                        subtitle = "Open Settings in a dedicated window",
                        titleAccent = true
                    ) {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
                contentContainer.addView(standaloneCard)
                contentContainer.addView(
                    SettingsActivity.buildSettingsSection(
                        activity = this,
                        showBackHeader = false,
                        onRefreshRequested = { renderActiveTab() }
                    )
                )
            }
            Tab.ABOUT -> {
                val standaloneCard = OneUiViews.createGroupedCard(this, bottomMarginDp = 12)
                standaloneCard.addView(
                    OneUiViews.createSettingsRow(
                        context = this,
                        title = "Open Standalone About Activity",
                        subtitle = "Open About & Honest Limits in a dedicated window",
                        titleAccent = true
                    ) {
                        startActivity(Intent(this, AboutActivity::class.java))
                    }
                )
                contentContainer.addView(standaloneCard)
                contentContainer.addView(
                    AboutActivity.buildAboutSection(
                        activity = this,
                        showBackHeader = false
                    )
                )
            }
        }
    }

    private fun renderShortcutsAndPairsTab() {
        // Card 1: Device Mode / Posture Gate (Fold8 Auto vs S24+ Always Split)
        val splitMode = DuoPrefs.getSplitMode(this)
        val postureCard = OneUiViews.createGroupedCard(this)
        postureCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Device mode: ${splitMode.label}",
                subtitle = "Galaxy Z Fold8 users: leave on Auto (splits when unfolded >= 600dp, opens normal on cover screen). Galaxy S24+ testers: select Always so split engages on a phone screen."
            ) {
                OneUiViews.showFloatingMenu(
                    activity = this,
                    items = DuoPrefs.SplitMode.values().map { mode ->
                        OneUiViews.FloatingMenuItem(
                            title = mode.label,
                            subtitle = mode.summary,
                            selected = (mode == splitMode)
                        ) {
                            DuoPrefs.setSplitMode(this, mode)
                            renderActiveTab()
                        }
                    }
                )
            }
        )
        postureCard.addView(OneUiViews.createHairlineDivider(this))
        postureCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Set Fold8 Auto (Wide >= 600dp)",
                subtitle = "Splits on unfolded display (>= 600dp), fullscreen on cover screen",
                titleAccent = (splitMode == DuoPrefs.SplitMode.AUTO),
                trailingView = if (splitMode == DuoPrefs.SplitMode.AUTO) {
                    OneUiViews.CheckmarkView(this, color = OneUiViews.COLOR_ONEUI_BLUE)
                } else {
                    null
                }
            ) {
                DuoPrefs.setSplitMode(this, DuoPrefs.SplitMode.AUTO)
                renderActiveTab()
            }
        )
        postureCard.addView(OneUiViews.createHairlineDivider(this))
        postureCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Set S24+ Always Split",
                subtitle = "Always attempts split-screen even on narrow (< 600dp) displays",
                titleAccent = (splitMode == DuoPrefs.SplitMode.ALWAYS),
                trailingView = if (splitMode == DuoPrefs.SplitMode.ALWAYS) {
                    OneUiViews.CheckmarkView(this, color = OneUiViews.COLOR_ONEUI_BLUE)
                } else {
                    null
                }
            ) {
                DuoPrefs.setSplitMode(this, DuoPrefs.SplitMode.ALWAYS)
                renderActiveTab()
            }
        )
        contentContainer.addView(postureCard)

        // Card 2: Primary Corner Shortcuts (Left & Right Panels)
        val primaryCard = OneUiViews.createGroupedCard(this)
        primaryCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Primary corner shortcuts (Left & Right panels)",
                subtitle = "Pin 'Left Panel' to the bottom-left corner of One UI Home and 'Right Panel' to the bottom-right corner. Tapping a corner opens the app list on that panel while the Companion surface waits on the other panel."
            )
        )
        primaryCard.addView(OneUiViews.createHairlineDivider(this))
        primaryCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Add LEFT shortcut",
                subtitle = "Left Panel • Pin to bottom-left corner (list & app on LEFT, Companion on RIGHT)",
                leadingView = OneUiViews.PanelGraphicView(
                    context = this,
                    highlightLeft = true,
                    highlightRight = false,
                    sizeDp = 36
                ),
                titleAccent = true
            ) {
                ShortcutHelper.requestPinPanelShortcut(this, DuoPrefs.PanelSide.LEFT)
            }
        )
        primaryCard.addView(OneUiViews.createHairlineDivider(this))
        primaryCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Open Left Picker",
                subtitle = "Open the Left Panel app drawer now"
            ) {
                startActivity(
                    ShortcutHelper.buildPanelPickerIntent(
                        context = this,
                        side = DuoPrefs.PanelSide.LEFT,
                        panelEntry = true
                    )
                )
            }
        )
        primaryCard.addView(OneUiViews.createHairlineDivider(this))
        primaryCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Add RIGHT shortcut",
                subtitle = "Right Panel • Pin to bottom-right corner (list & app on RIGHT, Companion on LEFT)",
                leadingView = OneUiViews.PanelGraphicView(
                    context = this,
                    highlightLeft = false,
                    highlightRight = true,
                    sizeDp = 36
                ),
                titleAccent = true
            ) {
                ShortcutHelper.requestPinPanelShortcut(this, DuoPrefs.PanelSide.RIGHT)
            }
        )
        primaryCard.addView(OneUiViews.createHairlineDivider(this))
        primaryCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Open Right Picker",
                subtitle = "Open the Right Panel app drawer now"
            ) {
                startActivity(
                    ShortcutHelper.buildPanelPickerIntent(
                        context = this,
                        side = DuoPrefs.PanelSide.RIGHT,
                        panelEntry = true
                    )
                )
            }
        )
        contentContainer.addView(primaryCard)

        // Card 3: Per-App Solo Shortcuts
        val savedSolos = DuoPrefs.getSavedSoloShortcuts(this)
        val soloCard = OneUiViews.createGroupedCard(this)
        soloCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Per-app Solo shortcuts (${savedSolos.size})",
                subtitle = "Curate your top 10–20 apps. Each Solo shortcut launches that app on one panel and shows the Companion surface (with 'Pick app') on the opposite panel."
            )
        )
        soloCard.addView(OneUiViews.createHairlineDivider(this))
        soloCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "+ Add Left Solo App Shortcut",
                subtitle = "Choose an app to pin as a Left Panel Solo shortcut",
                titleAccent = true
            ) {
                promptAddSoloShortcut(DuoPrefs.PanelSide.LEFT)
            }
        )
        soloCard.addView(OneUiViews.createHairlineDivider(this))
        soloCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "+ Add Right Solo App Shortcut",
                subtitle = "Choose an app to pin as a Right Panel Solo shortcut",
                titleAccent = true
            ) {
                promptAddSoloShortcut(DuoPrefs.PanelSide.RIGHT)
            }
        )

        if (savedSolos.isEmpty()) {
            soloCard.addView(OneUiViews.createHairlineDivider(this))
            soloCard.addView(
                OneUiViews.createSettingsRow(
                    context = this,
                    title = "No per-app Solo shortcuts added yet",
                    subtitle = "Tap either option above to pick an app (e.g. Outlook, Chrome, YouTube, OneNote) and pin its Solo shortcut."
                )
            )
        } else {
            for (item in savedSolos) {
                soloCard.addView(OneUiViews.createHairlineDivider(this))
                val remembered = DuoPrefs.getRememberedStrategy(this, item.packageName)?.shortName
                    ?: "Auto (A→B→C→D)"
                val icon = AppRepository.getAppIcon(this, item.packageName)
                val iconView = if (icon != null) {
                    ImageView(this).apply {
                        setImageDrawable(icon)
                        val sz = OneUiViews.dp(this@MainActivity, 32)
                        layoutParams = LinearLayout.LayoutParams(sz, sz)
                    }
                } else {
                    null
                }

                val trailingActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        OneUiViews.createTextAction(this@MainActivity, "Launch", activeOrLink = true) {
                            SoloLauncher.launchSoloShortcut(this@MainActivity, item.packageName, item.side)
                        }
                    )
                    addView(
                        OneUiViews.createTextAction(this@MainActivity, "Re-Pin") {
                            ShortcutHelper.requestPinSoloAppShortcut(
                                context = this@MainActivity,
                                packageName = item.packageName,
                                label = item.label,
                                side = item.side
                            )
                        }
                    )
                    addView(
                        OneUiViews.createTextAction(this@MainActivity, "Remove") {
                            DuoPrefs.removeSavedSoloShortcut(this@MainActivity, item.packageName, item.side)
                            renderActiveTab()
                        }
                    )
                }

                soloCard.addView(
                    OneUiViews.createSettingsRow(
                        context = this,
                        title = "${item.label} • ${item.side.title}",
                        subtitle = "${item.packageName} • Strategy: $remembered",
                        leadingView = iconView,
                        trailingView = trailingActions
                    ) {
                        SoloLauncher.launchSoloShortcut(this, item.packageName, item.side)
                    }
                )
            }
        }
        contentContainer.addView(soloCard)

        // Card 4: Two-App Pair Shortcuts
        val savedPairs = DuoPrefs.getSavedPairShortcuts(this)
        val pairCard = OneUiViews.createGroupedCard(this)
        pairCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Two-app Pair shortcuts (${savedPairs.size})",
                subtitle = "Pick two apps and pin a Pair shortcut that launches App 1 and App 2 side-by-side."
            )
        )
        pairCard.addView(OneUiViews.createHairlineDivider(this))
        pairCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "+ Create & Pin Two-App Pair Shortcut",
                subtitle = "Select primary (Left) and secondary (Right) apps",
                titleAccent = true
            ) {
                promptCreatePairShortcut()
            }
        )

        if (savedPairs.isEmpty()) {
            pairCard.addView(OneUiViews.createHairlineDivider(this))
            pairCard.addView(
                OneUiViews.createSettingsRow(
                    context = this,
                    title = "No App Pairs saved yet",
                    subtitle = "Create a Pair shortcut for your favorite two-app combinations or as a fallback for apps that prefer direct pairing."
                )
            )
        } else {
            for (pair in savedPairs) {
                pairCard.addView(OneUiViews.createHairlineDivider(this))
                val trailingActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        OneUiViews.createTextAction(this@MainActivity, "Launch", activeOrLink = true) {
                            SoloLauncher.launchPairShortcut(
                                context = this@MainActivity,
                                primaryPkg = pair.primaryPackage,
                                secondaryPkg = pair.secondaryPackage
                            )
                        }
                    )
                    addView(
                        OneUiViews.createTextAction(this@MainActivity, "Re-Pin") {
                            ShortcutHelper.requestPinPairShortcut(
                                context = this@MainActivity,
                                primaryPackage = pair.primaryPackage,
                                primaryLabel = pair.primaryLabel,
                                secondaryPackage = pair.secondaryPackage,
                                secondaryLabel = pair.secondaryLabel
                            )
                        }
                    )
                    addView(
                        OneUiViews.createTextAction(this@MainActivity, "Remove") {
                            DuoPrefs.removeSavedPairShortcut(this@MainActivity, pair.id)
                            renderActiveTab()
                        }
                    )
                }

                pairCard.addView(
                    OneUiViews.createSettingsRow(
                        context = this,
                        title = "${pair.primaryLabel} + ${pair.secondaryLabel}",
                        subtitle = "Left: ${pair.primaryPackage} • Right: ${pair.secondaryPackage}",
                        trailingView = trailingActions
                    ) {
                        SoloLauncher.launchPairShortcut(
                            context = this,
                            primaryPkg = pair.primaryPackage,
                            secondaryPkg = pair.secondaryPackage
                        )
                    }
                )
            }
        }
        contentContainer.addView(pairCard)
    }

    private fun promptAddSoloShortcut(side: DuoPrefs.PanelSide) {
        val apps = AppRepository.getLaunchableApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show()
            return
        }
        OneUiViews.showFloatingMenu(
            activity = this,
            items = apps.map { app ->
                OneUiViews.FloatingMenuItem(
                    title = app.label,
                    subtitle = "${side.title} • ${app.packageName}",
                    icon = app.icon
                ) {
                    ShortcutHelper.requestPinSoloAppShortcut(
                        context = this,
                        packageName = app.packageName,
                        label = app.label,
                        side = side
                    )
                    renderActiveTab()
                }
            }
        )
    }

    private fun promptCreatePairShortcut() {
        val apps = AppRepository.getLaunchableApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show()
            return
        }
        OneUiViews.showFloatingMenu(
            activity = this,
            items = apps.map { primaryApp ->
                OneUiViews.FloatingMenuItem(
                    title = primaryApp.label,
                    subtitle = "1st App (Left / Primary) • ${primaryApp.packageName}",
                    icon = primaryApp.icon
                ) {
                    OneUiViews.showFloatingMenu(
                        activity = this,
                        items = apps.map { secondaryApp ->
                            OneUiViews.FloatingMenuItem(
                                title = secondaryApp.label,
                                subtitle = "2nd App (Right / Secondary) with ${primaryApp.label}",
                                icon = secondaryApp.icon
                            ) {
                                ShortcutHelper.requestPinPairShortcut(
                                    context = this,
                                    primaryPackage = primaryApp.packageName,
                                    primaryLabel = primaryApp.label,
                                    secondaryPackage = secondaryApp.packageName,
                                    secondaryLabel = secondaryApp.label
                                )
                                renderActiveTab()
                            }
                        }
                    )
                }
            }
        )
    }
}
