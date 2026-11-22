package com.jonathancruzate.duopanel

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * AboutActivity — Displays Honest Limits, Corner-Pin Placement Guide (including portrait top/bottom
 * mapping), Galaxy Z Fold8 Arrival-Day Checklist, and Galaxy S24+ Proxy Test Guide per SPEC.md,
 * SPEC-ADDENDUM.md, and PATCH-0.1.2.md.
 */
class AboutActivity : Activity() {

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
            setPadding(margin, OneUiViews.dp(this@AboutActivity, 18), margin, margin)
        }
        container.addView(buildAboutSection(this, showBackHeader = true))
        scroll.addView(container)
        setContentView(scroll)
    }

    companion object {
        fun buildAboutSection(activity: Activity, showBackHeader: Boolean): View {
            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            if (showBackHeader) {
                wrapper.addView(OneUiViews.createSettingsTopBar(activity, "About & Honest Limits"))
            }

            // Card 1: Product Summary & Corner-Pin Workflow
            val introCard = OneUiViews.createGroupedCard(activity)
            introCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "DuoPanel v0.1.3 — Surface Duo One-Panel Ergonomics for Galaxy Z Fold8",
                    subtitle = "Package: com.jonathancruzate.duopanel • Works with Samsung One UI Home"
                )
            )
            introCard.addView(OneUiViews.createHairlineDivider(activity))
            introCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Bottom-corner setup (primary flow)",
                    subtitle = "Pin the 'Left Panel' shortcut to the bottom-left corner of One UI Home and the 'Right Panel' shortcut to the bottom-right corner."
                )
            )
            introCard.addView(OneUiViews.createHairlineDivider(activity))
            introCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "The app list lives on the panel the app opens on",
                    subtitle = "Left Panel opens the One UI App Picker on the LEFT panel with CompanionPanelActivity on the RIGHT panel (and vice versa). Picking an app replaces the Picker on its own panel while the Companion stays on the other panel with a live clock/date (togglable via 'Clock'), the paired app's name, and a 'Pick app' row."
                )
            )
            introCard.addView(OneUiViews.createHairlineDivider(activity))
            introCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Portrait / top-bottom orientation",
                    subtitle = "When the device is in portrait top/bottom split (e.g., Galaxy S24+ or rotated Fold), 'Left Panel' maps to Top/Primary and 'Right Panel' maps to Bottom/Secondary."
                )
            )
            wrapper.addView(introCard)

            // Card 2: Honest Scope & Platform Limits
            val limitsCard = OneUiViews.createGroupedCard(activity)
            limitsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Honest scope & platform limits",
                    subtitle = "What DuoPanel does — and what Android / One UI prevents without root or replacing your launcher"
                )
            )
            limitsCard.addView(OneUiViews.createHairlineDivider(activity))
            limitsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Curated panel & Solo shortcuts product",
                    subtitle = "DuoPanel does not claim to intercept every app launch across Android. Launching apps from DuoPanel's Left/Right Panel shortcuts, Solo shortcuts, or Pair shortcuts triggers the two-panel split flow."
                )
            )
            limitsCard.addView(OneUiViews.createHairlineDivider(activity))
            limitsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Standard One UI icons still open full-screen",
                    subtitle = "Real One UI Home icons in the app drawer, Samsung Taskbar, Recents, notifications, and intents from other apps still open full-screen. That is an Android & Samsung platform boundary without root or replacing One UI Home."
                )
            )
            limitsCard.addView(OneUiViews.createHairlineDivider(activity))
            limitsCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Zero intrusive permissions or background daemons",
                    subtitle = "Not a launcher (no HOME role; One UI Home and taskbar stay intact). No root, no Shizuku, no ADB required. No AccessibilityService. No INTERNET permission (zero network calls). Zero background services."
                )
            )
            wrapper.addView(limitsCard)

            // Card 3: Galaxy Z Fold8 Arrival-Day Checklist & S24+ Proxy Steps
            val checklistCard = OneUiViews.createGroupedCard(activity)
            checklistCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Galaxy Z Fold8 arrival-day checklist & S24+ test guide",
                    subtitle = "Use the Launch Lab tab to test and record which flow/strategy works per app"
                )
            )
            checklistCard.addView(OneUiViews.createHairlineDivider(activity))
            checklistCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Galaxy S24+ proxy test steps",
                    subtitle = "1. Open DuoPanel → Settings → set Split Mode to 'Always Try Split' (since S24+ portrait width is < 600dp).\n2. Pin 'Left Panel' and 'Right Panel' shortcuts to One UI Home.\n3. Test Right Panel flow, Left Panel re-anchor flow, and Launch Lab Strategies A / B / C."
                )
            )
            checklistCard.addView(OneUiViews.createHairlineDivider(activity))
            checklistCard.addView(
                OneUiViews.createSettingsRow(
                    context = activity,
                    title = "Galaxy Z Fold8 arrival-day gate (Dec 2–4)",
                    subtitle = "1. Set Split Mode to 'Auto (Wide >= 600dp)' so cover screen opens apps full-screen and unfolded inner display engages split-screen.\n2. Pin 'Left Panel' to bottom-left and 'Right Panel' to bottom-right of unfolded One UI Home.\n3. In Launch Lab, test Outlook, Chrome, YouTube, OneNote, and a non-resizable app.\n4. Use 'Swap' in the Picker or Companion if panels ever need reversing."
                )
            )
            wrapper.addView(checklistCard)

            return wrapper
        }
    }
}
