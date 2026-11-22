package com.jonathancruzate.duopanel

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * HideAppsActivity — PATCH 0.1.7 Part B, mirroring One UI's "Hide apps" screen:
 * a "Hidden apps" section on top showing the currently hidden apps, then the full
 * app list with checkboxes, and a Done action that persists the selection.
 *
 * Hidden apps disappear from DuoPanel's Picker everywhere (All Apps grid, Favorites,
 * search results, and the StageActivity picker pane). They still appear and launch
 * normally from One UI Home; this only affects DuoPanel's Picker, and the screen
 * says so.
 */
class HideAppsActivity : Activity() {

    private var rootContainer: LinearLayout? = null
    private val workingHidden: MutableSet<String> = mutableSetOf()
    private var allApps: List<AppRepository.InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        workingHidden.addAll(DuoPrefs.getHiddenPackages(this))
        allApps = AppRepository.getLaunchableApps(this)

        val margin = OneUiViews.screenMarginPx(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            setPadding(margin, OneUiViews.dp(this@HideAppsActivity, 18), margin, margin)
        }
        rootContainer = container
        scroll.addView(container)
        setContentView(scroll)
        rebuildUI()
    }

    private fun rebuildUI() {
        val container = rootContainer ?: return
        container.removeAllViews()

        // Top bar: < Hide apps                                    Done
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = OneUiViews.dp(this@HideAppsActivity, 14)
            }
        }
        topBar.addView(OneUiViews.BackChevronView(this) { finish() })
        topBar.addView(
            TextView(this).apply {
                text = "Hide apps"
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(OneUiViews.dp(this@HideAppsActivity, 8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        topBar.addView(
            TextView(this).apply {
                text = "Done"
                setTextColor(OneUiViews.COLOR_ONEUI_BLUE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val hPad = OneUiViews.dp(this@HideAppsActivity, 10)
                val vPad = OneUiViews.dp(this@HideAppsActivity, 6)
                setPadding(hPad, vPad, hPad, vPad)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    DuoPrefs.setHiddenPackages(this@HideAppsActivity, workingHidden)
                    finish()
                }
            }
        )
        container.addView(topBar)

        // Scope note card
        val noteCard = OneUiViews.createGroupedCard(this)
        noteCard.addView(
            OneUiViews.createSettingsRow(
                context = this,
                title = "Hidden apps leave the DuoPanel picker only",
                subtitle = "Hidden apps disappear from DuoPanel's app picker (All apps, Favorites, and search). They still appear and launch normally on your home screen."
            )
        )
        container.addView(noteCard)

        // Hidden apps section (top, mirroring One UI)
        container.addView(
            OneUiViews.createSectionHeader(
                context = this,
                title = "Hidden apps",
                subtitle = if (workingHidden.isEmpty()) {
                    "No apps hidden"
                } else {
                    "${workingHidden.size} app(s) hidden from the picker"
                }
            )
        )
        val hiddenCard = OneUiViews.createGroupedCard(this)
        val hiddenApps = allApps.filter { workingHidden.contains(it.packageName) }
        if (hiddenApps.isEmpty()) {
            hiddenCard.addView(
                OneUiViews.createSettingsRow(
                    context = this,
                    title = "No apps hidden",
                    subtitle = "Check apps in the list below to hide them from the picker"
                )
            )
        } else {
            hiddenApps.forEachIndexed { index, app ->
                if (index > 0) {
                    hiddenCard.addView(OneUiViews.createHairlineDivider(this))
                }
                hiddenCard.addView(buildAppRow(app))
            }
        }
        container.addView(hiddenCard)

        // Full app list with checkboxes
        container.addView(
            OneUiViews.createSectionHeader(
                context = this,
                title = "All apps",
                subtitle = "Checked apps are hidden from the picker"
            )
        )
        val allCard = OneUiViews.createGroupedCard(this)
        allApps.forEachIndexed { index, app ->
            if (index > 0) {
                allCard.addView(OneUiViews.createHairlineDivider(this))
            }
            allCard.addView(buildAppRow(app))
        }
        container.addView(allCard)
    }

    private fun buildAppRow(app: AppRepository.InstalledApp): LinearLayout {
        val checked = workingHidden.contains(app.packageName)
        val iconView = ImageView(this).apply {
            val sz = OneUiViews.dp(this@HideAppsActivity, 40)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (app.icon != null) {
                setImageDrawable(app.icon)
            } else {
                setImageResource(R.drawable.ic_launcher_foreground)
            }
        }
        return OneUiViews.createSettingsRow(
            context = this,
            title = app.label,
            subtitle = app.packageName,
            leadingView = iconView,
            trailingView = buildCheckbox(checked)
        ) {
            if (checked) {
                workingHidden.remove(app.packageName)
            } else {
                workingHidden.add(app.packageName)
            }
            rebuildUI()
        }
    }

    /** One UI style checkbox: Samsung-blue rounded square with a white check when checked. */
    private fun buildCheckbox(checked: Boolean): FrameLayout {
        val size = OneUiViews.dp(this, 26)
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = if (checked) {
                OneUiViews.roundedBg(
                    context = this@HideAppsActivity,
                    fillColor = OneUiViews.COLOR_ONEUI_BLUE,
                    radiusDp = 8
                )
            } else {
                OneUiViews.roundedBg(
                    context = this@HideAppsActivity,
                    fillColor = OneUiViews.COLOR_CARD_BG,
                    radiusDp = 8,
                    strokeColor = OneUiViews.COLOR_FLOATING_STROKE,
                    strokeWidthDp = 1
                )
            }
            if (checked) {
                val check = OneUiViews.CheckmarkView(
                    this@HideAppsActivity,
                    color = OneUiViews.COLOR_SWITCH_THUMB,
                    sizeDp = 16
                )
                val lp = FrameLayout.LayoutParams(
                    OneUiViews.dp(this@HideAppsActivity, 16),
                    OneUiViews.dp(this@HideAppsActivity, 16),
                    Gravity.CENTER
                )
                check.layoutParams = lp
                addView(check)
            }
        }
    }
}
