package com.jonathancruzate.duopanel

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PATCH 0.2.0: shared rendering for the picker's arrangeable sections, used by
 * BOTH the standalone PickerActivity and the StageActivity picker pane so the
 * sections look and behave identically in each: a section header in the same
 * style as the Favorites / All apps headers, then either one horizontal row of
 * compact app cells (Recent apps / Frequently used) or, when Usage Access is
 * not granted, an honest grant-prompt row. No usage data is ever faked: no
 * grant or no data means the prompt or nothing, never stale rows.
 */
object PickerSections {

    /** Max apps shown in a one-line usage row. */
    const val ROW_LIMIT: Int = 12

    fun sectionHeader(context: Context, title: String, trailing: String? = null): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(context, 6)
            setPadding(hPad, OneUiViews.dp(context, 2), hPad, OneUiViews.dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(TextView(context).apply {
            text = title
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!trailing.isNullOrBlank()) {
            row.addView(TextView(context).apply {
                text = trailing
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            })
        }
        return row
    }

    /**
     * Builds one usage-backed section (header + one horizontal row), or the
     * grant prompt when Usage Access is off. Returns null when access is
     * granted but the section has no apps to show (the section hides silently
     * rather than rendering an empty row). Sections carry no divider of their
     * own (DESIGN-PASS-0.2.0-SECTIONS): open spacing separates the top
     * sections, and exactly one hairline sits above All Apps.
     *
     * [viewportWidthDp] is the picker surface's width (full width for the
     * standalone Picker, half width for a Stage pane) and [gridColumns] its
     * grid column count; together they drive the adaptive cell width that
     * always leaves ~40% of the next cell peeking as the scroll affordance.
     */
    fun buildUsageSection(
        context: Context,
        section: DuoPrefs.PickerSection,
        appsByPackage: Map<String, AppRepository.InstalledApp>,
        hiddenPackages: Set<String>,
        viewportWidthDp: Int,
        gridColumns: Int,
        onPick: (AppRepository.InstalledApp) -> Unit,
        onLongPress: (AppRepository.InstalledApp) -> Unit
    ): View? {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = OneUiViews.dp(context, 10) }
        }
        block.addView(sectionHeader(context, section.title))

        if (!UsageRepository.hasUsageAccess(context)) {
            block.addView(buildGrantPrompt(context))
            return block
        }

        val packages = when (section) {
            DuoPrefs.PickerSection.RECENT -> UsageRepository.recentPackages(context, ROW_LIMIT * 2)
            DuoPrefs.PickerSection.FREQUENT -> UsageRepository.frequentPackages(context, ROW_LIMIT * 2)
            DuoPrefs.PickerSection.FAVORITES -> emptyList()
        }
        val apps = packages.asSequence()
            .filter { it != context.packageName }
            .filter { !hiddenPackages.contains(it) }
            .mapNotNull { appsByPackage[it] }
            .take(ROW_LIMIT)
            .toList()
        if (apps.isEmpty()) return null

        val cellWidthDp = adaptiveCellWidthDp(context, viewportWidthDp, gridColumns)
        val rowContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        apps.forEachIndexed { index, app ->
            rowContent.addView(
                buildCompactCell(context, app, cellWidthDp, index < apps.size - 1, onPick, onLongPress)
            )
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(rowContent)
        }
        block.addView(scroller)
        return block
    }

    /**
     * DESIGN-PASS-0.2.0-SECTIONS adaptive cell width: start from the grid
     * column count, size cells so (visibleFull + 0.40) cells span the row
     * (the 0.40 is the peeking next cell), then walk the visible count until
     * the width lands inside the per-AppSize bounds. The row lives inside
     * the drawer's padded rail, so the available width is the viewport minus
     * both 14dp rails. (Deviation from the spec's full-bleed restructure:
     * rows stay on the same rail as every other drawer element; the peek
     * affordance is preserved by this formula.)
     */
    fun adaptiveCellWidthDp(context: Context, viewportWidthDp: Int, gridColumns: Int): Int {
        val (minW, maxW) = when (DuoPrefs.getAppSize(context)) {
            DuoPrefs.AppSize.SMALL -> 68 to 96
            DuoPrefs.AppSize.MEDIUM -> 76 to 104
            DuoPrefs.AppSize.LARGE -> 84 to 112
        }
        val avail = (viewportWidthDp - 28).coerceAtLeast(minW)
        var visibleFull = gridColumns.coerceAtLeast(2)
        var width = (avail / (visibleFull + 0.40)).toInt() - 4
        var guard = 0
        while (width < minW && visibleFull > 2 && guard < 8) {
            visibleFull--
            width = (avail / (visibleFull + 0.40)).toInt() - 4
            guard++
        }
        guard = 0
        while (width > maxW && guard < 8) {
            visibleFull++
            width = (avail / (visibleFull + 0.40)).toInt() - 4
            guard++
        }
        return width.coerceIn(minW, maxW)
    }

    /**
     * The honest no-grant state (DESIGN-PASS-0.2.0-SECTIONS): one quiet
     * tappable row on a translucent card fill, "Usage access needed" in
     * secondary text and a bold accent "Allow" action. No warning colors, no
     * fake data. Tapping opens the system Usage Access settings.
     */
    fun buildGrantPrompt(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OneUiViews.roundedBg(context, OneUiViews.COLOR_GRANT_FILL, 16)
            val hPad = OneUiViews.dp(context, 14)
            setPadding(hPad, OneUiViews.dp(context, 10), hPad, OneUiViews.dp(context, 10))
            minimumHeight = OneUiViews.dp(context, 44)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = OneUiViews.dp(context, 6)
                marginEnd = OneUiViews.dp(context, 6)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openUsageAccessSettings(context) }
        }
        row.addView(TextView(context).apply {
            text = "Usage access needed"
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = OneUiViews.dp(context, 12)
            }
        })
        row.addView(TextView(context).apply {
            text = "Allow"
            setTextColor(OneUiViews.COLOR_ONEUI_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        return row
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }
    }

    /** One compact cell in a horizontal section row: badged icon + label. */
    fun buildCompactCell(
        context: Context,
        app: AppRepository.InstalledApp,
        cellWidthDp: Int,
        trailingGap: Boolean,
        onPick: (AppRepository.InstalledApp) -> Unit,
        onLongPress: (AppRepository.InstalledApp) -> Unit
    ): View {
        val iconSize = OneUiViews.dp(context, DuoPrefs.getAppSize(context).iconDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val vPad = OneUiViews.dp(context, 8)
            setPadding(OneUiViews.dp(context, 6), vPad, OneUiViews.dp(context, 6), vPad)
            layoutParams = LinearLayout.LayoutParams(
                OneUiViews.dp(context, cellWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (trailingGap) marginEnd = OneUiViews.dp(context, 4)
            }
            addView(buildBadgeIcon(context, app, iconSize))
            if (DuoPrefs.isShowLabels(context)) {
                addView(TextView(context).apply {
                    text = app.label
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, DuoPrefs.getAppSize(context).labelSp)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, OneUiViews.dp(context, 6), 0, 0)
                })
            }
            setOnClickListener { onPick(app) }
            setOnLongClickListener {
                onLongPress(app)
                true
            }
        }
    }

    /**
     * Icon with the 0.1.7 notification badge overlay, shared by the section
     * rows (the grid cells keep their per-activity copies). Dot = accent dot at
     * the icon's top-right; Number = accent pill with the count, capped at 99+,
     * white text so it stays legible on any accent in either palette.
     */
    fun buildBadgeIcon(context: Context, app: AppRepository.InstalledApp, iconSize: Int): View {
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(iconSize, iconSize)
        }
        frame.addView(ImageView(context).apply {
            setImageDrawable(app.icon)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
        })

        if (!DuoPrefs.isBadgesEnabled(context)) return frame
        if (!DuoNotificationListener.connected) return frame
        val count = DuoNotificationListener.countFor(app.packageName)
        if (count <= 0) return frame

        when (DuoPrefs.getBadgeStyle(context)) {
            DuoPrefs.BadgeStyle.DOT -> {
                val dotSize = OneUiViews.dp(context, 10)
                frame.addView(View(context).apply {
                    background = OneUiViews.roundedBg(context, OneUiViews.COLOR_ONEUI_BLUE, 999)
                    layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                        gravity = Gravity.TOP or Gravity.END
                    }
                })
            }
            DuoPrefs.BadgeStyle.NUMBER -> {
                frame.addView(TextView(context).apply {
                    text = if (count > 99) "99+" else count.toString()
                    // PATCH 0.2.0 on-accent rule: white or dark ink, whichever
                    // contrasts the active accent (see DuoTheme.onAccent).
                    setTextColor(DuoTheme.onAccent(OneUiViews.COLOR_ONEUI_BLUE))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = OneUiViews.roundedBg(context, OneUiViews.COLOR_ONEUI_BLUE, 999)
                    val hPad = OneUiViews.dp(context, 4)
                    setPadding(hPad, 0, hPad, 0)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        OneUiViews.dp(context, 15)
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                    }
                })
            }
        }
        return frame
    }
}
