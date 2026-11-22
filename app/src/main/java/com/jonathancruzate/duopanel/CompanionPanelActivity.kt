package com.jonathancruzate.duopanel

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CompanionPanelActivity — Minimal Samsung One UI-styled companion surface for the non-target panel
 * (PATCH 0.1.1C, PATCH 0.1.2, PATCH 0.1.2B, & PATCH 0.1.5).
 *
 * When a user taps the Left or Right corner shortcut, PickerActivity opens confined to THAT panel
 * while CompanionPanelActivity occupies the opposite panel. When a Solo shortcut launches with
 * `pickerOnFreePanel` enabled, CompanionPanelActivity waits on the free panel beside the app.
 *
 * Surface contents (one grouped One UI card on the solid deep navy-black One UI background
 * #0B0E1A with its own companion taskAffinity per PATCH 0.1.5):
 * - DuoPanel header row (dual-panel graphic highlighting this companion's panel, panel title, and
 *   quiet "DuoPanel" settings link)
 * - Live clock and date block (togglable via the "Clock" button)
 * - Other-panel app row (icon + name like a One UI settings row)
 * - Actions inside the card (no outlined blue pills):
 *   - "Pick app": summons PickerActivity on THIS panel, replacing the companion, with this panel
 *     as its side (EXTRA_SIDE) and default mode "Replace this panel", then finishes.
 *   - "Swap", "Full", "Clock", "Close": behave like the same buttons in PickerActivity.
 */
class CompanionPanelActivity : Activity() {

    private var requestedSide: DuoPrefs.PanelSide = DuoPrefs.PanelSide.RIGHT
    private var side: DuoPrefs.PanelSide = DuoPrefs.PanelSide.RIGHT
    private var pairedPackage: String? = null
    private var panelClockExpanded: Boolean = true

    private lateinit var panelGraphicView: OneUiViews.PanelGraphicView
    private lateinit var headerTitleText: TextView
    private lateinit var headerSubtitleText: TextView
    private lateinit var panelClockBlock: LinearLayout
    private lateinit var clockDivider: View
    private lateinit var otherPanelBadgeContainer: LinearLayout
    private lateinit var clockActionBtn: TextView

    private val activeAppListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                syncPairedPackageFromEngine()
                refreshAllUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        SoloLauncher.registerCompanionInstance(this)
        readIntentState(intent)
        buildOneUiContentView()
        SoloLauncher.registerActivePanelListener(activeAppListener)
        refreshAllUi()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        SoloLauncher.registerCompanionInstance(this)
        if (intent != null) {
            setIntent(intent)
            readIntentState(intent)
            refreshAllUi()
        }
    }

    override fun onResume() {
        super.onResume()
        SoloLauncher.registerCompanionInstance(this)
        syncPairedPackageFromEngine()
        refreshAllUi()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        syncPairedPackageFromEngine()
        refreshAllUi()
    }

    override fun onDestroy() {
        SoloLauncher.unregisterCompanionInstance(this)
        SoloLauncher.unregisterActivePanelListener(activeAppListener)
        super.onDestroy()
    }

    private fun readIntentState(incoming: Intent?) {
        val sideExtra = incoming?.getStringExtra(ShortcutHelper.EXTRA_SIDE)
        val rawSide = DuoPrefs.PanelSide.fromString(sideExtra, DuoPrefs.PanelSide.RIGHT)
        requestedSide = rawSide
        side = DuoPrefs.effectiveSide(this, rawSide)

        val explicitPaired = incoming?.getStringExtra(ShortcutHelper.EXTRA_PAIRED_PKG)
        if (!explicitPaired.isNullOrBlank()) {
            pairedPackage = explicitPaired
            SoloLauncher.recordActivePanelApp(this, requestedSide.opposite(), explicitPaired)
        } else {
            syncPairedPackageFromEngine()
        }
    }

    private fun syncPairedPackageFromEngine() {
        val fromEngine = SoloLauncher.getActivePanelApp(this, requestedSide.opposite())
        if (!fromEngine.isNullOrBlank()) {
            pairedPackage = fromEngine
        }
    }

    private fun buildOneUiContentView() {
        val outerMargin = OneUiViews.screenMarginPx(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(OneUiViews.COLOR_BG_NAVY)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(outerMargin, OneUiViews.dp(this@CompanionPanelActivity, 18), outerMargin, outerMargin)
        }
        // PATCH 0.1.9: mirror of the 0.1.8 bottom inset listener so the companion header card
        // clears the status bar under Android 15/16 edge-to-edge enforcement.
        OneUiViews.applyTopStatusBarInsetPadding(root)

        // Single grouped card (#1A1E2E, 26dp corners) on the deep navy background
        val cardBlock = OneUiViews.createGroupedCard(this, bottomMarginDp = 14, verticalPadDp = 10)

        // 1. Header Row inside card: Side graphic + Panel title/subtitle + quiet DuoPanel link
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@CompanionPanelActivity, 20)
            val vPad = OneUiViews.dp(this@CompanionPanelActivity, 12)
            setPadding(hPad, vPad, hPad, vPad)
        }

        panelGraphicView = OneUiViews.PanelGraphicView(
            context = this,
            highlightLeft = requestedSide == DuoPrefs.PanelSide.LEFT,
            highlightRight = requestedSide == DuoPrefs.PanelSide.RIGHT,
            sizeDp = 38
        )
        topRow.addView(panelGraphicView)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = OneUiViews.dp(this@CompanionPanelActivity, 14)
            }
        }

        headerTitleText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        }
        headerSubtitleText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, OneUiViews.dp(this@CompanionPanelActivity, 2), 0, 0)
        }
        titleCol.addView(headerTitleText)
        titleCol.addView(headerSubtitleText)
        topRow.addView(titleCol)

        val configLink = OneUiViews.createTextAction(
            context = this,
            label = "DuoPanel",
            activeOrLink = true
        ) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        topRow.addView(configLink)
        cardBlock.addView(topRow)
        cardBlock.addView(OneUiViews.createHairlineDivider(this))

        // 2. Live Clock & Date Block (togglable via "Clock" button)
        panelClockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = OneUiViews.dp(this@CompanionPanelActivity, 20)
            val vPad = OneUiViews.dp(this@CompanionPanelActivity, 16)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val clock = TextClock(this@CompanionPanelActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val dateText = TextView(this@CompanionPanelActivity).apply {
                text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, OneUiViews.dp(this@CompanionPanelActivity, 2), 0, 0)
            }
            addView(clock)
            addView(dateText)
        }
        cardBlock.addView(panelClockBlock)
        clockDivider = OneUiViews.createHairlineDivider(this)
        cardBlock.addView(clockDivider)

        // 3. Other-panel app status row (icon + name like a One UI settings row)
        otherPanelBadgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@CompanionPanelActivity, 20)
            val vPad = OneUiViews.dp(this@CompanionPanelActivity, 14)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cardBlock.addView(otherPanelBadgeContainer)
        cardBlock.addView(OneUiViews.createHairlineDivider(this))

        // 4. Primary Action Row inside card: Pick app (settings-style row)
        val pickAppRow = OneUiViews.createSettingsRow(
            context = this,
            title = "Pick app",
            subtitle = "Open the app drawer on this panel",
            titleAccent = true
        ) {
            summonPickerOnThisPanel()
        }
        cardBlock.addView(pickAppRow)
        cardBlock.addView(OneUiViews.createHairlineDivider(this))

        // 5. Quiet Action Row inside card: Swap | Full | Clock | Close (no outlined blue pills)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@CompanionPanelActivity, 12)
            val vPad = OneUiViews.dp(this@CompanionPanelActivity, 6)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun addQuietAction(title: String, action: () -> Unit): TextView {
            val btn = TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                val vPad = OneUiViews.dp(this@CompanionPanelActivity, 10)
                setPadding(0, vPad, 0, vPad)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            actionsRow.addView(btn)
            return btn
        }

        addQuietAction("Swap") {
            SoloLauncher.swapFromCompanion(
                companionActivity = this,
                companionRequestedSide = requestedSide,
                pairedPkg = pairedPackage
            )
        }
        addQuietAction("Full") {
            SoloLauncher.expandPairedAppFullscreen(this, pairedPackage)
        }
        clockActionBtn = addQuietAction("Clock") {
            panelClockExpanded = !panelClockExpanded
            refreshAllUi()
        }
        addQuietAction("Close") {
            finish()
        }
        cardBlock.addView(actionsRow)

        root.addView(cardBlock)
        scroll.addView(root)
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun refreshAllUi() {
        panelGraphicView.setSide(requestedSide)
        headerTitleText.text = requestedSide.title
        headerSubtitleText.text = "Companion panel"

        panelClockBlock.visibility = if (panelClockExpanded) View.VISIBLE else View.GONE
        clockDivider.visibility = if (panelClockExpanded) View.VISIBLE else View.GONE
        clockActionBtn.setTextColor(
            if (panelClockExpanded) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_PRIMARY
        )

        otherPanelBadgeContainer.removeAllViews()
        val paired = pairedPackage
        if (!paired.isNullOrBlank()) {
            val iconDrawable = AppRepository.getAppIcon(this, paired)
            if (iconDrawable != null) {
                val iv = ImageView(this).apply {
                    setImageDrawable(iconDrawable)
                    val sz = OneUiViews.dp(this@CompanionPanelActivity, 32)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = OneUiViews.dp(this@CompanionPanelActivity, 14)
                    }
                }
                otherPanelBadgeContainer.addView(iv)
            }
            val label = AppRepository.getAppLabel(this, paired)
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                }
            )
            textCol.addView(
                TextView(this).apply {
                    text = "${requestedSide.oppositeTitle} • Active app"
                    setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, OneUiViews.dp(this@CompanionPanelActivity, 2), 0, 0)
                }
            )
            otherPanelBadgeContainer.addView(textCol)
        } else {
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(
                TextView(this).apply {
                    text = "App list active"
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                }
            )
            textCol.addView(
                TextView(this).apply {
                    text = requestedSide.oppositeTitle
                    setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, OneUiViews.dp(this@CompanionPanelActivity, 2), 0, 0)
                }
            )
            otherPanelBadgeContainer.addView(textCol)
        }
    }

    /**
     * PATCH 0.1.1C:
     * Starts PickerActivity with no adjacent flag (`replaceSlotFlags()`) and this companion's own
     * panel side as EXTRA_SIDE plus default mode "Replace this panel", then finishes so the Picker
     * takes the companion's split slot.
     */
    private fun summonPickerOnThisPanel() {
        val flags = SoloLauncher.replaceSlotFlags()
        val pickerIntent = ShortcutHelper.buildPanelPickerIntent(
            context = this,
            side = requestedSide,
            pairedPackage = pairedPackage,
            reanchored = false,
            panelEntry = false,
            defaultReplaceMode = true
        ).apply {
            addFlags(flags)
        }
        try {
            startActivity(pickerIntent)
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "COMPANION_PICK_APP",
                side = requestedSide.name,
                targetDesc = "PickerActivity(replaceCompanionSlot)",
                flagsHex = SoloLauncher.describeFlags(flags),
                optionsInfo = "inMultiWindow=$isInMultiWindowMode,paired=${pairedPackage ?: "none"}",
                result = "OK (Picker replaces Companion on ${requestedSide.name})"
            )
            finish()
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "COMPANION_PICK_APP",
                side = requestedSide.name,
                targetDesc = "PickerActivity",
                flagsHex = SoloLauncher.describeFlags(flags),
                optionsInfo = "inMultiWindow=$isInMultiWindowMode",
                result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }
}
