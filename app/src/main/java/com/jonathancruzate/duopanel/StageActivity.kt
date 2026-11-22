package com.jonathancruzate.duopanel

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * StageActivity — PATCH 0.1.6 "Simulated Stage" (Jonathan, 2026-11-21).
 *
 * ONE spanned fullscreen DuoPanel window that simulates the two-panel Duo layout in a single
 * surface, bypassing the parked two-surface split formation (0.1.3 to 0.1.5) entirely:
 * - Normal entry (Left Panel / Right Panel shortcuts): picker pane on the entry side,
 *   companion pane on the other half, over a transparent window with the One UI blur/scrim
 *   treatment. No split formation is attempted for Stage entries; the formation code in
 *   PickerActivity / SoloLauncher stays parked and untouched.
 * - Over-a-running-app entry (EXTRA_STAGE_OVER_APP): an ILLUSION, not transparency. A one-time
 *   MediaProjection screenshot of the current screen (consent once per process) is rendered as
 *   the still backdrop of the app half while the companion pane renders live beside it. The
 *   bitmap is memory-only (StageBackdrop), never persisted, released when the Stage closes.
 *   Consent denied or capture failed: the app half falls back to the blur/scrim backdrop.
 *   A tap on the app half dismisses the Stage back to the real app.
 * - Picking an app in the picker pane launches it via the existing per-side picker flow
 *   (SoloLauncher.launchFromPicker, default "Replace this panel" semantics) and finishes.
 */
class StageActivity : Activity() {

    enum class StagePickMode {
        OPEN_OTHER_SIDE,
        REPLACE_THIS_PANEL
    }

    companion object {
        private const val REQUEST_CAPTURE = 6106
        private const val CAPTURE_TIMEOUT_MS = 20000L
        // PATCH 0.2.0: render-settle delay before the after-pick capture.
        private const val SECOND_PICK_CAPTURE_DELAY_MS = 900L
    }

    private var entryRequestedSide: DuoPrefs.PanelSide = DuoPrefs.PanelSide.LEFT
    private var pickerSide: DuoPrefs.PanelSide = DuoPrefs.PanelSide.LEFT
    private var overApp: Boolean = false
    private var appSide: DuoPrefs.PanelSide = DuoPrefs.PanelSide.LEFT
    private var pairedPackage: String? = null
    // PATCH 0.2.0: the after-pick re-formed state. The first picked app runs on
    // appSide (shown as the captured frame); the picker sits on the other side
    // and the next pick completes the pair. Set from EXTRA_STAGE_SECOND_PICK.
    private var secondPick: Boolean = false
    // PATCH 0.2.0: container holding the arrangeable picker sections in the
    // user's configured order (Recent / Frequently used / Favorites).
    private var sectionsContainer: LinearLayout? = null
    // PATCH 0.2.0: the single hairline above All Apps in the picker pane.
    private var sectionsDivider: View? = null
    // PATCH 0.2.0: the picker pane's search magnifier (accent tint while active).
    private var searchMagnifierView: OneUiViews.MagnifierIconView? = null
    private var searchBoxHasFocus: Boolean = false
    // PATCH 0.2.0 audit cleanup: the favorites header's star hint, hidden when
    // the favorites grid is empty (its empty-state text already says it).
    private var favStarHintText: TextView? = null

    private var pickMode: StagePickMode = StagePickMode.REPLACE_THIS_PANEL
    // PATCH 0.2.0: the companion clock is the Stage's only clock; its expanded
    // state is controlled solely by the companion pane's Clock toggle.
    private var companionClockExpanded: Boolean = true
    private var searchQuery: String = ""

    private var captureSettled: Boolean = false
    private var settledViaTimeout: Boolean = false
    private var panesBuilt: Boolean = false

    private var rootLayout: LinearLayout? = null
    private var favoritesGrid: GridLayout? = null
    private var favoritesBlock: LinearLayout? = null
    private var allAppsGrid: GridLayout? = null
    private var allAppsCountText: TextView? = null
    private var pickerGraphicView: OneUiViews.PanelGraphicView? = null
    private var pickerTitleText: TextView? = null
    private var modeOtherSideBtn: TextView? = null
    private var modeReplaceSelfBtn: TextView? = null
    private var hintBannerText: TextView? = null
    private var companionClockBlock: LinearLayout? = null
    private var companionClockDivider: View? = null
    private var companionClockActionBtn: TextView? = null
    private var pickerSearchWrapper: LinearLayout? = null
    // PATCH 0.1.7: custom-order drag state
    private var dragCellView: View? = null
    private var dragHighlightIndex: Int = -1

    // PATCH 0.1.7 Part B: live badge refresh while the Stage is visible
    private val badgeListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && panesBuilt && !overApp) {
                populateAppGrids()
            }
        }
    }

    private val activeAppListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && panesBuilt && !overApp) {
                syncPairedFromEngine()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PATCH 0.2.0: resolve the Appearance palette before any view is built.
        DuoTheme.applyTo(this)
        readIntentState(intent)
        if (overApp) {
            // Stay fully transparent until the one frame is captured so the capture shows the
            // real app, not the Stage itself.
            OneUiViews.clearTranslucentBlurWindow(this)
            setContentView(FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) })
            scheduleBackdropCapture()
        } else {
            buildPanes()
        }
        SoloLauncher.registerActivePanelListener(activeAppListener)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            StageBackdrop.releaseBitmap()
            captureSettled = false
            panesBuilt = false
            readIntentState(intent)
            if (overApp) {
                OneUiViews.clearTranslucentBlurWindow(this)
                setContentView(FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) })
                scheduleBackdropCapture()
            } else {
                buildPanes()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DuoNotificationListener.registerBadgeListener(badgeListener)
        if (panesBuilt) {
            OneUiViews.applyTranslucentBlurWindow(this, rootLayout, attachListener = false)
        }
        // PATCH 0.1.7: display prefs may have changed in Settings while the Stage was paused.
        if (panesBuilt && !overApp) {
            pickerSearchWrapper?.visibility =
                if (DuoPrefs.isShowSearchBar(this)) View.VISIBLE else View.GONE
            populateAppGrids()
        }
    }

    override fun onPause() {
        DuoNotificationListener.unregisterBadgeListener(badgeListener)
        super.onPause()
    }

    override fun onDestroy() {
        SoloLauncher.unregisterActivePanelListener(activeAppListener)
        if (overApp) {
            StageBackdrop.releaseBitmap()
        }
        super.onDestroy()
    }

    private fun readIntentState(incoming: Intent?) {
        val sideExtra = incoming?.getStringExtra(ShortcutHelper.EXTRA_SIDE)
        entryRequestedSide = DuoPrefs.PanelSide.fromString(sideExtra, DuoPrefs.PanelSide.LEFT)
        pickerSide = entryRequestedSide
        appSide = entryRequestedSide
        overApp = incoming?.getBooleanExtra(ShortcutHelper.EXTRA_STAGE_OVER_APP, false) == true
        // PATCH 0.2.0: the after-pick state arrives as an over-app entry whose
        // side names the FIRST app's panel; the picker belongs on the other side.
        secondPick = incoming?.getBooleanExtra(ShortcutHelper.EXTRA_STAGE_SECOND_PICK, false) == true
        if (secondPick) {
            overApp = true
            pickerSide = entryRequestedSide.opposite()
        }
        val explicitPaired = incoming?.getStringExtra(ShortcutHelper.EXTRA_PAIRED_PKG)
        pairedPackage = if (!explicitPaired.isNullOrBlank()) {
            explicitPaired
        } else {
            SoloLauncher.getActivePanelApp(this, entryRequestedSide.opposite())
                ?: SoloLauncher.getActivePanelApp(this, entryRequestedSide)
        }
        DuoPrefs.recordLaunchLog(
            context = this,
            flowOrStrategy = "STAGE_OPEN",
            side = entryRequestedSide.name,
            targetDesc = "StageActivity",
            flagsHex = "0x00000000",
            optionsInfo = "overApp=$overApp,secondPick=$secondPick,paired=${pairedPackage ?: "none"}",
            result = "OK (Stage opened; formation bypassed)"
        )
    }

    private fun syncPairedFromEngine() {
        val fromEngine = SoloLauncher.getActivePanelApp(this, pickerSide.opposite())
        if (!fromEngine.isNullOrBlank() && fromEngine != pairedPackage) {
            pairedPackage = fromEngine
            buildPanes()
        }
    }

    // ------------------------------------------------------------------ over-app capture

    /**
     * PATCH 0.2.0: in the after-pick (secondPick) state the first app was
     * launched a moment ago and needs a beat to render before the one-shot
     * capture fires, or the frame catches the launcher mid-transition. Normal
     * over-app entries capture immediately, as before.
     */
    private fun scheduleBackdropCapture() {
        if (secondPick) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    beginBackdropCapture()
                }
            }, SECOND_PICK_CAPTURE_DELAY_MS)
        } else {
            beginBackdropCapture()
        }
    }

    private fun beginBackdropCapture() {
        captureSettled = false
        settledViaTimeout = false
        Handler(Looper.getMainLooper()).postDelayed({
            if (!captureSettled && !isFinishing && !isDestroyed) {
                captureSettled = true
                settledViaTimeout = true
                DuoPrefs.recordLaunchLog(
                    context = this,
                    flowOrStrategy = "STAGE_CAPTURE_TIMEOUT",
                    side = entryRequestedSide.name,
                    targetDesc = "StageBackdrop",
                    flagsHex = "0x00000000",
                    optionsInfo = "overApp=true",
                    result = "FAIL: capture timed out; blur/scrim fallback backdrop"
                )
                buildPanes()
            }
        }, CAPTURE_TIMEOUT_MS)

        if (StageBackdrop.projection != null) {
            StageCaptureService.requestCapture(this, null, null) { ok -> onCaptureResult(ok) }
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            onCaptureResult(false)
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (_: Throwable) {
            onCaptureResult(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            StageCaptureService.requestCapture(this, resultCode, data) { ok -> onCaptureResult(ok) }
        } else {
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_CAPTURE_DENIED",
                side = entryRequestedSide.name,
                targetDesc = "StageBackdrop",
                flagsHex = "0x00000000",
                optionsInfo = "overApp=true",
                result = "Consent denied; blur/scrim fallback backdrop"
            )
            onCaptureResult(false)
        }
    }

    private fun onCaptureResult(ok: Boolean) {
        runOnUiThread {
            if (captureSettled) {
                // A frame landing after the timeout fallback still upgrades the
                // backdrop in place while this Stage is alive.
                if (ok && settledViaTimeout && !isFinishing && !isDestroyed) {
                    settledViaTimeout = false
                    buildPanes()
                }
                return@runOnUiThread
            }
            captureSettled = true
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = if (ok) "STAGE_CAPTURE_OK" else "STAGE_CAPTURE_FAIL",
                side = entryRequestedSide.name,
                targetDesc = "StageBackdrop",
                flagsHex = "0x00000000",
                optionsInfo = "overApp=true,bitmap=${StageBackdrop.bitmap != null}",
                result = if (ok) "OK (one frame captured to memory only)" else "FAIL: blur/scrim fallback backdrop"
            )
            buildPanes()
        }
    }

    // ------------------------------------------------------------------ layout assembly

    private fun buildPanes() {
        panesBuilt = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout = root

        val leftPane: View
        val rightPane: View
        if (secondPick) {
            // PATCH 0.2.0 after-pick state: the first app's captured frame on its
            // own side, the picker on the free side. No companion pane, and no
            // big clock on either pane (Jonathan's single-clock rule).
            val appPane = buildAppBackdropPane()
            val pickerPane = buildPickerPane()
            if (appSide == DuoPrefs.PanelSide.LEFT) {
                leftPane = appPane
                rightPane = pickerPane
            } else {
                leftPane = pickerPane
                rightPane = appPane
            }
        } else if (overApp) {
            val appPane = buildAppBackdropPane()
            val companionPane = buildCompanionPane()
            if (appSide == DuoPrefs.PanelSide.LEFT) {
                leftPane = appPane
                rightPane = companionPane
            } else {
                leftPane = companionPane
                rightPane = appPane
            }
        } else {
            val pickerPane = buildPickerPane()
            val companionPane = buildCompanionPane()
            if (pickerSide == DuoPrefs.PanelSide.LEFT) {
                leftPane = pickerPane
                rightPane = companionPane
            } else {
                leftPane = companionPane
                rightPane = pickerPane
            }
        }
        root.addView(leftPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(rightPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
        root.requestApplyInsets()
        OneUiViews.applyTranslucentBlurWindow(this, root, attachListener = true)
    }

    private fun halfParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    /**
     * The simulated app half for the over-app case: the captured still frame, cropped to this
     * half of the screen, or the blur/scrim fallback when no frame is available. Tap dismisses.
     */
    private fun buildAppBackdropPane(): View {
        val container = FrameLayout(this).apply {
            clipChildren = true
            isClickable = true
            isFocusable = true
            setOnClickListener {
                DuoPrefs.recordLaunchLog(
                    context = this@StageActivity,
                    flowOrStrategy = "STAGE_DISMISS",
                    side = appSide.name,
                    targetDesc = "StageActivity",
                    flagsHex = "0x00000000",
                    optionsInfo = "overApp=true",
                    result = "OK (tap on app half dismissed Stage back to the real app)"
                )
                finish()
            }
        }
        val bitmap = StageBackdrop.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            val fullWidthPx = resources.displayMetrics.widthPixels
            val fullHeightPx = resources.displayMetrics.heightPixels
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                val lp = FrameLayout.LayoutParams(fullWidthPx, fullHeightPx)
                lp.leftMargin = if (appSide == DuoPrefs.PanelSide.LEFT) 0 else -(fullWidthPx / 2)
                layoutParams = lp
            }
            container.addView(image)
        } else {
            container.setBackgroundColor(Color.TRANSPARENT)
            val caption = TextView(this).apply {
                text = "Tap to return to the app"
                // DESIGN-PASS-0.2.0: the caption sits in a FIXED dark pill with
                // near-white text, never palette colors, because the backdrop
                // behind it is arbitrary app content (or the light no-blur
                // scrim, where secondary text would fail contrast).
                setTextColor(0xFFFAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                background = OneUiViews.roundedBg(this@StageActivity, 0xBF0B0E1A.toInt(), 999)
                val hPad = OneUiViews.dp(this@StageActivity, 14)
                val vPad = OneUiViews.dp(this@StageActivity, 7)
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = OneUiViews.dp(this@StageActivity, 28)
                }
            }
            container.addView(caption)
        }
        return container
    }

    // ------------------------------------------------------------------ picker pane

    private fun buildPickerPane(): View {
        val outerMargin = OneUiViews.dp(this, 14)
        val paneRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerMargin, OneUiViews.dp(this@StageActivity, 12), outerMargin, OneUiViews.dp(this@StageActivity, 8))
        }
        // PATCH 0.1.9: mirror of the 0.1.8 bottom inset listener so the picker pane top bar clears
        // the status bar under edge-to-edge enforcement.
        OneUiViews.applyTopStatusBarInsetPadding(drawerContent)

        // Top bar: panel title only in fresh entry; Swap / Full / Close join it
        // in the after-pick state (PATCH 0.2.0: Clock removed per the
        // single-clock rule; window actions de-duplicated per the audit).
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = OneUiViews.dp(this@StageActivity, 8) }
        }
        // PATCH 0.2.0 audit fix: the picker top bar no longer carries its own
        // PanelGraphicView (the companion keeps the single 38dp identity
        // badge; two differently-sized glyphs side by side were clutter) and
        // no settings subtitle (the companion's DuoPanel link is the settings
        // entry; the title stays quietly tappable, which is also the only
        // settings path in the after-pick state where no companion exists).
        pickerGraphicView = null
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@StageActivity, MainActivity::class.java)) }
        }
        val title = TextView(this).apply {
            text = pickerSide.title
            setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        pickerTitleText = title
        titleCol.addView(title)
        topBar.addView(titleCol)

        fun topAction(label: String, onClick: () -> Unit): TextView {
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
        // PATCH 0.2.0 (Jonathan's design correction, 2026-11-21): no Clock action
        // and no clock card on the picker pane. The time renders in exactly one
        // place in the Stage by default, the companion pane's clock block, and
        // the companion's own Clock toggle is the only control for it.
        // PATCH 0.2.0 audit fix: Swap / Full / Close also leave the picker top
        // bar in fresh entry (the companion's action row hosts the single set);
        // they render here only in the after-pick state, where no companion
        // pane exists to host them. Full additionally needs a paired app.
        if (secondPick) {
            topBar.addView(topAction("Swap") { swapPanes() })
            if (!pairedPackage.isNullOrBlank()) {
                topBar.addView(topAction("Full") { expandPairedFullscreen() })
            }
            topBar.addView(topAction("Close") { finish() })
        }
        drawerContent.addView(topBar)

        // Status card: paired row + segmented pick-mode control + hint.
        // PATCH 0.2.0: the picker-pane clock block that used to head this card is
        // removed (see the note above); in the after-pick state the status bar
        // time is enough, and in the fresh state the companion clock is the one.
        val statusCard = OneUiViews.createGroupedCard(this, bottomMarginDp = 12, verticalPadDp = 8)

        val paired = pairedPackage
        // PATCH 0.2.0: the paired row renders ONLY in the after-pick state,
        // where it is the picker's header naming the first app (Jonathan's
        // approved 0.2.0 mockup). In fresh entry the companion's otherRow
        // carries the paired status and this row would duplicate it (audit
        // fix, with this one scoped exception for the approved mockup).
        if (secondPick && !paired.isNullOrBlank()) {
            val pairedRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hPad = OneUiViews.dp(this@StageActivity, 16)
                val vPad = OneUiViews.dp(this@StageActivity, 8)
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val iconDrawable = AppRepository.getAppIcon(this, paired)
            if (iconDrawable != null) {
                pairedRow.addView(ImageView(this).apply {
                    setImageDrawable(iconDrawable)
                    val sz = OneUiViews.dp(this@StageActivity, 26)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = OneUiViews.dp(this@StageActivity, 10)
                    }
                })
            }
            pairedRow.addView(TextView(this).apply {
                text = "Paired with ${AppRepository.getAppLabel(this@StageActivity, paired)} (${pickerSide.oppositeTitle})"
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
            statusCard.addView(pairedRow)
        }

        val segmentedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 14)
            val vPad = OneUiViews.dp(this@StageActivity, 6)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val segmentedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = OneUiViews.roundedBg(this@StageActivity, OneUiViews.COLOR_BG_NAVY, 20)
            val p = OneUiViews.dp(this@StageActivity, 3)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val otherBtn = TextView(this).apply {
            text = "Open on other side"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            val vp = OneUiViews.dp(this@StageActivity, 7)
            setPadding(vp, vp, vp, vp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                pickMode = StagePickMode.OPEN_OTHER_SIDE
                refreshPickModeUi()
            }
        }
        modeOtherSideBtn = otherBtn
        val replaceBtn = TextView(this).apply {
            text = "Replace this panel"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            val vp = OneUiViews.dp(this@StageActivity, 7)
            setPadding(vp, vp, vp, vp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                pickMode = StagePickMode.REPLACE_THIS_PANEL
                refreshPickModeUi()
            }
        }
        modeReplaceSelfBtn = replaceBtn
        segmentedRow.addView(otherBtn)
        segmentedRow.addView(replaceBtn)
        // PATCH 0.2.0: in the after-pick state there is no pick-mode choice (the
        // pick always completes the pair), so the segmented control is not
        // rendered; the hint line below carries the state instead.
        if (!secondPick) {
            segmentedContainer.addView(segmentedRow)
        }
        val hintTv = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                OneUiViews.dp(this@StageActivity, 4), OneUiViews.dp(this@StageActivity, 6),
                OneUiViews.dp(this@StageActivity, 4), 0
            )
        }
        hintBannerText = hintTv
        segmentedContainer.addView(hintTv)
        statusCard.addView(segmentedContainer)
        drawerContent.addView(statusCard)

        // Favorites block
        val favBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = OneUiViews.dp(this@StageActivity, 8) }
        }
        favoritesBlock = favBlock
        val favHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 6)
            setPadding(hPad, OneUiViews.dp(this@StageActivity, 2), hPad, OneUiViews.dp(this@StageActivity, 6))
        }
        favHeader.addView(TextView(this).apply {
            text = "Favorites"
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val starHint = TextView(this).apply {
            text = "Long-press app to star"
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        favStarHintText = starHint
        favHeader.addView(starHint)
        favBlock.addView(favHeader)
        val favGrid = GridLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        favoritesGrid = favGrid
        favBlock.addView(favGrid)
        // PATCH 0.2.0: the favorites block no longer goes straight into the
        // drawer; it becomes one of the arrangeable sections, placed by
        // populateSections() in the user's configured order alongside the
        // Recent apps and Frequently used rows.
        val sections = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sectionsContainer = sections
        drawerContent.addView(sections)

        // PATCH 0.2.0 (sections spec harmonization): exactly one hairline above
        // All Apps, shown only when at least one top section renders. The Stage
        // picker previously had no such divider; the standalone Picker had one
        // inside its favorites block. Both now share this single divider.
        val divider = OneUiViews.createHairlineDivider(this, horizontalInsetDp = 20).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                OneUiViews.dp(this@StageActivity, 6)
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin =
                OneUiViews.dp(this@StageActivity, 10)
        }
        sectionsDivider = divider
        drawerContent.addView(divider)

        // All apps header + grid
        val allHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 6)
            setPadding(hPad, OneUiViews.dp(this@StageActivity, 2), hPad, OneUiViews.dp(this@StageActivity, 6))
        }
        allHeader.addView(TextView(this).apply {
            text = "All apps"
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val countText = TextView(this).apply {
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        allAppsCountText = countText
        allHeader.addView(countText)
        drawerContent.addView(allHeader)
        val grid = GridLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        grid.setOnDragListener(gridDragListener)
        allAppsGrid = grid
        drawerContent.addView(grid)

        scroll.addView(drawerContent)
        paneRoot.addView(scroll)

        // Bottom search pill
        val searchWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 22)
            setPadding(hPad, OneUiViews.dp(this@StageActivity, 8), hPad, OneUiViews.dp(this@StageActivity, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // PATCH 0.2.0 design pass: the pill stays achromatic; the light
            // palette's white pill gets a quiet 1dp stroke so it never merges
            // into bright backdrop content.
            background = OneUiViews.roundedBg(
                context = this@StageActivity,
                fillColor = OneUiViews.COLOR_SEARCH_PILL,
                radiusDp = 26,
                strokeColor = OneUiViews.COLOR_SEARCH_PILL_STROKE,
                strokeWidthDp = if (OneUiViews.COLOR_SEARCH_PILL_STROKE != 0) 1 else 0
            )
            val hPad = OneUiViews.dp(this@StageActivity, 18)
            val vPad = OneUiViews.dp(this@StageActivity, 10)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val stageMagnifier = OneUiViews.MagnifierIconView(this, sizeDp = 19).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = OneUiViews.dp(this@StageActivity, 12)
        }
        searchMagnifierView = stageMagnifier
        searchPill.addView(stageMagnifier)
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (searchQuery.isNotEmpty()) {
                setText(searchQuery)
                setSelection(searchQuery.length)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val q = s?.toString()?.trim().orEmpty()
                    if (q != searchQuery) {
                        searchQuery = q
                        updateSearchAccentTint()
                        populateAppGrids()
                    }
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
        searchWrapper.addView(searchPill)
        // PATCH 0.1.7: "Show search bar" display pref
        searchWrapper.visibility =
            if (DuoPrefs.isShowSearchBar(this)) View.VISIBLE else View.GONE
        pickerSearchWrapper = searchWrapper
        paneRoot.addView(searchWrapper)

        // PATCH 0.1.8 (change 2): same covering-strip fix as PickerActivity. In the
        // spanned Stage window the ROM's bottom system strip overlays the window's
        // bottom ~140px, where this pane's search bar sits. Lift the wrapper by the
        // bottom system window inset, added to its designed bottom padding, so the
        // pill renders above the strip. Insets are returned unconsumed.
        val searchBaseBottomPad = searchWrapper.paddingBottom
        searchWrapper.setOnApplyWindowInsetsListener { v, insets ->
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
        searchWrapper.requestApplyInsets()

        refreshPickerChrome()
        populateAppGrids()
        return paneRoot
    }

    private fun refreshPickerChrome() {
        // PATCH 0.2.0: the picker clock chrome is gone (single-clock rule); this
        // now only refreshes the pick-mode control.
        refreshPickModeUi()
    }

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

    private fun refreshPickModeUi() {
        val isReplace = (pickMode == StagePickMode.REPLACE_THIS_PANEL)
        modeOtherSideBtn?.let { btn ->
            btn.background = if (!isReplace) {
                OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 17)
            } else null
            btn.setTextColor(if (!isReplace) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_SECONDARY)
            btn.setTypeface(null, if (!isReplace) Typeface.BOLD else Typeface.NORMAL)
        }
        modeReplaceSelfBtn?.let { btn ->
            btn.background = if (isReplace) {
                OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 17)
            } else null
            btn.setTextColor(if (isReplace) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_SECONDARY)
            btn.setTypeface(null, if (isReplace) Typeface.BOLD else Typeface.NORMAL)
        }
        hintBannerText?.text = when {
            // PATCH 0.2.0: after-pick state — the pick completes the pair.
            secondPick -> "Pick a second app to pair with the first"
            isReplace -> "Next app opens on THIS panel"
            pickerSide == DuoPrefs.PanelSide.LEFT -> "Next app opens on the RIGHT — tap an app"
            else -> "Next app opens on the LEFT — tap an app"
        }
    }

    private fun paneColumns(): Int {
        // PATCH 0.1.7: a forced Picker grid value wins over Auto in the Stage pane too.
        DuoPrefs.getPickerGrid(this).columns?.let { return it }
        val halfDp = resources.configuration.screenWidthDp / 2
        return when {
            halfDp >= 460 -> 4
            halfDp >= 340 -> 3
            else -> 2
        }
    }

    private fun populateAppGrids() {
        val allGrid = allAppsGrid ?: return
        val favGrid = favoritesGrid ?: return
        val cols = paneColumns()
        val alphabetical = AppRepository.getLaunchableApps(this)
        // PATCH 0.1.7: apply the persisted display order (Alphabetical or Custom).
        val orderedPkgs = DuoPrefs.applyDisplayOrder(this, alphabetical.map { it.packageName })
        val byPkg = alphabetical.associateBy { it.packageName }
        // PATCH 0.1.7 Part B: hidden apps are filtered out of the Stage picker pane too.
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

        // PATCH 0.2.0 audit cleanup: with no favorites, the grid's empty-state
        // text already gives the long-press instruction; hide the header hint
        // so it is not stated twice in the same pane.
        favStarHintText?.visibility = if (favApps.isEmpty()) View.GONE else View.VISIBLE

        favGrid.removeAllViews()
        favGrid.columnCount = cols
        if (favApps.isEmpty()) {
            favoritesBlock?.visibility = if (searchQuery.isNotBlank()) View.GONE else View.VISIBLE
            favGrid.addView(TextView(this).apply {
                text = "No favorites starred yet. Long-press any app below to add it here."
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val hPad = OneUiViews.dp(this@StageActivity, 6)
                setPadding(hPad, OneUiViews.dp(this@StageActivity, 2), hPad, OneUiViews.dp(this@StageActivity, 4))
            })
        } else {
            favoritesBlock?.visibility = View.VISIBLE
            for (app in favApps) {
                favGrid.addView(createAppGridCell(app, cols))
            }
        }

        allAppsCountText?.text = "${filtered.size} apps"
        allGrid.removeAllViews()
        allGrid.columnCount = cols
        // PATCH 0.1.7: long-press drag reorder is live only in Custom sort mode with no
        // active search filter (filtered list == full ordered list in that state).
        val dragEnabled = DuoPrefs.getSortOrder(this) == DuoPrefs.SortOrder.CUSTOM &&
            searchQuery.isBlank()
        for (app in filtered) {
            allGrid.addView(createAppGridCell(app, cols, draggable = dragEnabled))
        }

        populateSections(byPkg, hiddenPkgs)
    }

    /**
     * PATCH 0.2.0: (re)builds the arrangeable sections container in the user's
     * configured order. Favorites reuses the block built by buildPickerPane;
     * Recent / Frequently used come from PickerSections (live usage stats).
     * While a search query is active only Favorites shows (with its existing
     * filtered behavior); the usage rows are browse affordances and hide.
     */
    private fun populateSections(
        byPkg: Map<String, AppRepository.InstalledApp>,
        hiddenPkgs: Set<String>
    ) {
        val container = sectionsContainer ?: return
        val favBlock = favoritesBlock ?: return
        (favBlock.parent as? ViewGroup)?.removeView(favBlock)
        container.removeAllViews()
        val searching = searchQuery.isNotBlank()
        var anyRendered = false
        for (section in DuoPrefs.getSectionOrder(this)) {
            if (!DuoPrefs.isSectionVisible(this, section)) continue
            when (section) {
                DuoPrefs.PickerSection.FAVORITES -> {
                    container.addView(favBlock)
                    if (favBlock.visibility == View.VISIBLE) anyRendered = true
                }
                DuoPrefs.PickerSection.RECENT, DuoPrefs.PickerSection.FREQUENT -> {
                    if (searching) continue
                    PickerSections.buildUsageSection(
                        context = this,
                        section = section,
                        appsByPackage = byPkg,
                        hiddenPackages = hiddenPkgs,
                        viewportWidthDp = resources.configuration.screenWidthDp / 2,
                        gridColumns = paneColumns(),
                        onPick = { app -> performAppPick(app.packageName) },
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
                        container.addView(it)
                        anyRendered = true
                    }
                }
            }
        }
        sectionsDivider?.visibility = if (anyRendered) View.VISIBLE else View.GONE
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
                addView(TextView(this@StageActivity).apply {
                    text = app.label
                    setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                    // PATCH 0.1.7 calibration: label text scales slightly with App size.
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, DuoPrefs.getAppSize(this@StageActivity).labelSp)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, OneUiViews.dp(this@StageActivity, 6), 0, 0)
                })
            }
            setOnClickListener { performAppPick(app.packageName) }
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
                    val nowFav = DuoPrefs.toggleFavorite(this@StageActivity, app.packageName)
                    Toast.makeText(
                        this@StageActivity,
                        if (nowFav) "Added ${app.label} to Favorites" else "Removed ${app.label} from Favorites",
                        Toast.LENGTH_SHORT
                    ).show()
                    populateAppGrids()
                    true
                }
            }
        }
    }

    /**
     * PATCH 0.1.7 Part B: the Stage picker-pane cell icon with its notification badge
     * overlay (same rules as PickerActivity: Dot or Number pill, top-right, only when
     * badges are on, access is granted and connected, and the count is real).
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
                                context = this@StageActivity,
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
                                context = this@StageActivity,
                                fillColor = OneUiViews.COLOR_ONEUI_BLUE,
                                radiusDp = 9
                            )
                            val hPad = OneUiViews.dp(this@StageActivity, 5)
                            setPadding(hPad, 0, hPad, 0)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                OneUiViews.dp(this@StageActivity, 18),
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
    // PATCH 0.1.7: Custom-order drag reorder (Stage picker pane, All Apps grid)
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
        val grid = allAppsGrid ?: return -1
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
        val grid = allAppsGrid ?: return
        if (index == dragHighlightIndex) return
        clearDropHighlight()
        dragHighlightIndex = index
        if (index >= 0 && index < grid.childCount) {
            grid.getChildAt(index).background =
                OneUiViews.roundedBg(this, OneUiViews.COLOR_SUBTLE_SURFACE, 18)
        }
    }

    private fun clearDropHighlight() {
        val grid = allAppsGrid ?: return
        if (dragHighlightIndex >= 0 && dragHighlightIndex < grid.childCount) {
            grid.getChildAt(dragHighlightIndex).background = null
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
            side = pickerSide.name,
            targetDesc = pkg,
            flagsHex = "0x00000000",
            optionsInfo = "from=$from,to=$to",
            result = "OK (custom order updated via drag in StageActivity picker pane)"
        )
        populateAppGrids()
    }

    private fun performAppPick(pkg: String) {
        // PATCH 0.2.0: in the after-pick state a pick completes the pair.
        if (secondPick) {
            performSecondPick(pkg)
            return
        }
        val replaceThisPanel = (pickMode == StagePickMode.REPLACE_THIS_PANEL)
        val targetSide = if (replaceThisPanel) {
            DuoPrefs.effectiveSide(this, pickerSide)
        } else {
            DuoPrefs.effectiveSide(this, pickerSide.opposite())
        }
        val openedDisplaySide = if (replaceThisPanel) pickerSide else pickerSide.opposite()
        SoloLauncher.recordActivePanelApp(this, openedDisplaySide, pkg)
        DuoPrefs.recordLaunchLog(
            context = this,
            flowOrStrategy = "STAGE_PICK",
            side = pickerSide.name,
            targetDesc = pkg,
            flagsHex = "0x00000000",
            optionsInfo = "mode=$pickMode,target=${targetSide.name}",
            result = "Launching via SoloLauncher.launchFromPicker from Stage; Stage finishes"
        )
        val launched = SoloLauncher.launchFromPicker(
            activity = this,
            pkg = pkg,
            side = targetSide,
            replaceMySide = replaceThisPanel
        )
        if (launched) {
            pairedPackage = pkg
            // PATCH 0.2.0 (Jonathan's confirmed flow, 2026-11-21): a default
            // Replace-this-panel pick from the fresh-entry Stage does not end
            // the flow. The picked app opens on the list's side for real, then
            // the Stage re-forms over it with the list moved to the other side
            // so the second app can be picked immediately. Open-on-other-side
            // picks keep the pre-0.2.0 behavior (Stage closes).
            if (replaceThisPanel && !overApp && SoloLauncher.shouldEngageSplit(this)) {
                reformAfterFirstPick(pkg, openedDisplaySide)
            }
            finish()
        }
    }

    /**
     * PATCH 0.2.0: re-enters the Stage in the after-pick state over the app
     * that was just launched: its side comes from [openedDisplaySide], the
     * first app is the paired package, and the picker lands on the free side.
     * Any failure falls back to today's behavior (Stage closed, picked app in
     * front) and is logged as STAGE_REFORM_FALLBACK.
     */
    private fun reformAfterFirstPick(pkg: String, openedDisplaySide: DuoPrefs.PanelSide) {
        try {
            val reformIntent = ShortcutHelper.buildStageIntent(
                context = this,
                side = openedDisplaySide,
                overApp = true,
                pairedPackage = pkg,
                secondPick = true
            )
            startActivity(reformIntent)
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_REFORM",
                side = openedDisplaySide.name,
                targetDesc = pkg,
                flagsHex = "0x00000000",
                optionsInfo = "secondPick=true,appSide=${openedDisplaySide.name}",
                result = "OK (Stage re-forming with the list on ${openedDisplaySide.opposite().name})"
            )
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_REFORM_FALLBACK",
                side = openedDisplaySide.name,
                targetDesc = pkg,
                flagsHex = "0x00000000",
                optionsInfo = "secondPick=true",
                result = "FAIL: ${t.javaClass.simpleName}: ${t.message ?: "no message"} (Stage stays closed; picked app in front)"
            )
        }
    }

    /**
     * PATCH 0.2.0: the second pick. Launches the picked app paired with the
     * first app through the existing pair machinery (primary = the LEFT panel's
     * app, secondary = the RIGHT panel's app, per the persisted panel records)
     * and closes the Stage, leaving the real pair in split.
     */
    private fun performSecondPick(pkg: String) {
        val firstApp = pairedPackage
        if (firstApp.isNullOrBlank()) {
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_SECOND_PICK",
                side = pickerSide.name,
                targetDesc = pkg,
                flagsHex = "0x00000000",
                optionsInfo = "secondPick=true",
                result = "FAIL: no first app recorded; closing Stage"
            )
            finish()
            return
        }
        if (pkg == firstApp) {
            // Picking the first app again would pair it with itself; just
            // return to it (same as dismissing the Stage).
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_SECOND_PICK",
                side = pickerSide.name,
                targetDesc = pkg,
                flagsHex = "0x00000000",
                optionsInfo = "secondPick=true,sameAsFirst=true",
                result = "OK (picked the first app again; returning to it unpaired)"
            )
            finish()
            return
        }
        SoloLauncher.recordActivePanelApp(this, pickerSide, pkg)
        val leftApp = if (appSide == DuoPrefs.PanelSide.LEFT) firstApp else pkg
        val rightApp = if (appSide == DuoPrefs.PanelSide.LEFT) pkg else firstApp
        DuoPrefs.recordLaunchLog(
            context = this,
            flowOrStrategy = "STAGE_SECOND_PICK",
            side = pickerSide.name,
            targetDesc = pkg,
            flagsHex = "0x00000000",
            optionsInfo = "secondPick=true,left=$leftApp,right=$rightApp",
            result = "Launching the pair via SoloLauncher.launchPairShortcut; Stage finishes"
        )
        SoloLauncher.launchPairShortcut(
            context = this,
            primaryPkg = leftApp,
            secondaryPkg = rightApp
        )
        finish()
    }

    // ------------------------------------------------------------------ companion pane

    private fun companionSide(): DuoPrefs.PanelSide =
        if (overApp) appSide.opposite() else pickerSide.opposite()

    private fun buildCompanionPane(): View {
        val side = companionSide()
        val outerMargin = OneUiViews.screenMarginPx(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        val rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(outerMargin, OneUiViews.dp(this@StageActivity, 18), outerMargin, outerMargin)
        }
        // PATCH 0.1.9: add the status bar top inset to the companion pane header container so the
        // companion card clears the status bar cleanly even on compact/split heights.
        OneUiViews.applyTopStatusBarInsetPadding(rootCol)
        val cardBlock = OneUiViews.createGroupedCard(this, bottomMarginDp = 14, verticalPadDp = 10)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 20)
            val vPad = OneUiViews.dp(this@StageActivity, 12)
            setPadding(hPad, vPad, hPad, vPad)
        }
        topRow.addView(
            OneUiViews.PanelGraphicView(
                context = this,
                highlightLeft = side == DuoPrefs.PanelSide.LEFT,
                highlightRight = side == DuoPrefs.PanelSide.RIGHT,
                sizeDp = 38
            )
        )
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = OneUiViews.dp(this@StageActivity, 14)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = side.title
            setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "Companion panel"
            setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, OneUiViews.dp(this@StageActivity, 2), 0, 0)
        })
        topRow.addView(titleCol)
        topRow.addView(
            OneUiViews.createTextAction(context = this, label = "DuoPanel", activeOrLink = true) {
                startActivity(Intent(this, MainActivity::class.java))
            }
        )
        cardBlock.addView(topRow)
        cardBlock.addView(OneUiViews.createHairlineDivider(this))

        val clockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = OneUiViews.dp(this@StageActivity, 20)
            val vPad = OneUiViews.dp(this@StageActivity, 16)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val clock = TextClock(this@StageActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val dateText = TextView(this@StageActivity).apply {
                text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, OneUiViews.dp(this@StageActivity, 2), 0, 0)
            }
            addView(clock)
            addView(dateText)
        }
        companionClockBlock = clockBlock
        cardBlock.addView(clockBlock)
        val divider = OneUiViews.createHairlineDivider(this)
        companionClockDivider = divider
        cardBlock.addView(divider)

        val otherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 20)
            val vPad = OneUiViews.dp(this@StageActivity, 14)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val paired = pairedPackage
        if (!paired.isNullOrBlank()) {
            val iconDrawable = AppRepository.getAppIcon(this, paired)
            if (iconDrawable != null) {
                otherRow.addView(ImageView(this).apply {
                    setImageDrawable(iconDrawable)
                    val sz = OneUiViews.dp(this@StageActivity, 32)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = OneUiViews.dp(this@StageActivity, 14)
                    }
                })
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = AppRepository.getAppLabel(this@StageActivity, paired)
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            textCol.addView(TextView(this).apply {
                // PATCH 0.2.0 audit fix: in fresh entry the other panel holds the
                // app LIST, not this app, so "Active app" mislabels it; the app
                // is the last paired one. Over-app keeps the active wording.
                text = if (overApp) "${side.oppositeTitle} • Active app" else "Last paired app"
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, OneUiViews.dp(this@StageActivity, 2), 0, 0)
            })
            otherRow.addView(textCol)
        } else {
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = if (overApp) "App running" else "App list active"
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            textCol.addView(TextView(this).apply {
                text = side.oppositeTitle
                setTextColor(OneUiViews.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, OneUiViews.dp(this@StageActivity, 2), 0, 0)
            })
            otherRow.addView(textCol)
        }
        // PATCH 0.2.0 audit fix: in fresh entry with nothing paired, this row
        // only echoes the app list sitting on the other pane ("App list active /
        // Left Panel"), so it and its divider are hidden. It renders when an
        // app is paired, and always in the over-app state (where it is the
        // only status line for the running app).
        val showOtherRow = overApp || !paired.isNullOrBlank()
        if (showOtherRow) {
            cardBlock.addView(otherRow)
            cardBlock.addView(OneUiViews.createHairlineDivider(this))
        }

        // PATCH 0.2.0 audit fix: "Pick app" in fresh entry literally duplicates
        // Swap (it calls swapPanes when the drawer is already open beside it),
        // so it renders only in the over-app state, where it is the one
        // control that opens the drawer.
        if (overApp) {
            val pickAppRow = OneUiViews.createSettingsRow(
                context = this,
                title = "Pick app",
                subtitle = "Open the app drawer on this panel",
                titleAccent = true
            ) {
                companionPickApp()
            }
            cardBlock.addView(pickAppRow)
            cardBlock.addView(OneUiViews.createHairlineDivider(this))
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = OneUiViews.dp(this@StageActivity, 12)
            val vPad = OneUiViews.dp(this@StageActivity, 6)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun addQuietAction(title: String, action: () -> Unit): TextView {
            val btn = TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                setTextColor(OneUiViews.COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                val vPad = OneUiViews.dp(this@StageActivity, 10)
                setPadding(0, vPad, 0, vPad)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            actionsRow.addView(btn)
            return btn
        }
        addQuietAction("Swap") { swapPanes() }
        // PATCH 0.2.0 audit fix: Full with nothing paired is a dead-end toast;
        // render it only when there is a paired app to expand.
        if (!paired.isNullOrBlank()) {
            addQuietAction("Full") { expandPairedFullscreen() }
        }
        val clockAction = addQuietAction("Clock") {
            companionClockExpanded = !companionClockExpanded
            refreshCompanionClock()
        }
        companionClockActionBtn = clockAction
        addQuietAction("Close") { finish() }
        cardBlock.addView(actionsRow)

        rootCol.addView(cardBlock)
        scroll.addView(rootCol)
        refreshCompanionClock()
        return scroll
    }

    private fun refreshCompanionClock() {
        companionClockBlock?.visibility = if (companionClockExpanded) View.VISIBLE else View.GONE
        companionClockDivider?.visibility = if (companionClockExpanded) View.VISIBLE else View.GONE
        companionClockActionBtn?.setTextColor(
            if (companionClockExpanded) OneUiViews.COLOR_ONEUI_BLUE else OneUiViews.COLOR_TEXT_PRIMARY
        )
    }

    // ------------------------------------------------------------------ stage actions

    private fun swapPanes() {
        if (secondPick) {
            // PATCH 0.2.0: in the after-pick state Swap exchanges the frame side
            // and the list side; the first app stays the paired app.
            appSide = appSide.opposite()
            pickerSide = appSide.opposite()
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_SWAP",
                side = appSide.name,
                targetDesc = "StageActivity",
                flagsHex = "0x00000000",
                optionsInfo = "secondPick=true",
                result = "OK (frame and list swapped inside the after-pick Stage)"
            )
            buildPanes()
            return
        }
        if (overApp) {
            appSide = appSide.opposite()
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_SWAP",
                side = appSide.name,
                targetDesc = "StageActivity",
                flagsHex = "0x00000000",
                optionsInfo = "overApp=true",
                result = "OK (companion moved to ${appSide.opposite().name} inside the Stage)"
            )
        } else {
            pickerSide = pickerSide.opposite()
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_SWAP",
                side = pickerSide.name,
                targetDesc = "StageActivity",
                flagsHex = "0x00000000",
                optionsInfo = "overApp=false",
                result = "OK (picker and companion panes swapped inside the Stage)"
            )
        }
        buildPanes()
    }

    private fun companionPickApp() {
        if (overApp) {
            // The picker replaces the screenshot half: Stage becomes the two-pane layout with
            // the picker on the entry side, and the captured frame is released.
            overApp = false
            pickerSide = entryRequestedSide
            StageBackdrop.releaseBitmap()
            DuoPrefs.recordLaunchLog(
                context = this,
                flowOrStrategy = "STAGE_PICK_APP",
                side = pickerSide.name,
                targetDesc = "StageActivity(pickerPane)",
                flagsHex = "0x00000000",
                optionsInfo = "fromOverApp=true",
                result = "OK (picker pane opened on ${pickerSide.name} inside the Stage)"
            )
            buildPanes()
        } else {
            // Summon the picker on the companion's own panel: swap the panes in-window.
            swapPanes()
        }
    }

    private fun expandPairedFullscreen() {
        val paired = pairedPackage
        if (paired.isNullOrBlank()) {
            Toast.makeText(this, "No paired app to expand", Toast.LENGTH_SHORT).show()
            return
        }
        val expanded = SoloLauncher.expandPairedAppFullscreen(this, paired)
        if (expanded) {
            finish()
        }
    }
}
