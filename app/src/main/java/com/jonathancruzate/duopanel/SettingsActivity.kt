package com.jonathancruzate.duopanel

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * SettingsActivity — Configures DuoPanel behavior per SPEC.md line 37 & PATCH-0.1.2.md:
 * Rebuilt as a Samsung One UI Settings screen (`design-reference/oneui-home-settings.png`):
 * - Deep navy-black (#0B0E1A) background
 * - Grouped cards (#1A1E2E, 26dp corners) separated by open background space
 * - Each row is a title with an optional gray subtitle under it
 * - Hairline dividers inside cards only (never bordered boxes inside boxes)
 * - One UI pill switches (`OneUiViews.OneUiSwitch`) for toggles
 * - Plain text rows and floating rounded menu cards with check marks for selections/actions.
 */
class SettingsActivity : Activity() {

    private var rootContainer: LinearLayout? = null
    private var rootScroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        val margin = OneUiViews.screenMarginPx(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            isFillViewport = true
        }
        rootScroll = scroll
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            setPadding(margin, OneUiViews.dp(this@SettingsActivity, 18), margin, margin)
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
        // PATCH 0.2.0 (verification fix): the scroll/column backgrounds are
        // baked at onCreate; re-apply them on every rebuild so a live
        // Theme/Color change repaints the page background immediately.
        rootScroll?.setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
        container.setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
        container.removeAllViews()
        container.addView(
            buildSettingsSection(
                activity = this,
                showBackHeader = true,
                onRefreshRequested = { rebuildUI() }
            )
        )
    }

    companion object {
        fun buildSettingsSection(
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
                wrapper.addView(OneUiViews.createSettingsTopBar(activity, "Settings"))
            }

            // Card 1: Surface Duo Window Management Defaults + Panel Switches
            val togglesCard = OneUiViews.createGroupedCard(activity)

            // 1a. Non-interactive Duo window management defaults info row (PATCH 0.1.1B / 0.1.1C)
            togglesCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Surface Duo window management",
                    subtitle = "Default behavior mimics Surface Duo window management: one panel per app, the app list opens on the target panel while the companion panel waits on the other panel, and apps span only via Full."
                )
            )
            togglesCard.addView(OneUiViews.createHairlineDivider(activity))

            // 1b. Picker on the free panel One UI Pill Switch row
            val pickerOnFree = DuoPrefs.isPickerOnFreePanel(activity)
            val freePanelSwitch = OneUiViews.OneUiSwitch(activity).apply {
                isChecked = pickerOnFree
                setOnCheckedChangeListener { _, isChecked ->
                    DuoPrefs.setPickerOnFreePanel(activity, isChecked)
                    onRefreshRequested()
                }
            }
            togglesCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Picker on the free panel",
                    subtitle = "Duo style: corner shortcuts open the app list on the target panel with the companion surface on the other panel. For Solo shortcuts, the companion waits on the free panel with a 'Pick app' button so the next app is one tap away.",
                    trailingView = freePanelSwitch
                ) {
                    freePanelSwitch.isChecked = !freePanelSwitch.isChecked
                }
            )
            togglesCard.addView(OneUiViews.createHairlineDivider(activity))

            // 1c. Flip panel sides One UI Pill Switch row
            val flipSides = DuoPrefs.isFlipSides(activity)
            val flipSwitch = OneUiViews.OneUiSwitch(activity).apply {
                isChecked = flipSides
                setOnCheckedChangeListener { _, isChecked ->
                    DuoPrefs.setFlipSides(activity, isChecked)
                    DuoPrefs.setFlipCalibrated(activity, true)
                    onRefreshRequested()
                }
            }
            togglesCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Flip panel sides",
                    subtitle = "Turn on if apps open on the opposite side from the shortcut you tapped. DuoPanel can also set this automatically the first time it detects mirrored splits.",
                    trailingView = flipSwitch
                ) {
                    flipSwitch.isChecked = !flipSwitch.isChecked
                }
            )
            wrapper.addView(togglesCard)

            // Card 1b (PATCH 0.1.7): Picker display — One UI Home screen settings parity for the
            // Picker drawer: App size slider, Show app labels, Picker grid, Sort order with a
            // persisted custom order, and Show search bar. Home-screen-only One UI options
            // (layout, home/folder grid, media page, widget labels) do not map to DuoPanel.
            wrapper.addView(
                OneUiViews.createSectionHeader(
                    context = activity,
                    title = "Picker display",
                    subtitle = "How the app picker drawer looks and sorts, in the Picker and in the Stage."
                )
            )
            val displayCard = OneUiViews.createGroupedCard(activity)

            // App size: 3-step slider (Small / Medium / Large)
            val currentAppSize = DuoPrefs.getAppSize(activity)
            val appSizeBlock = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val hPad = OneUiViews.dp(activity, 20)
                setPadding(hPad, OneUiViews.dp(activity, 14), hPad, OneUiViews.dp(activity, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            appSizeBlock.addView(
                TextView(activity).apply {
                    text = "App size"
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                }
            )
            appSizeBlock.addView(
                TextView(activity).apply {
                    text = "Icon size in the app picker • ${currentAppSize.label} (${currentAppSize.iconDp}dp)"
                    setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, OneUiViews.dp(activity, 3), 0, 0)
                }
            )
            val appSizeSeek = SeekBar(activity).apply {
                max = DuoPrefs.AppSize.values().size - 1
                progress = currentAppSize.ordinal
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = OneUiViews.dp(activity, 6)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val size = DuoPrefs.AppSize.values()[
                            (seekBar?.progress ?: currentAppSize.ordinal)
                                .coerceIn(0, DuoPrefs.AppSize.values().size - 1)
                        ]
                        DuoPrefs.setAppSize(activity, size)
                        onRefreshRequested()
                    }
                })
            }
            appSizeBlock.addView(appSizeSeek)
            val appSizeLabels = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            DuoPrefs.AppSize.values().forEachIndexed { index, size ->
                appSizeLabels.addView(
                    TextView(activity).apply {
                        text = size.label
                        setTextColor(
                            if (size == currentAppSize) OneUiViews.COLOR_ONEUI_BLUE
                            else OneUiViews.COLOR_TEXT_SECONDARY
                        )
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        gravity = when (index) {
                            0 -> Gravity.START
                            DuoPrefs.AppSize.values().size - 1 -> Gravity.END
                            else -> Gravity.CENTER
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                )
            }
            appSizeBlock.addView(appSizeLabels)
            displayCard.addView(appSizeBlock)
            displayCard.addView(OneUiViews.createHairlineDivider(activity))

            // Show app labels switch
            val labelsSwitch = OneUiViews.OneUiSwitch(activity).apply {
                isChecked = DuoPrefs.isShowLabels(activity)
                setOnCheckedChangeListener { _, isChecked ->
                    DuoPrefs.setShowLabels(activity, isChecked)
                    onRefreshRequested()
                }
            }
            displayCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Show app labels",
                    subtitle = "Show app names under icons in the app picker",
                    trailingView = labelsSwitch
                ) {
                    labelsSwitch.isChecked = !labelsSwitch.isChecked
                }
            )
            displayCard.addView(OneUiViews.createHairlineDivider(activity))

            // Picker grid: Auto / 4 columns / 5 columns
            val currentGrid = DuoPrefs.getPickerGrid(activity)
            displayCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Picker grid",
                    subtitle = "${currentGrid.label} • Auto keeps the width-based columns (4 on a panel, 5 on a wide screen). A forced value wins in the Picker and the Stage."
                )
            )
            for (gridOption in DuoPrefs.PickerGrid.values()) {
                val selected = (gridOption == currentGrid)
                displayCard.addView(OneUiViews.createHairlineDivider(activity))
                displayCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = gridOption.label,
                        subtitle = when (gridOption) {
                            DuoPrefs.PickerGrid.AUTO -> "Columns follow the available width"
                            DuoPrefs.PickerGrid.FOUR -> "Always 4 columns"
                            DuoPrefs.PickerGrid.FIVE -> "Always 5 columns"
                        },
                        titleAccent = selected,
                        trailingView = if (selected) {
                            OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                        } else {
                            null
                        }
                    ) {
                        DuoPrefs.setPickerGrid(activity, gridOption)
                        onRefreshRequested()
                    }
                )
            }
            displayCard.addView(OneUiViews.createHairlineDivider(activity))

            // Sort order: Alphabetical / Custom (+ reset)
            val currentSort = DuoPrefs.getSortOrder(activity)
            displayCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Sort order",
                    subtitle = "${currentSort.label} • Custom keeps your own order: long-press and drag apps in the picker's All apps grid. Favorites are not part of the custom order."
                )
            )
            for (sortOption in DuoPrefs.SortOrder.values()) {
                val selected = (sortOption == currentSort)
                displayCard.addView(OneUiViews.createHairlineDivider(activity))
                displayCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = sortOption.label,
                        subtitle = if (sortOption == DuoPrefs.SortOrder.CUSTOM) {
                            "Your order, seeded from alphabetical the first time"
                        } else {
                            "Apps sorted A to Z by name"
                        },
                        titleAccent = selected,
                        trailingView = if (selected) {
                            OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                        } else {
                            null
                        }
                    ) {
                        DuoPrefs.setSortOrder(activity, sortOption)
                        if (sortOption == DuoPrefs.SortOrder.CUSTOM) {
                            DuoPrefs.ensureCustomOrderSeeded(
                                activity,
                                AppRepository.getLaunchableApps(activity).map { it.packageName }
                            )
                        }
                        onRefreshRequested()
                    }
                )
            }
            displayCard.addView(OneUiViews.createHairlineDivider(activity))
            displayCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Reset custom order",
                    subtitle = "Put the custom order back to alphabetical",
                    titleAccent = true
                ) {
                    DuoPrefs.resetCustomOrder(
                        activity,
                        AppRepository.getLaunchableApps(activity).map { it.packageName }
                    )
                    Toast.makeText(
                        activity,
                        "Custom order reset to alphabetical.",
                        Toast.LENGTH_SHORT
                    ).show()
                    onRefreshRequested()
                }
            )
            displayCard.addView(OneUiViews.createHairlineDivider(activity))

            // Show search bar switch
            val searchSwitch = OneUiViews.OneUiSwitch(activity).apply {
                isChecked = DuoPrefs.isShowSearchBar(activity)
                setOnCheckedChangeListener { _, isChecked ->
                    DuoPrefs.setShowSearchBar(activity, isChecked)
                    onRefreshRequested()
                }
            }
            displayCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Show search bar",
                    subtitle = "Show the search pill at the bottom of the app picker",
                    trailingView = searchSwitch
                ) {
                    searchSwitch.isChecked = !searchSwitch.isChecked
                }
            )
            displayCard.addView(OneUiViews.createHairlineDivider(activity))

            // Hide apps (PATCH 0.1.7 Part B): opens the One UI style Hide Apps screen.
            val hiddenCount = DuoPrefs.getHiddenPackages(activity).size
            displayCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Hide apps",
                    subtitle = if (hiddenCount == 0) {
                        "No apps hidden • Hidden apps disappear from the DuoPanel picker only"
                    } else {
                        "$hiddenCount app(s) hidden • Hidden apps disappear from the DuoPanel picker only"
                    },
                    titleAccent = true
                ) {
                    activity.startActivity(Intent(activity, HideAppsActivity::class.java))
                }
            )
            wrapper.addView(displayCard)

            // Card 1d (PATCH 0.2.0): Appearance — Theme (System / Light / Dark) and
            // Color, mirroring the Meta Muse Widget's color themes (One UI /
            // System palette / Stephany pink / Custom hue + saturation). The
            // current look is the Dark palette with the One UI accent; System
            // theme on a night-mode device plus One UI color is unchanged.
            wrapper.addView(
                OneUiViews.createSectionHeader(
                    context = activity,
                    title = "Appearance",
                    subtitle = "Theme and accent color for the Picker, the Stage, the Companion, and Settings."
                )
            )
            val appearanceCard = OneUiViews.createGroupedCard(activity)
            val currentThemeMode = DuoPrefs.getThemeMode(activity)
            appearanceCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Theme",
                    subtitle = "${currentThemeMode.label} • ${currentThemeMode.summary}"
                )
            )
            for (modeOption in DuoPrefs.ThemeMode.values()) {
                val selected = (modeOption == currentThemeMode)
                appearanceCard.addView(OneUiViews.createHairlineDivider(activity))
                appearanceCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = modeOption.label,
                        subtitle = modeOption.summary,
                        titleAccent = selected,
                        trailingView = if (selected) {
                            OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                        } else {
                            null
                        }
                    ) {
                        DuoPrefs.setThemeMode(activity, modeOption)
                        DuoTheme.applyTo(activity)
                        onRefreshRequested()
                    }
                )
            }
            appearanceCard.addView(OneUiViews.createHairlineDivider(activity))
            val currentColorTheme = DuoPrefs.getColorTheme(activity)
            val effectiveDark = DuoTheme.isDark(activity)
            appearanceCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Color",
                    subtitle = "${currentColorTheme.label} • Drives selection and active states, favorite stars, search accents, badges, and switch on-states"
                )
            )
            for (colorOption in DuoPrefs.ColorTheme.values()) {
                val selected = (colorOption == currentColorTheme)
                val swatchColor = DuoTheme.accentFor(activity, colorOption, effectiveDark)
                val swatch = View(activity).apply {
                    // DESIGN-PASS-0.2.0: hairline outline keeps light swatches
                    // crisp on white cards.
                    background = OneUiViews.roundedBg(
                        activity, swatchColor, 999,
                        strokeColor = OneUiViews.COLOR_HAIRLINE_DIVIDER, strokeWidthDp = 1
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        OneUiViews.dp(activity, 22), OneUiViews.dp(activity, 22)
                    )
                }
                appearanceCard.addView(OneUiViews.createHairlineDivider(activity))
                appearanceCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = colorOption.label,
                        subtitle = colorOption.summary,
                        titleAccent = selected,
                        trailingView = swatch
                    ) {
                        DuoPrefs.setColorTheme(activity, colorOption)
                        DuoTheme.applyTo(activity)
                        onRefreshRequested()
                    }
                )
            }
            if (currentColorTheme == DuoPrefs.ColorTheme.CUSTOM) {
                // Custom hue + saturation sliders (the widget's custom theme).
                appearanceCard.addView(OneUiViews.createHairlineDivider(activity))
                val hueValueText = TextView(activity)
                val hueBlock = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val hPad = OneUiViews.dp(activity, 20)
                    setPadding(hPad, OneUiViews.dp(activity, 14), hPad, OneUiViews.dp(activity, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                hueBlock.addView(TextView(activity).apply {
                    text = "Hue"
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                hueValueText.apply {
                    text = "Hue ${DuoPrefs.getCustomHue(activity)}° of 359"
                    setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, OneUiViews.dp(activity, 3), 0, 0)
                }
                hueBlock.addView(hueValueText)
                hueBlock.addView(SeekBar(activity).apply {
                    max = 359
                    progress = DuoPrefs.getCustomHue(activity)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = OneUiViews.dp(activity, 6) }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                DuoPrefs.setCustomHue(activity, progress)
                                hueValueText.text = "Hue $progress° of 359"
                            }
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            DuoTheme.applyTo(activity)
                            onRefreshRequested()
                        }
                    })
                })
                appearanceCard.addView(hueBlock)

                val satValueText = TextView(activity)
                val satBlock = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val hPad = OneUiViews.dp(activity, 20)
                    setPadding(hPad, OneUiViews.dp(activity, 8), hPad, OneUiViews.dp(activity, 12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                satBlock.addView(TextView(activity).apply {
                    text = "Saturation"
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                satValueText.apply {
                    text = "Saturation ${DuoPrefs.getCustomSat(activity)}%"
                    setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, OneUiViews.dp(activity, 3), 0, 0)
                }
                satBlock.addView(satValueText)
                satBlock.addView(SeekBar(activity).apply {
                    max = 100
                    progress = DuoPrefs.getCustomSat(activity)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = OneUiViews.dp(activity, 6) }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                DuoPrefs.setCustomSat(activity, progress)
                                satValueText.text = "Saturation $progress%"
                            }
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            DuoTheme.applyTo(activity)
                            onRefreshRequested()
                        }
                    })
                })
                appearanceCard.addView(satBlock)
            }
            wrapper.addView(appearanceCard)

            // Card 1e (PATCH 0.2.0): Picker sections — arrange and toggle the
            // sections above All Apps (Recent apps / Frequently used /
            // Favorites). All Apps is the always-present base list and stays
            // pinned last: it is the full catalog the sections sample from.
            wrapper.addView(
                OneUiViews.createSectionHeader(
                    context = activity,
                    title = "Picker sections",
                    subtitle = "Choose which sections appear above All apps and set their order. All apps always stays last."
                )
            )
            val sectionsCard = OneUiViews.createGroupedCard(activity)
            val usageGranted = UsageRepository.hasUsageAccess(activity)
            val sectionOrder = DuoPrefs.getSectionOrder(activity)
            sectionOrder.forEachIndexed { index, section ->
                if (index > 0) {
                    sectionsCard.addView(OneUiViews.createHairlineDivider(activity))
                }
                val sectionSwitch = OneUiViews.OneUiSwitch(activity).apply {
                    isChecked = DuoPrefs.isSectionVisible(activity, section)
                    setOnCheckedChangeListener { _, isChecked ->
                        DuoPrefs.setSectionVisible(activity, section, isChecked)
                        onRefreshRequested()
                    }
                }
                val moveControls = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                fun moveButton(label: String, description: String, enabled: Boolean, delta: Int): TextView {
                    return TextView(activity).apply {
                        text = label
                        contentDescription = description
                        gravity = Gravity.CENTER
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                        // DESIGN-PASS-0.2.0-SECTIONS: chevrons stay neutral
                        // (textSecondary, never accent); boundary chevrons stay
                        // in place, dimmed (0.28 dark / 0.35 light alpha).
                        setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                        alpha = if (enabled) 1f else if (DuoTheme.isDark(activity)) 0.28f else 0.35f
                        val hPad = OneUiViews.dp(activity, 7)
                        setPadding(hPad, OneUiViews.dp(activity, 6), hPad, OneUiViews.dp(activity, 6))
                        if (enabled) {
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                val order = DuoPrefs.getSectionOrder(activity).toMutableList()
                                val from = order.indexOf(section)
                                val to = from + delta
                                if (from >= 0 && to in order.indices) {
                                    order.removeAt(from)
                                    order.add(to, section)
                                    DuoPrefs.setSectionOrder(activity, order)
                                }
                                onRefreshRequested()
                            }
                        }
                    }
                }
                moveControls.addView(
                    moveButton("↑", "Move ${section.title} up", index > 0, -1)
                )
                moveControls.addView(
                    moveButton("↓", "Move ${section.title} down", index < sectionOrder.size - 1, 1)
                )
                moveControls.addView(sectionSwitch)
                // DESIGN-PASS-0.2.0-SECTIONS dynamic subtitles: state-aware
                // (off / needs access / normal) instead of a static summary.
                val sectionVisible = DuoPrefs.isSectionVisible(activity, section)
                val rowSubtitle = when {
                    !sectionVisible -> "Hidden from the app picker"
                    section == DuoPrefs.PickerSection.FAVORITES ->
                        "Starred apps pinned above All apps"
                    !usageGranted -> "Horizontal row • Needs Usage access (below)"
                    section == DuoPrefs.PickerSection.RECENT ->
                        "Horizontal row of recently used apps"
                    else -> "Horizontal row of most used apps"
                }
                sectionsCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = section.title,
                        subtitle = rowSubtitle,
                        trailingView = moveControls
                    ) {
                        sectionSwitch.isChecked = !sectionSwitch.isChecked
                    }
                )
            }
            // Usage access closes the card (sections spec), mirroring the
            // Notification access row in the badges card: accent title, grant
            // state subtitle, tap opens the system settings.
            sectionsCard.addView(OneUiViews.createHairlineDivider(activity))
            sectionsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Usage access",
                    subtitle = if (usageGranted) {
                        "Granted • DuoPanel can read recent and frequent app usage"
                    } else {
                        "Not granted • Tap to open system settings and allow DuoPanel"
                    },
                    titleAccent = true
                ) {
                    PickerSections.openUsageAccessSettings(activity)
                }
            )
            wrapper.addView(sectionsCard)

            // Card 1c (PATCH 0.1.7 Part B): Notification badges on Picker icons.
            // Mirrors One UI's "App icon badges" screen: badges on/off, badge style
            // Dot / Number, and the system Notification access grant state. No grant
            // means no badges, and the rows say why; counts are never faked.
            wrapper.addView(
                OneUiViews.createSectionHeader(
                    context = activity,
                    title = "Notification badges",
                    subtitle = "Badges on app icons in the DuoPanel picker, from your active notifications."
                )
            )
            val badgesCard = OneUiViews.createGroupedCard(activity)
            val accessGranted = DuoNotificationListener.isAccessGranted(activity)
            val badgesOn = DuoPrefs.isBadgesEnabled(activity)
            val badgesSwitch = OneUiViews.OneUiSwitch(activity).apply {
                isChecked = badgesOn
                setOnCheckedChangeListener { _, isChecked ->
                    DuoPrefs.setBadgesEnabled(activity, isChecked)
                    onRefreshRequested()
                }
            }
            badgesCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "App icon badges",
                    subtitle = when {
                        !accessGranted ->
                            "Needs Notification access (below) before badges can appear"
                        badgesOn ->
                            "Badges show on picker icons for apps with active notifications"
                        else ->
                            "Off • Turn on to show badges on picker icons"
                    },
                    trailingView = badgesSwitch
                ) {
                    badgesSwitch.isChecked = !badgesSwitch.isChecked
                }
            )
            badgesCard.addView(OneUiViews.createHairlineDivider(activity))
            badgesCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Notification access",
                    subtitle = if (accessGranted) {
                        "Granted • DuoPanel can count active notifications per app"
                    } else {
                        "Not granted • Tap to open system settings and allow DuoPanel"
                    },
                    titleAccent = true
                ) {
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    } catch (_: Throwable) {
                        Toast.makeText(
                            activity,
                            "Could not open notification access settings on this device.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            val currentBadgeStyle = DuoPrefs.getBadgeStyle(activity)
            badgesCard.addView(OneUiViews.createHairlineDivider(activity))
            badgesCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Badge style",
                    subtitle = "${currentBadgeStyle.label} • Dot shows a dot; Number shows the active notification count (99+ max)"
                )
            )
            for (style in DuoPrefs.BadgeStyle.values()) {
                val selected = (style == currentBadgeStyle)
                badgesCard.addView(OneUiViews.createHairlineDivider(activity))
                badgesCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = style.label,
                        subtitle = if (style == DuoPrefs.BadgeStyle.DOT) {
                            "Small Samsung-blue dot at the icon's top-right"
                        } else {
                            "Pill with the notification count at the icon's top-right"
                        },
                        titleAccent = selected,
                        trailingView = if (selected) {
                            OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                        } else {
                            null
                        }
                    ) {
                        DuoPrefs.setBadgeStyle(activity, style)
                        onRefreshRequested()
                    }
                )
            }
            wrapper.addView(badgesCard)

            // Card 2: Split Mode (Auto / Always / Off) — grouped card with plain rows + floating menu support
            val currentSplitMode = DuoPrefs.getSplitMode(activity)
            val splitCard = OneUiViews.createGroupedCard(activity)
            splitCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Split mode",
                    subtitle = "${currentSplitMode.label} • Leave on Auto for Galaxy Z Fold8 (splits unfolded >= 600dp, fullscreen on cover). Choose Always to test on Galaxy S24+."
                ) {
                    OneUiViews.showFloatingMenu(
                        activity = activity,
                        items = DuoPrefs.SplitMode.values().map { mode ->
                            OneUiViews.FloatingMenuItem(
                                title = mode.label,
                                subtitle = mode.summary,
                                selected = (mode == currentSplitMode)
                            ) {
                                DuoPrefs.setSplitMode(activity, mode)
                                onRefreshRequested()
                            }
                        }
                    )
                }
            )

            for (mode in DuoPrefs.SplitMode.values()) {
                val selected = (mode == currentSplitMode)
                splitCard.addView(OneUiViews.createHairlineDivider(activity))
                splitCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = mode.label,
                        subtitle = mode.summary,
                        titleAccent = selected,
                        trailingView = if (selected) {
                            OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                        } else {
                            null
                        }
                    ) {
                        DuoPrefs.setSplitMode(activity, mode)
                        onRefreshRequested()
                    }
                )
            }
            wrapper.addView(splitCard)

            // Card 3: Preferred Default Side & Picker Replacement Behavior
            val currentSide = DuoPrefs.getPreferredSide(activity)
            val autoReplace = DuoPrefs.isPickerAutoReplace(activity)
            val prefsCard = OneUiViews.createGroupedCard(activity)

            prefsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Preferred default side",
                    subtitle = "${currentSide.title} • Used when opening Picker standalone or adding new per-app Solo shortcuts (Left = Top, Right = Bottom in portrait)."
                ) {
                    OneUiViews.showFloatingMenu(
                        activity = activity,
                        items = DuoPrefs.PanelSide.values().map { s ->
                            OneUiViews.FloatingMenuItem(
                                title = s.title,
                                selected = (s == currentSide)
                            ) {
                                DuoPrefs.setPreferredSide(activity, s)
                                onRefreshRequested()
                            }
                        }
                    )
                }
            )
            for (side in DuoPrefs.PanelSide.values()) {
                val isSelected = (side == currentSide)
                prefsCard.addView(OneUiViews.createHairlineDivider(activity))
                prefsCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = side.title,
                        subtitle = if (side == DuoPrefs.PanelSide.LEFT) {
                            "Primary panel (Left in unfolded/landscape, Top in portrait)"
                        } else {
                            "Secondary panel (Right in unfolded/landscape, Bottom in portrait)"
                        },
                        titleAccent = isSelected,
                        trailingView = if (isSelected) {
                            OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                        } else {
                            null
                        }
                    ) {
                        DuoPrefs.setPreferredSide(activity, side)
                        onRefreshRequested()
                    }
                )
            }

            prefsCard.addView(OneUiViews.createHairlineDivider(activity))
            prefsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Picker behavior on app pick",
                    subtitle = "${if (autoReplace) "Auto-Replace (Default)" else "Keep Picker Alive"} • Controls whether PickerActivity closes after launching an app into its own panel slot."
                ) {
                    OneUiViews.showFloatingMenu(
                        activity = activity,
                        items = listOf(
                            OneUiViews.FloatingMenuItem(
                                title = "Auto-Replace (Default)",
                                selected = autoReplace
                            ) {
                                DuoPrefs.setPickerAutoReplace(activity, true)
                                onRefreshRequested()
                            },
                            OneUiViews.FloatingMenuItem(
                                title = "Keep Picker Alive",
                                selected = !autoReplace
                            ) {
                                DuoPrefs.setPickerAutoReplace(activity, false)
                                onRefreshRequested()
                            }
                        )
                    )
                }
            )
            prefsCard.addView(OneUiViews.createHairlineDivider(activity))
            prefsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Auto-Replace (Default)",
                    subtitle = "Closes the Picker after launching an app into its own panel slot",
                    titleAccent = autoReplace,
                    trailingView = if (autoReplace) {
                        OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                    } else {
                        null
                    }
                ) {
                    DuoPrefs.setPickerAutoReplace(activity, true)
                    onRefreshRequested()
                }
            )
            prefsCard.addView(OneUiViews.createHairlineDivider(activity))
            prefsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Keep Picker Alive",
                    subtitle = "Leaves the Picker activity in the back stack behind the launched app",
                    titleAccent = !autoReplace,
                    trailingView = if (!autoReplace) {
                        OneUiViews.CheckmarkView(activity, color = OneUiViews.COLOR_ONEUI_BLUE)
                    } else {
                        null
                    }
                ) {
                    DuoPrefs.setPickerAutoReplace(activity, false)
                    onRefreshRequested()
                }
            )
            wrapper.addView(prefsCard)

            // Card 4: Per-App Strategy Memory
            val rememberedMap = DuoPrefs.getAllRememberedStrategies(activity)
            val memCard = OneUiViews.createGroupedCard(activity)
            memCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Per-app strategy memory (${rememberedMap.size})",
                    subtitle = "DuoPanel remembers which split strategy (A, B, or C) last succeeded for each package and tries it first next time."
                )
            )

            if (rememberedMap.isEmpty()) {
                memCard.addView(OneUiViews.createHairlineDivider(activity))
                memCard.addView(
                    OneUiViews.createSettingsRow(
                        context = activity,
                        title = "No per-app strategies cached yet",
                        subtitle = "Launch apps via Solo shortcuts or Launch Lab to populate"
                    )
                )
            } else {
                for ((pkg, strategy) in rememberedMap) {
                    val label = AppRepository.getAppLabel(activity, pkg)
                    memCard.addView(OneUiViews.createHairlineDivider(activity))
                    memCard.addView(
                        OneUiViews.createSettingsRow(
                            context = activity,
                            title = label,
                            subtitle = "$pkg • ${strategy.shortName}"
                        )
                    )
                }
            }

            memCard.addView(OneUiViews.createHairlineDivider(activity))
            memCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Reset Per-App Strategy Memory",
                    subtitle = "Clear cached winning strategies for all packages",
                    titleAccent = true
                ) {
                    val cleared = DuoPrefs.clearAllStrategyMemory(activity)
                    Toast.makeText(
                        activity,
                        "Cleared strategy memory for $cleared app(s).",
                        Toast.LENGTH_SHORT
                    ).show()
                    onRefreshRequested()
                }
            )
            wrapper.addView(memCard)

            return wrapper
        }
    }
}
