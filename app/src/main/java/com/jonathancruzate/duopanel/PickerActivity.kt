package com.jonathancruzate.duopanel

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PickerActivity — The Samsung One UI styled app drawer & Surface Duo second panel.
 *
 * Implements SPEC-ADDENDUM.md, SPEC.md, PATCH-0.1.2.md, and PATCH-0.1.2B.md:
 * 1. Reads `extra_side` ("LEFT" or "RIGHT") from the pinned Left Panel / Right Panel shortcut
 *    (or defaults to the user's preferred side when opened standalone from the app drawer).
 * 2. Styled after the Samsung One UI app drawer (`design-reference/oneui-app-drawer.png`):
 *    - Translucent frosted cross-window blur (API 31+) with a semi-transparent dark navy scrim
 *      (#B80B0E1A, ~72% opacity; graceful fallback to scrim or solid #0B0E1A where blur is unavailable)
 *    - Simple top bar with panel indicator, quiet Swap / Full / Clock / Close actions, and
 *      three-dot (`⋮`) overflow menu opening a floating rounded One UI menu card
 *    - Quiet segmented mode control ("Open on other side" / "Replace this panel") + 1-line hint
 *    - Favorites top group separated from the All Apps 4-column grid of full-color app icons
 *      at natural size with white 12sp labels underneath
 *    - Dark search pill at the BOTTOM of the screen with magnifier icon and "Search" hint
 * 3. Two pick modes:
 *    - First pick (`PickMode.OPEN_OTHER_SIDE`):
 *      - If `side == RIGHT`: launches chosen app adjacent on the RIGHT; Picker STAYS ALIVE on
 *        the LEFT and automatically switches into `PickMode.REPLACE_THIS_PANEL`.
 *      - If `side == LEFT`: launches chosen app normally on the LEFT, then invokes
 *        `SoloLauncher.reanchorPicker` so a fresh Picker opens adjacent on the RIGHT.
 *    - In-split / second pick (`PickMode.REPLACE_THIS_PANEL`):
 *      - Launches the chosen app WITHOUT adjacent flags (`NEW_TASK | MULTIPLE_TASK`) so it lands
 *        in the Picker's own split slot, and then Picker finishes.
 * 4. Top action row: Swap, Full, Clock, Close (+ three-dot overflow).
 */
class PickerActivity : Activity() {

    enum class PickMode {
        OPEN_OTHER_SIDE,
        REPLACE_THIS_PANEL
    }

    companion object {
        private const val STATE_PAIR_ATTEMPTED = "state_pair_attempted"
        private const val STATE_PHASE2_LAUNCHED = "state_phase2_launched"
        private const val PHASE2_INITIAL_DELAY_MS = 160L
        private const val PHASE2_RETRY_INTERVAL_MS = 65L
        private const val PHASE2_MAX_RETRIES = 12
    }

    private var requestedSide: DuoPrefs.PanelSide = DuoPrefs.PanelSide.LEFT
    private var side: DuoPrefs.PanelSide = DuoPrefs.PanelSide.LEFT
    private var pairedPackage: String? = null
    private var reanchored: Boolean = false
    private var defaultReplaceRequested: Boolean = false
    private var isCornerShortcutFlow: Boolean = false
    private var pendingPanelEntry: DuoPrefs.PanelSide? = null
    private var panelPairFormationAttempted: Boolean = false
    private var pendingPhase2Companion: Boolean = false
    private var phase2CompanionLaunched: Boolean = false
    private var phase2RetryScheduled: Boolean = false
    private var calibrationSkippedLogged: Boolean = false
    private var hasSplitActive: Boolean = false
    private var pickMode: PickMode = PickMode.REPLACE_THIS_PANEL
    private var panelClockExpanded: Boolean = true
    private var searchQuery: String = ""

    private var rootContentView: View? = null
    private lateinit var panelGraphicView: OneUiViews.PanelGraphicView
    private lateinit var headerTitleText: TextView
    private lateinit var headerSubtitleText: TextView
    private lateinit var clockActionBtn: TextView
    private lateinit var pairedBadgeContainer: LinearLayout
    private lateinit var panelClockBlock: LinearLayout
    private lateinit var modeOtherSideBtn: TextView
    private lateinit var modeReplaceSelfBtn: TextView
    private lateinit var hintBannerText: TextView
    private lateinit var favoritesBlock: LinearLayout
    private lateinit var favoritesGrid: GridLayout
    // PATCH 0.2.0: container holding the arrangeable picker sections in the
    // user's configured order (Recent / Frequently used / Favorites).
    private lateinit var sectionsContainer: LinearLayout
    // PATCH 0.2.0: the single hairline above All Apps, shown only when at
    // least one top section is actually rendered.
    private lateinit var sectionsDivider: View
    // PATCH 0.2.0: the search magnifier (accent tint while search is active).
    private var searchMagnifierView: OneUiViews.MagnifierIconView? = null
    private var searchBoxHasFocus: Boolean = false
    private lateinit var allAppsGrid: GridLayout
    private lateinit var allAppsCountText: TextView
    private var searchBarWrapper: LinearLayout? = null
    // PATCH 0.1.7: custom-order drag state
    private var dragCellView: View? = null
    private var dragHighlightIndex: Int = -1

    // PATCH 0.1.7 Part B: live badge refresh while the Picker is visible
    private val badgeListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                populateAppGrids()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        SoloLauncher.registerPickerInstance(this)
        panelPairFormationAttempted =
            savedInstanceState?.getBoolean(STATE_PAIR_ATTEMPTED, false) == true
        phase2CompanionLaunched =
            savedInstanceState?.getBoolean(STATE_PHASE2_LAUNCHED, false) == true
        readIntentState(intent)
        buildOneUiContentView()
        refreshAllUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PAIR_ATTEMPTED, panelPairFormationAttempted)
        outState.putBoolean(STATE_PHASE2_LAUNCHED, phase2CompanionLaunched)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        SoloLauncher.registerPickerInstance(this)
        if (intent != null) {
            if (!intent.getStringExtra(ShortcutHelper.EXTRA_PANEL_ENTRY).isNullOrBlank()) {
                panelPairFormationAttempted = false
                phase2CompanionLaunched = false
                phase2RetryScheduled = false
            }
            if (intent.getBooleanExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION, false)) {
                phase2CompanionLaunched = false
                phase2RetryScheduled = false
            }
            setIntent(intent)
            readIntentState(intent)
            refreshAllUi()
        }
    }

    override fun onResume() {
        super.onResume()
        SoloLauncher.registerPickerInstance(this)
        DuoNotificationListener.registerBadgeListener(badgeListener)
        OneUiViews.applyTranslucentBlurWindow(this, rootContentView, attachListener = false)
        syncMultiWindowPickMode()
        refreshAllUi()
        maybeFormCornerPanelPair()
        maybeRunPhase2CompanionDock()
        maybeAutoCalibrateSplitSides()
    }

    override fun onPause() {
        DuoNotificationListener.unregisterBadgeListener(badgeListener)
        super.onPause()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        OneUiViews.applyTranslucentBlurWindow(this, rootContentView, attachListener = false)
        syncMultiWindowPickMode()
        refreshAllUi()
        if (isInMultiWindowMode) {
            maybeRunPhase2CompanionDock()
            maybeAutoCalibrateSplitSides()
        }
    }

    override fun onDestroy() {
        SoloLauncher.unregisterPickerInstance(this)
        super.onDestroy()
    }

    private fun readIntentState(incoming: Intent?) {
        val sideExtra = incoming?.getStringExtra(ShortcutHelper.EXTRA_SIDE)
        val rawSide = DuoPrefs.PanelSide.fromString(sideExtra, DuoPrefs.getPreferredSide(this))
        pairedPackage = incoming?.getStringExtra(ShortcutHelper.EXTRA_PAIRED_PKG)
        reanchored = incoming?.getBooleanExtra(ShortcutHelper.EXTRA_REANCHORED, false) == true
        defaultReplaceRequested =
            incoming?.getBooleanExtra(ShortcutHelper.EXTRA_DEFAULT_REPLACE, false) == true

        val phase2Extra =
            incoming?.getBooleanExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION, false) == true
        if (phase2Extra && !phase2CompanionLaunched) {
            pendingPhase2Companion = true
        }

        val entryExtra = incoming?.getStringExtra(ShortcutHelper.EXTRA_PANEL_ENTRY)
        val hasEntryExtra = !entryExtra.isNullOrBlank()
        if (hasEntryExtra && !panelPairFormationAttempted) {
            pendingPanelEntry = DuoPrefs.PanelSide.fromString(entryExtra, rawSide)
        }

        isCornerShortcutFlow =
            hasEntryExtra || reanchored || defaultReplaceRequested || phase2Extra
        hasSplitActive =
            isInMultiWindowMode || reanchored || defaultReplaceRequested || phase2Extra || !pairedPackage.isNullOrBlank()

        side = DuoPrefs.effectiveSide(this, rawSide)
        requestedSide = rawSide

        if (pairedPackage.isNullOrBlank()) {
            val existingOtherApp = SoloLauncher.getActivePanelApp(this, requestedSide.opposite())
            if (!existingOtherApp.isNullOrBlank() && !hasEntryExtra && !phase2Extra) {
                pairedPackage = existingOtherApp
            }
        }

        // PATCH 0.1.1C: "Replace this panel" is the default pick mode for corner-shortcut flows,
        // companion "Pick app" replacements, and active split panels.
        pickMode = PickMode.REPLACE_THIS_PANEL
    }

    /**
     * PATCH 0.1.3 (Part B): Two-phase corner-shortcut panel-pair formation.
     * - Phase 1: If PickerActivity is not yet confined in multi-window mode, uses AnchorActivity
     *   (`SoloLauncher.formCornerPanelPair`) as a Phase-1 confinement step to spawn a confined
     *   PickerActivity with `EXTRA_PHASE2_COMPANION = true`, and finishes the fullscreen caller.
     *   If PickerActivity is already confined in multi-window mode, records `PANEL_PAIR_FORMED`
     *   and proceeds straight to Phase 2.
     */
    private fun maybeFormCornerPanelPair() {
        if (panelPairFormationAttempted) return
        val entrySide = pendingPanelEntry ?: return
        panelPairFormationAttempted = true
        pendingPanelEntry = null
        intent?.removeExtra(ShortcutHelper.EXTRA_PANEL_ENTRY)

        if (isInMultiWindowMode && SoloLauncher.isActivityConfinedToSplitStage(this)) {
            SoloLauncher.clearActivePanelApps(this)
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "PANEL_PAIR_FORMED",
                side = side.name,
                targetDesc = if (side == DuoPrefs.PanelSide.LEFT) {
                    "PickerActivity(LEFT) + CompanionPanelActivity(RIGHT)"
                } else {
                    "CompanionPanelActivity(LEFT) + PickerActivity(RIGHT)"
                },
                flagsHex = SoloLauncher.describeFlags(SoloLauncher.adjacentLaunchFlags()),
                optionsInfo = "phase1:pickerAlreadyConfinedInMultiWindow(req=${entrySide.name},eff=${side.name})",
                result = "OK (Picker already confined in split; triggering Phase 2 Companion adjacent launch)"
            )
            hasSplitActive = true
            pickMode = PickMode.REPLACE_THIS_PANEL
            pendingPhase2Companion = true
            phase2CompanionLaunched = false
            phase2RetryScheduled = false
            refreshAllUi()
            maybeRunPhase2CompanionDock()
            return
        }

        val view = rootContentView ?: window?.decorView
        val task = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (isInMultiWindowMode && SoloLauncher.isActivityConfinedToSplitStage(this)) {
                pendingPhase2Companion = true
                phase2CompanionLaunched = false
                phase2RetryScheduled = false
                maybeRunPhase2CompanionDock()
                return@Runnable
            }
            SoloLauncher.clearActivePanelApps(this)
            val formed = SoloLauncher.formCornerPanelPair(
                pickerActivity = this,
                effectivePickerSide = side,
                requestedPickerSide = requestedSide
            )
            if (formed) {
                hasSplitActive = true
                pickMode = PickMode.REPLACE_THIS_PANEL
                refreshAllUi()
            }
        }
        if (view != null) {
            view.post(task)
        } else {
            task.run()
        }
    }

    /**
     * PATCH 0.1.4: Phase 2 of two-phase corner formation via AnchorActivity relay.
     * Once the re-anchored PickerActivity is in multi-window mode and confined to a split stage,
     * the confined Picker relays through AnchorActivity with `adjacentLaunchFlags()` and
     * `EXTRA_ANCHOR_TARGET = ANCHOR_TARGET_COMPANION`, so AnchorActivity lands in the empty stage
     * and launches CompanionPanelActivity with plain `NEW_TASK | MULTIPLE_TASK` (falling back to
     * direct adjacent launch if the anchor is not in multi-window mode).
     */
    private fun maybeRunPhase2CompanionDock(retryCount: Int = 0) {
        if (!pendingPhase2Companion || phase2CompanionLaunched) return
        val view = rootContentView ?: window?.decorView ?: return

        if (retryCount == 0) {
            if (phase2RetryScheduled) return
            phase2RetryScheduled = true
            view.postDelayed({
                maybeRunPhase2CompanionDock(retryCount = 1)
            }, PHASE2_INITIAL_DELAY_MS)
            return
        }

        if (isFinishing || isDestroyed || !pendingPhase2Companion || phase2CompanionLaunched) {
            phase2RetryScheduled = false
            return
        }

        val isConfined = isInMultiWindowMode && SoloLauncher.isActivityConfinedToSplitStage(this)
        if (!isConfined) {
            if (retryCount < PHASE2_MAX_RETRIES) {
                view.postDelayed({
                    maybeRunPhase2CompanionDock(retryCount + 1)
                }, PHASE2_RETRY_INTERVAL_MS)
                return
            }
            if (!isInMultiWindowMode) {
                phase2CompanionLaunched = true
                pendingPhase2Companion = false
                phase2RetryScheduled = false
                intent?.removeExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION)
                DuoPrefs.recordLaunchLog(
                    context = this,
                    flowOrStrategy = "PANEL_PAIR_FAILED",
                    side = requestedSide.name,
                    targetDesc = "CompanionPanelActivity",
                    flagsHex = "0x00000000",
                    optionsInfo = "phase2TimeoutAfterRetries=$retryCount,inMultiWindow=$isInMultiWindowMode,frame=${SoloLauncher.getActivityWindowFrameString(this)}",
                    result = "FAIL: Picker did not enter multi-window stage in Phase 1; skipping Phase 2"
                )
                SoloLauncher.scheduleFormationCheck(
                    context = applicationContext,
                    formationSide = requestedSide.name
                )
                return
            }
        }

        phase2CompanionLaunched = true
        pendingPhase2Companion = false
        phase2RetryScheduled = false
        intent?.removeExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION)

        SoloLauncher.dockCompanionViaAnchorRelay(
            callerActivity = this,
            companionDisplaySide = requestedSide.opposite(),
            effectiveCompanionSide = side.opposite(),
            pairedPackage = pairedPackage
        )
    }

    /**
     * PATCH 0.1.3 (Part A): Calibration guard.
     * Only measures when PickerActivity is in multi-window mode AND its window width is confined
     * to about one half of the display (between 30% and 65% of the current display width).
     * Never flips sides based on a spanned or fullscreen window.
     */
    private fun maybeAutoCalibrateSplitSides(retryCount: Int = 0) {
        if (!isInMultiWindowMode || DuoPrefs.isFlipCalibrated(this)) return
        val view = rootContentView ?: window?.decorView ?: return
        if (view.width <= 0 || view.height <= 0) {
            if (retryCount < 8) {
                view.postDelayed({ maybeAutoCalibrateSplitSides(retryCount + 1) }, 50L)
            }
            return
        }
        view.post {
            if (isFinishing || isDestroyed) return@post
            if (!isInMultiWindowMode || DuoPrefs.isFlipCalibrated(this)) return@post
            val v = rootContentView ?: window?.decorView ?: return@post
            val viewWidth = v.width
            val viewHeight = v.height
            if (viewWidth <= 0 || viewHeight <= 0) return@post

            val currentBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds
            } else {
                Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            }
            val maxBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.maximumWindowMetrics.bounds
            } else {
                currentBounds
            }
            val displayWidth = maxOf(currentBounds.width(), maxBounds.width())
            val displayHeight = maxOf(currentBounds.height(), maxBounds.height())
            if (displayWidth <= 0 || displayHeight <= 0) return@post

            val minConfinedWidth = (displayWidth * 0.30f).toInt()
            val maxConfinedWidth = (displayWidth * 0.65f).toInt()
            val measuredWindowWidth = if (currentBounds.width() > 0) {
                maxOf(viewWidth, currentBounds.width())
            } else {
                viewWidth
            }
            val isHalfWidthConfined =
                (viewWidth in minConfinedWidth..maxConfinedWidth) &&
                    (measuredWindowWidth in minConfinedWidth..maxConfinedWidth)

            if (!isHalfWidthConfined) {
                if (retryCount < 8) {
                    v.postDelayed({ maybeAutoCalibrateSplitSides(retryCount + 1) }, 60L)
                    return@post
                }
                if (!calibrationSkippedLogged) {
                    calibrationSkippedLogged = true
                    DuoPrefs.recordLaunchLog(
                        context = this,
                        flowOrStrategy = "CALIBRATION_SKIPPED",
                        side = side.name,
                        targetDesc = "PickerActivity",
                        flagsHex = "0x00000000",
                        optionsInfo = "view=${viewWidth}x${viewHeight},winBounds=${currentBounds.width()}x${currentBounds.height()},display=${displayWidth}x${displayHeight},inMultiWindow=$isInMultiWindowMode",
                        result = "Skipped calibration: window width $measuredWindowWidth not in 30%..65% ($minConfinedWidth..$maxConfinedWidth) of display width $displayWidth"
                    )
                }
                return@post
            }

            val loc = IntArray(2)
            v.getLocationOnScreen(loc)

            val centerX = loc[0] + (viewWidth / 2)
            val physicalSide =
                if (centerX < (displayWidth / 2)) DuoPrefs.PanelSide.LEFT else DuoPrefs.PanelSide.RIGHT

            // Compare with the side the Picker believes it occupies (its own panel side state)
            val believedSide = side
            val metricsDesc =
                "confinedHalf:centerX=$centerX,halfW=${displayWidth / 2},loc=(${loc[0]},${loc[1]}),view=${viewWidth}x${viewHeight},display=${displayWidth}x${displayHeight}"

            if (physicalSide != believedSide) {
                DuoPrefs.setFlipSides(this, true)
                DuoPrefs.setFlipCalibrated(this, true)
                DuoPrefs.recordLaunchLog(
                    context = this,
                    flowOrStrategy = "CALIBRATION_AUTO_FLIP",
                    side = "${believedSide.name}->${physicalSide.name}",
                    targetDesc = "PickerActivity",
                    flagsHex = "0x00000000",
                    optionsInfo = metricsDesc,
                    result = "believed=${believedSide.name}, physical=${physicalSide.name}; auto-enabled flipSides=true"
                )
                Toast.makeText(
                    this,
                    "This device mirrors split sides. DuoPanel adjusted automatically.",
                    Toast.LENGTH_LONG
                ).show()
                side = physicalSide
                requestedSide = physicalSide
                refreshAllUi()
            } else {
                DuoPrefs.setFlipCalibrated(this, true)
                DuoPrefs.recordLaunchLog(
                    context = this,
                    flowOrStrategy = "CALIBRATION_OK",
                    side = physicalSide.name,
                    targetDesc = "PickerActivity",
                    flagsHex = "0x00000000",
                    optionsInfo = metricsDesc,
                    result = "believed=${believedSide.name}, physical=${physicalSide.name}; sides match"
                )
            }
        }
    }

    private fun syncMultiWindowPickMode() {
        if (isInMultiWindowMode || reanchored || defaultReplaceRequested || !pairedPackage.isNullOrBlank()) {
            hasSplitActive = true
        }
    }

    private fun buildOneUiContentView() {
        val outerMargin = OneUiViews.dp(this, 14)

        val screenRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerMargin, OneUiViews.dp(this@PickerActivity, 12), outerMargin, OneUiViews.dp(this@PickerActivity, 8))
        }
        // PATCH 0.1.9: mirror of the 0.1.8 bottom inset listener so the top drawer bar clears
        // the status bar under edge-to-edge enforcement.
        OneUiViews.applyTopStatusBarInsetPadding(drawerContent)

        // 1. Top Drawer Bar: Panel graphic + Panel title + plain action row (Swap, Full, Clock, Close) + Three-dot overflow (⋮)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = OneUiViews.dp(this@PickerActivity, 8)
            }
        }

        panelGraphicView = OneUiViews.PanelGraphicView(
            context = this,
            highlightLeft = requestedSide == DuoPrefs.PanelSide.LEFT,
            highlightRight = requestedSide == DuoPrefs.PanelSide.RIGHT,
            sizeDp = 30
        )
        topBar.addView(panelGraphicView)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = OneUiViews.dp(this@PickerActivity, 10)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@PickerActivity, MainActivity::class.java))
            }
        }

        headerTitleText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerSubtitleText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(headerTitleText)
        titleCol.addView(headerSubtitleText)
        topBar.addView(titleCol)

        fun createTopActionText(label: String, onClick: () -> Unit): TextView {
            val hPad = OneUiViews.dp(this, 8)
            val vPad = OneUiViews.dp(this, 6)
            return TextView(this).apply {
                text = label
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(hPad, vPad, hPad, vPad)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        topBar.addView(
            createTopActionText("Swap") {
                val swapBaseSide = if (pairedPackage.isNullOrBlank()) requestedSide else side
                SoloLauncher.swapCurrentPair(this, swapBaseSide, pairedPackage)
            }
        )
        topBar.addView(
            createTopActionText("Full") {
                SoloLauncher.expandPairedAppFullscreen(this, pairedPackage)
            }
        )
        clockActionBtn = createTopActionText("Clock") {
            panelClockExpanded = !panelClockExpanded
            refreshAllUi()
        }
        topBar.addView(clockActionBtn)
        topBar.addView(
            createTopActionText("Close") {
                finish()
            }
        )

        val overflowBtn = OneUiViews.ThreeDotMenuView(this) { anchorView ->
            showDrawerOverflowMenu(anchorView)
        }
        topBar.addView(overflowBtn)
        drawerContent.addView(topBar)

        // 2. Collapsible Clock / Date + Paired App + Quiet Mode Controls Card
        val statusCard = OneUiViews.createGroupedCard(this, bottomMarginDp = 12, verticalPadDp = 8)

        // Panel Clock / Date block (togglable via "Clock" button)
        panelClockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = OneUiViews.dp(this@PickerActivity, 16)
            val vPad = OneUiViews.dp(this@PickerActivity, 8)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val clock = TextClock(this@PickerActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val dateText = TextView(this@PickerActivity).apply {
                text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER
            }
            addView(clock)
            addView(dateText)
        }
        statusCard.addView(panelClockBlock)

        // Paired app indicator row (settings-style row when paired with an app on the other panel)
        pairedBadgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@PickerActivity, 16)
            val vPad = OneUiViews.dp(this@PickerActivity, 8)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        statusCard.addView(pairedBadgeContainer)

        // Visually quiet One UI Segmented Pick Mode Control + One-Line Hint
        val segmentedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = OneUiViews.dp(this@PickerActivity, 14)
            val vPad = OneUiViews.dp(this@PickerActivity, 6)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val segmentedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = OneUiViews.roundedBg(this@PickerActivity, OneUiViews.COLOR_BG_NAVY, 20)
            val p = OneUiViews.dp(this@PickerActivity, 3)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        modeOtherSideBtn = TextView(this).apply {
            text = "Open on other side"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            val vp = OneUiViews.dp(this@PickerActivity, 7)
            setPadding(vp, vp, vp, vp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                pickMode = PickMode.OPEN_OTHER_SIDE
                refreshPickModeUi()
            }
        }

        modeReplaceSelfBtn = TextView(this).apply {
            text = "Replace this panel"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            val vp = OneUiViews.dp(this@PickerActivity, 7)
            setPadding(vp, vp, vp, vp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                pickMode = PickMode.REPLACE_THIS_PANEL
                refreshPickModeUi()
            }
        }

        segmentedRow.addView(modeOtherSideBtn)
        segmentedRow.addView(modeReplaceSelfBtn)
        segmentedContainer.addView(segmentedRow)

        hintBannerText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                OneUiViews.dp(this@PickerActivity, 4),
                OneUiViews.dp(this@PickerActivity, 6),
                OneUiViews.dp(this@PickerActivity, 4),
                0
            )
        }
        segmentedContainer.addView(hintBannerText)
        statusCard.addView(segmentedContainer)
        drawerContent.addView(statusCard)

        // 3. Favorites Top Drawer Group (separated from All Apps by a hairline divider)
        favoritesBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = OneUiViews.dp(this@PickerActivity, 8)
            }
        }

        val favHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@PickerActivity, 6)
            setPadding(hPad, OneUiViews.dp(this@PickerActivity, 2), hPad, OneUiViews.dp(this@PickerActivity, 6))
        }
        favHeaderRow.addView(
            TextView(this).apply {
                text = "Favorites"
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        favHeaderRow.addView(
            TextView(this).apply {
                text = "Long-press app to star"
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
        )
        favoritesBlock.addView(favHeaderRow)

        favoritesGrid = GridLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        favoritesBlock.addView(favoritesGrid)
        // PATCH 0.2.0: the favorites block becomes one of the arrangeable
        // sections, placed by populateSections() in the user's configured
        // order alongside the Recent apps and Frequently used rows. Its old
        // built-in bottom divider is gone: the sections spec puts exactly one
        // hairline above All Apps (sectionsDivider below), none between the
        // top sections.
        sectionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        drawerContent.addView(sectionsContainer)
        sectionsDivider = OneUiViews.createHairlineDivider(this, horizontalInsetDp = 20).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                OneUiViews.dp(this@PickerActivity, 6)
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin =
                OneUiViews.dp(this@PickerActivity, 10)
        }
        drawerContent.addView(sectionsDivider)

        // 4. All Apps Drawer Grid (4-column One UI drawer grid on phone/panel width, 5 on wide unfolded)
        val allHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@PickerActivity, 6)
            setPadding(hPad, OneUiViews.dp(this@PickerActivity, 2), hPad, OneUiViews.dp(this@PickerActivity, 6))
        }
        allHeaderRow.addView(
            TextView(this).apply {
                text = "All apps"
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        allAppsCountText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        allHeaderRow.addView(allAppsCountText)
        drawerContent.addView(allHeaderRow)

        allAppsGrid = GridLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        allAppsGrid.setOnDragListener(gridDragListener)
        drawerContent.addView(allAppsGrid)

        scroll.addView(drawerContent)
        screenRoot.addView(scroll)

        // 5. One UI Bottom Search Pill ("Search", magnifier icon left, pinned at bottom of drawer)
        val bottomSearchBarWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = OneUiViews.dp(this@PickerActivity, 22)
            val topPad = OneUiViews.dp(this@PickerActivity, 8)
            val botPad = OneUiViews.dp(this@PickerActivity, 14)
            setPadding(hPad, topPad, hPad, botPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OneUiViews.roundedBg(
                context = this@PickerActivity,
                fillColor = OneUiViews.COLOR_SEARCH_PILL,
                radiusDp = 26,
                // PATCH 0.2.0 design pass: the light palette's white pill gets
                // a quiet 1dp stroke so it never merges into bright backdrops.
                strokeColor = OneUiViews.COLOR_SEARCH_PILL_STROKE,
                strokeWidthDp = if (OneUiViews.COLOR_SEARCH_PILL_STROKE != 0) 1 else 0
            )
            val hPad = OneUiViews.dp(this@PickerActivity, 18)
            val vPad = OneUiViews.dp(this@PickerActivity, 10)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val magnifier = OneUiViews.MagnifierIconView(this, sizeDp = 19).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginEnd =
                OneUiViews.dp(this@PickerActivity, 12)
        }
        searchMagnifierView = magnifier
        searchPill.addView(magnifier)

        val searchBox = EditText(this).apply {
            hint = "Search"
            setHintTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
            // PATCH 0.2.0 design pass: selection highlight takes the accent
            // tint; the magnifier tints to the accent only while search is
            // focused or holds a query (see updateSearchAccentTint).
            highlightColor = OneUiViews.COLOR_ONEUI_BLUE_TINT
            setOnFocusChangeListener { _, hasFocus ->
                searchBoxHasFocus = hasFocus
                updateSearchAccentTint()
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setSingleLine(true)
            isFocusableInTouchMode = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    updateSearchAccentTint()
                    populateAppGrids()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchPill.addView(searchBox)
        searchPill.setOnClickListener {
            searchBox.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
        }
        bottomSearchBarWrapper.addView(searchPill)
        searchBarWrapper = bottomSearchBarWrapper
        screenRoot.addView(bottomSearchBarWrapper)

        // PATCH 0.1.8 (change 2): in spanned fullscreen the ROM's bottom system strip
        // (taskbar) overlays the window's bottom ~140px and covers the pill, which is
        // laid out but invisible and untappable there. Lift the wrapper by the bottom
        // system window inset, added to its designed bottom padding, so the pill
        // renders above the strip. Insets are returned unconsumed.
        val searchBaseBottomPad = bottomSearchBarWrapper.paddingBottom
        bottomSearchBarWrapper.setOnApplyWindowInsetsListener { v, insets ->
            val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(
                v.paddingLeft, v.paddingTop, v.paddingRight,
                searchBaseBottomPad + bottomInset
            )
            insets
        }

        rootContentView = screenRoot
        setContentView(screenRoot)
        screenRoot.requestApplyInsets()
        OneUiViews.applyTranslucentBlurWindow(this, screenRoot, attachListener = true)
    }

    private fun showDrawerOverflowMenu(anchorView: View) {
        val swapBaseSide = if (pairedPackage.isNullOrBlank()) requestedSide else side
        OneUiViews.showFloatingMenu(
            activity = this,
            anchor = anchorView,
            items = listOf(
                OneUiViews.FloatingMenuItem(
                    title = "Replace this panel",
                    selected = (pickMode == PickMode.REPLACE_THIS_PANEL)
                ) {
                    pickMode = PickMode.REPLACE_THIS_PANEL
                    refreshPickModeUi()
                },
                OneUiViews.FloatingMenuItem(
                    title = "Open on other side",
                    selected = (pickMode == PickMode.OPEN_OTHER_SIDE)
                ) {
                    pickMode = PickMode.OPEN_OTHER_SIDE
                    refreshPickModeUi()
                },
                OneUiViews.FloatingMenuItem(
                    title = "Clock",
                    selected = panelClockExpanded
                ) {
                    panelClockExpanded = !panelClockExpanded
                    refreshAllUi()
                },
                OneUiViews.FloatingMenuItem(
                    title = "Swap"
                ) {
                    SoloLauncher.swapCurrentPair(this, swapBaseSide, pairedPackage)
                },
                OneUiViews.FloatingMenuItem(
                    title = "Full"
                ) {
                    SoloLauncher.expandPairedAppFullscreen(this, pairedPackage)
                },
                OneUiViews.FloatingMenuItem(
                    title = "DuoPanel settings"
                ) {
                    startActivity(Intent(this, MainActivity::class.java))
                },
                OneUiViews.FloatingMenuItem(
                    title = "Close"
                ) {
                    finish()
                }
            )
        )
    }

    private fun refreshAllUi() {
        panelGraphicView.setSide(requestedSide)
        headerTitleText.text = requestedSide.title
        headerSubtitleText.text = if (isInMultiWindowMode) {
            "Multi-Window • ${requestedSide.title}"
        } else {
            "DuoPanel • Tap title for settings"
        }

        clockActionBtn.setTextColor(
            if (panelClockExpanded) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_PRIMARY
        )

        // Paired app row
        val paired = pairedPackage
        if (!paired.isNullOrBlank()) {
            pairedBadgeContainer.visibility = View.VISIBLE
            pairedBadgeContainer.removeAllViews()
            val iconDrawable = AppRepository.getAppIcon(this, paired)
            if (iconDrawable != null) {
                val iv = ImageView(this).apply {
                    setImageDrawable(iconDrawable)
                    val sz = OneUiViews.dp(this@PickerActivity, 26)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = OneUiViews.dp(this@PickerActivity, 10)
                    }
                }
                pairedBadgeContainer.addView(iv)
            }
            val label = AppRepository.getAppLabel(this, paired)
            pairedBadgeContainer.addView(
                TextView(this).apply {
                    text = "Paired with $label (${requestedSide.oppositeTitle})"
                    setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                }
            )
        } else {
            pairedBadgeContainer.visibility = View.GONE
        }

        panelClockBlock.visibility = if (panelClockExpanded) View.VISIBLE else View.GONE

        // PATCH 0.1.7: "Show search bar" display pref
        searchBarWrapper?.visibility =
            if (DuoPrefs.isShowSearchBar(this)) View.VISIBLE else View.GONE

        refreshPickModeUi()
        populateAppGrids()
    }

    private fun refreshPickModeUi() {
        val isReplace = (pickMode == PickMode.REPLACE_THIS_PANEL)

        modeOtherSideBtn.background = if (!isReplace) {
            OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 17)
        } else {
            null
        }
        modeOtherSideBtn.setTextColor(
            if (!isReplace) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_SECONDARY
        )
        modeOtherSideBtn.setTypeface(
            null,
            if (!isReplace) Typeface.BOLD else Typeface.NORMAL
        )

        modeReplaceSelfBtn.background = if (isReplace) {
            OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 17)
        } else {
            null
        }
        modeReplaceSelfBtn.setTextColor(
            if (isReplace) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_SECONDARY
        )
        modeReplaceSelfBtn.setTypeface(
            null,
            if (isReplace) Typeface.BOLD else Typeface.NORMAL
        )

        // One-line hint bar (PATCH 0.1.1C: "Next app opens on THIS panel" when "Replace this panel" is selected)
        hintBannerText.text = if (isReplace) {
            "Next app opens on THIS panel"
        } else if (requestedSide == DuoPrefs.PanelSide.RIGHT) {
            "Next app opens on the LEFT — tap an app (Picker stays RIGHT)"
        } else {
            "Next app opens on the RIGHT — tap an app (Picker stays LEFT)"
        }
    }

    private fun computeAdaptiveColumns(): Int {
        // PATCH 0.1.7: a forced Picker grid value wins over Auto in the standalone Picker too.
        DuoPrefs.getPickerGrid(this).columns?.let { return it }
        val widthDp = resources.configuration.screenWidthDp
        // 4 columns on phone / half panel (~340-520dp), 5 columns on wide unfolded display (>= 640dp)
        return when {
            widthDp >= 640 -> 5
            else -> 4
        }
    }

    private fun populateAppGrids() {
        val cols = computeAdaptiveColumns()
        val alphabetical = AppRepository.getLaunchableApps(this)
        // PATCH 0.1.7: apply the persisted display order (Alphabetical or Custom).
        val orderedPkgs = DuoPrefs.applyDisplayOrder(this, alphabetical.map { it.packageName })
        val byPkg = alphabetical.associateBy { it.packageName }
        // PATCH 0.1.7 Part B: hidden apps are filtered out of the Picker everywhere
        // (All Apps, Favorites, search). A hidden favorite is skipped, not deleted.
        val hiddenPkgs = DuoPrefs.getHiddenPackages(this)
        val allApps = orderedPkgs.mapNotNull { byPkg[it] }
            .filter { !hiddenPkgs.contains(it.packageName) }
        val favSet = DuoPrefs.getFavorites(this)

        val filtered = if (searchQuery.isBlank()) {
            allApps
        } else {
            val q = searchQuery.lowercase(Locale.getDefault())
            allApps.filter {
                it.label.lowercase(Locale.getDefault()).contains(q) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(q)
            }
        }

        val favApps = filtered.filter { favSet.contains(it.packageName) }

        // Favorites top group
        favoritesGrid.removeAllViews()
        favoritesGrid.columnCount = cols
        if (favApps.isEmpty()) {
            favoritesBlock.visibility = if (searchQuery.isNotBlank()) View.GONE else View.VISIBLE
            val emptyHint = TextView(this).apply {
                text = "No favorites starred yet. Long-press any app below to add it here."
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val hPad = OneUiViews.dp(this@PickerActivity, 6)
                setPadding(hPad, OneUiViews.dp(this@PickerActivity, 2), hPad, OneUiViews.dp(this@PickerActivity, 4))
            }
            favoritesGrid.addView(emptyHint)
        } else {
            favoritesBlock.visibility = View.VISIBLE
            for (app in favApps) {
                favoritesGrid.addView(createAppGridCell(app, columns = cols))
            }
        }

        // All Apps grid
        allAppsCountText.text = "${filtered.size} apps"
        allAppsGrid.removeAllViews()
        allAppsGrid.columnCount = cols
        // PATCH 0.1.7: long-press drag reorder is live only in Custom sort mode with no
        // active search filter (filtered list == full ordered list in that state).
        val dragEnabled = DuoPrefs.getSortOrder(this) == DuoPrefs.SortOrder.CUSTOM &&
            searchQuery.isBlank()
        for (app in filtered) {
            allAppsGrid.addView(
                createAppGridCell(
                    app = app,
                    columns = cols,
                    draggable = dragEnabled
                )
            )
        }

        populateSections(byPkg, hiddenPkgs)
    }

    /**
     * PATCH 0.2.0: (re)builds the arrangeable sections container in the user's
     * configured order. Favorites reuses the block built by
     * buildOneUiContentView; Recent / Frequently used come from PickerSections
     * (live usage stats). While a search query is active only Favorites shows
     * (with its existing filtered behavior); the usage rows hide.
     */
    /**
     * PATCH 0.2.0 (design pass search treatment): the magnifier carries the
     * accent only while the search box is focused or holds a query; at rest
     * the whole pill is neutral. The pill fill never takes the accent.
     */
    private fun updateSearchAccentTint() {
        val active = searchBoxHasFocus || searchQuery.isNotEmpty()
        searchMagnifierView?.iconTint =
            if (active) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_SECONDARY
    }

    private fun populateSections(
        byPkg: Map<String, AppRepository.InstalledApp>,
        hiddenPkgs: Set<String>
    ) {
        if (!::sectionsContainer.isInitialized) return
        (favoritesBlock.parent as? ViewGroup)?.removeView(favoritesBlock)
        sectionsContainer.removeAllViews()
        val searching = searchQuery.isNotBlank()
        var anyRendered = false
        for (section in DuoPrefs.getSectionOrder(this)) {
            if (!DuoPrefs.isSectionVisible(this, section)) continue
            when (section) {
                DuoPrefs.PickerSection.FAVORITES -> {
                    sectionsContainer.addView(favoritesBlock)
                    if (favoritesBlock.visibility == View.VISIBLE) anyRendered = true
                }
                DuoPrefs.PickerSection.RECENT, DuoPrefs.PickerSection.FREQUENT -> {
                    if (searching) continue
                    PickerSections.buildUsageSection(
                        context = this,
                        section = section,
                        appsByPackage = byPkg,
                        hiddenPackages = hiddenPkgs,
                        viewportWidthDp = resources.configuration.screenWidthDp,
                        gridColumns = computeAdaptiveColumns(),
                        onPick = { app -> onAppPicked(app) },
                        onLongPress = { app ->
                            val nowFav = DuoPrefs.toggleFavorite(this, app.packageName)
                            Toast.makeText(
                                this,
                                if (nowFav) "Added ${app.label} to Favorites"
                                else "Removed ${app.label} from Favorites",
                                Toast.LENGTH_SHORT
                            ).show()
                            populateAppGrids()
                        }
                    )?.let {
                        sectionsContainer.addView(it)
                        anyRendered = true
                    }
                }
            }
        }
        if (::sectionsDivider.isInitialized) {
            sectionsDivider.visibility = if (anyRendered) View.VISIBLE else View.GONE
        }
    }

    private fun createAppGridCell(
        app: AppRepository.InstalledApp,
        columns: Int,
        draggable: Boolean = false
    ): View {
        val hPad = OneUiViews.dp(this, 6)
        val vPad = OneUiViews.dp(this, 11)
        // PATCH 0.1.7: icon size comes from the App size display pref (48 / 56 / 64dp).
        val iconSize = OneUiViews.dp(this, DuoPrefs.getAppSize(this).iconDp)
        val showLabels = DuoPrefs.isShowLabels(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }

            addView(buildIconWithBadge(app, iconSize))

            // PATCH 0.1.7: "Show app labels" display pref.
            if (showLabels) {
                val labelTv = TextView(this@PickerActivity).apply {
                    text = app.label
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    // PATCH 0.1.7 calibration: label text scales slightly with App size.
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, DuoPrefs.getAppSize(this@PickerActivity).labelSp)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, OneUiViews.dp(this@PickerActivity, 6), 0, 0)
                }
                addView(labelTv)
            }

            setOnClickListener {
                onAppPicked(app)
            }

            if (draggable) {
                // PATCH 0.1.7: in Custom sort mode, long-press drag reorders the All Apps grid.
                setOnDragListener(gridDragListener)
                setOnLongClickListener {
                    dragCellView = this
                    alpha = 0.35f
                    val data = ClipData.newPlainText("duopanel_pkg", app.packageName)
                    startDragAndDrop(data, View.DragShadowBuilder(this), app.packageName, 0)
                    true
                }
            } else {
                setOnLongClickListener {
                    val nowFav = DuoPrefs.toggleFavorite(this@PickerActivity, app.packageName)
                    Toast.makeText(
                        this@PickerActivity,
                        if (nowFav) "Added ${app.label} to Favorites"
                        else "Removed ${app.label} from Favorites",
                        Toast.LENGTH_SHORT
                    ).show()
                    populateAppGrids()
                    true
                }
            }
        }
    }

    /**
     * PATCH 0.1.7 Part B: the grid cell icon with its notification badge overlay.
     * Dot = small Samsung-blue dot at the icon's top-right; Number = blue pill with the
     * active-notification count, capped at 99+. No badge when badges are off, when
     * notification access is not granted (listener not connected), or when the app has
     * no active notifications. Counts are never faked.
     */
    private fun buildIconWithBadge(app: AppRepository.InstalledApp, iconSize: Int): View {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (app.icon != null) {
                setImageDrawable(app.icon)
            } else {
                setImageResource(R.drawable.ic_launcher_foreground)
            }
        }
        frame.addView(iv)

        if (DuoPrefs.isBadgesEnabled(this) && DuoNotificationListener.connected) {
            val count = DuoNotificationListener.countFor(app.packageName)
            if (count > 0) {
                when (DuoPrefs.getBadgeStyle(this)) {
                    DuoPrefs.BadgeStyle.DOT -> {
                        val dotSize = OneUiViews.dp(this, 12)
                        val dot = View(this).apply {
                            background = OneUiViews.roundedBg(
                                context = this@PickerActivity,
                                fillColor = OneUiViews.COLOR_ONEUI_BLUE,
                                radiusDp = 6
                            )
                            layoutParams = FrameLayout.LayoutParams(
                                dotSize, dotSize, Gravity.TOP or Gravity.END
                            )
                        }
                        frame.addView(dot)
                    }
                    DuoPrefs.BadgeStyle.NUMBER -> {
                        val pill = TextView(this).apply {
                            text = if (count > 99) "99+" else count.toString()
                            // PATCH 0.2.0 on-accent rule: white or dark ink,
                            // whichever contrasts the active accent.
                            setTextColor(DuoTheme.onAccent(OneUiViews.COLOR_ONEUI_BLUE))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            setTypeface(typeface, Typeface.BOLD)
                            gravity = Gravity.CENTER
                            background = OneUiViews.roundedBg(
                                context = this@PickerActivity,
                                fillColor = OneUiViews.COLOR_ONEUI_BLUE,
                                radiusDp = 9
                            )
                            val hPad = OneUiViews.dp(this@PickerActivity, 5)
                            setPadding(hPad, 0, hPad, 0)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                OneUiViews.dp(this@PickerActivity, 18),
                                Gravity.TOP or Gravity.END
                            )
                        }
                        frame.addView(pill)
                    }
                }
            }
        }
        return frame
    }

    // ------------------------------------------------------------------
    // PATCH 0.1.7: Custom-order drag reorder (All Apps grid)
    // ------------------------------------------------------------------

    private val gridDragListener = View.OnDragListener { v, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                highlightDropCell(dropIndexAt(v, event.x, event.y))
                true
            }
            DragEvent.ACTION_DROP -> {
                val target = dropIndexAt(v, event.x, event.y)
                val pkg = (event.localState as? String)
                    ?: event.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                clearDropHighlight()
                if (pkg != null && target >= 0) {
                    reorderCustomOrder(pkg, target)
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                clearDropHighlight()
                dragCellView?.alpha = 1f
                dragCellView = null
                true
            }
            else -> true
        }
    }

    /** Converts event coordinates (relative to the receiving view) into a grid cell index. */
    private fun dropIndexAt(v: View, x: Float, y: Float): Int {
        val grid = allAppsGrid
        val gx = if (v === grid) x else v.left + x
        val gy = if (v === grid) y else v.top + y
        if (grid.childCount == 0) return -1
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (gx >= child.left && gx < child.right && gy >= child.top && gy < child.bottom) {
                return i
            }
        }
        val last = grid.getChildAt(grid.childCount - 1)
        return if (gy >= last.bottom) grid.childCount - 1 else -1
    }

    private fun highlightDropCell(index: Int) {
        if (index == dragHighlightIndex) return
        clearDropHighlight()
        dragHighlightIndex = index
        if (index >= 0 && index < allAppsGrid.childCount) {
            allAppsGrid.getChildAt(index).background =
                OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 18)
        }
    }

    private fun clearDropHighlight() {
        if (dragHighlightIndex >= 0 && dragHighlightIndex < allAppsGrid.childCount) {
            allAppsGrid.getChildAt(dragHighlightIndex).background = null
        }
        dragHighlightIndex = -1
    }

    private fun reorderCustomOrder(pkg: String, targetIndex: Int) {
        val alphabetical = AppRepository.getLaunchableApps(this)
        val current = DuoPrefs.applyDisplayOrder(this, alphabetical.map { it.packageName })
            .toMutableList()
        val from = current.indexOf(pkg)
        if (from < 0) return
        val to = targetIndex.coerceIn(0, current.size - 1)
        if (from == to) return
        current.removeAt(from)
        current.add(to, pkg)
        DuoPrefs.setCustomOrder(this, current)
        DuoPrefs.recordLaunchLog(
            context = this,
            flowOrStrategy = "CUSTOM_ORDER_DRAG",
            side = side.name,
            targetDesc = pkg,
            flagsHex = "0x00000000",
            optionsInfo = "from=$from,to=$to",
            result = "OK (custom order updated via drag in PickerActivity)"
        )
        populateAppGrids()
    }

    private fun onAppPicked(app: AppRepository.InstalledApp) {
        performAppPick(app.packageName)
    }

    private fun performAppPick(pkg: String) {
        val shouldReplaceThisPanel = (pickMode == PickMode.REPLACE_THIS_PANEL)

        val targetSide = if (shouldReplaceThisPanel) {
            side
        } else if (isInMultiWindowMode || isCornerShortcutFlow) {
            side.opposite()
        } else {
            side
        }

        val openedDisplaySide = if (shouldReplaceThisPanel) {
            requestedSide
        } else {
            requestedSide.opposite()
        }
        SoloLauncher.recordActivePanelApp(this, openedDisplaySide, pkg)

        val launched = SoloLauncher.launchFromPicker(
            activity = this,
            pkg = pkg,
            side = targetSide,
            replaceMySide = shouldReplaceThisPanel
        )

        // When user explicitly selected "Open on other side" and the app launched on the opposite
        // panel while Picker stayed alive on this panel, switch back to "Replace this panel" for the next pick.
        if (launched && !shouldReplaceThisPanel && targetSide == DuoPrefs.PanelSide.RIGHT) {
            pairedPackage = pkg
            hasSplitActive = true
            pickMode = PickMode.REPLACE_THIS_PANEL
            side = DuoPrefs.effectiveSide(this, DuoPrefs.PanelSide.LEFT)
            requestedSide = side
            refreshAllUi()
        }
    }
}
