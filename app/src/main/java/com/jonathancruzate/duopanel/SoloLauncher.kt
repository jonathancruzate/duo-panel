package com.jonathancruzate.duopanel

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * SoloLauncher — Shared multi-window launch engine for DuoPanel.
 *
 * Implements TECH-NOTES.md, SPEC-ADDENDUM.md, and SPEC.md:
 * 1. Explicit `FLAG_LAUNCH_ADJACENT_TO_RECENT = 0x00100000` constant alongside public
 *    `Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT` (0x00001000), logging both and exact hex bitmasks.
 * 2. Primary split mechanism via public Intent flags:
 *    `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_LAUNCH_ADJACENT_TO_RECENT`
 * 3. Guarded hidden-API reflection fallback for `ActivityOptions.setLaunchWindowingMode(int)`
 *    (SPLIT_SCREEN_PRIMARY=3, SPLIT_SCREEN_SECONDARY=4, FREEFORM=5). Reflection failure silently
 *    falls back to pure flag-based launch and never crashes.
 * 4. Public `ActivityOptions.setLaunchBounds(Rect)` for Strategy C (freeform half), with windowing
 *    mode left default per TECH-NOTES.md note 4.
 * 5. SPEC-ADDENDUM Left & Right shortcut flows:
 *    - `launchPickedApp(activity, pkg, side)`:
 *      - RIGHT flow: Picker is open; launches `pkg` adjacent so Picker shrinks to LEFT and app opens RIGHT.
 *        Picker STAYS ALIVE on the left.
 *      - LEFT flow: Picker launches `pkg` normally (primary task), then triggers `reanchorPicker(activity, side)`
 *        via `AnchorActivity` so a fresh `PickerActivity` opens adjacent on the RIGHT and old Picker finishes.
 *      - In-split / Replace-self flow: When Picker is already in multi-window (or user selected
 *        "Replace this panel"), launches `pkg` WITHOUT the adjacent flag (`NEW_TASK | MULTIPLE_TASK`)
 *        so it takes Picker's own panel slot, then Picker finishes.
 */
object SoloLauncher {

    /**
     * TECH-NOTES.md note 3: FLAG_ACTIVITY_LAUNCH_ADJACENT_TO_RECENT is absent from android-36
     * Intent stubs. Defined explicitly with its AOSP constant value 0x00100000.
     */
    const val FLAG_LAUNCH_ADJACENT_TO_RECENT: Int = 0x00100000

    /**
     * Public Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT constant in android-36 stubs (0x00001000).
     */
    const val PUBLIC_FLAG_LAUNCH_ADJACENT: Int = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

    /**
     * Compile-time / runtime check whether Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT is distinct
     * from 0x00100000, logged in Launch Lab per TECH-NOTES.md note 3.
     */
    val IS_PUBLIC_ADJACENT_DISTINCT: Boolean =
        (PUBLIC_FLAG_LAUNCH_ADJACENT != FLAG_LAUNCH_ADJACENT_TO_RECENT)

    /**
     * AOSP WindowConfiguration windowing mode constants for guarded reflection fallback.
     * Note: hidden-API reflection may be blocked on some builds; that is why this is a guarded
     * optional enhancement rather than the primary mechanism.
     */
    const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY: Int = 3
    const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY: Int = 4
    const val WINDOWING_MODE_FREEFORM: Int = 5

    private const val ADJACENT_STEP_DELAY_MS: Long = 110L
    const val FORMATION_CHECK_DELAY_MS: Long = 1200L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val activePanelApps = mutableMapOf<DuoPrefs.PanelSide, String>()
    private val activePanelListeners = mutableSetOf<() -> Unit>()

    private val livePickers =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<PickerActivity, Boolean>()))
    private val liveCompanions =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<CompanionPanelActivity, Boolean>()))

    @Volatile
    private var latestPickerRef: WeakReference<PickerActivity>? = null

    @Volatile
    private var latestCompanionRef: WeakReference<CompanionPanelActivity>? = null

    enum class Phase2Path(val logName: String) {
        RELAY("relayAnchor"),
        DIRECT("direct")
    }

    private class ActiveFormationState(
        val companionDisplaySide: DuoPrefs.PanelSide,
        val effectiveCompanionSide: DuoPrefs.PanelSide,
        val pairedPackage: String?,
        @Volatile var lastPathUsed: Phase2Path,
        @Volatile var retryAttempted: Boolean = false
    )

    @Volatile
    private var pendingFormationCheckRunnable: Runnable? = null

    @Volatile
    private var activeFormationState: ActiveFormationState? = null

    fun registerPickerInstance(activity: PickerActivity) {
        livePickers.add(activity)
        latestPickerRef = WeakReference(activity)
    }

    fun unregisterPickerInstance(activity: PickerActivity) {
        livePickers.remove(activity)
        if (latestPickerRef?.get() === activity) {
            val remaining = synchronized(livePickers) {
                livePickers.lastOrNull { !it.isFinishing && !it.isDestroyed }
            }
            latestPickerRef = if (remaining != null) WeakReference(remaining) else null
        }
    }

    fun registerCompanionInstance(activity: CompanionPanelActivity) {
        liveCompanions.add(activity)
        latestCompanionRef = WeakReference(activity)
    }

    fun unregisterCompanionInstance(activity: CompanionPanelActivity) {
        liveCompanions.remove(activity)
        if (latestCompanionRef?.get() === activity) {
            val remaining = synchronized(liveCompanions) {
                liveCompanions.lastOrNull { !it.isFinishing && !it.isDestroyed }
            }
            latestCompanionRef = if (remaining != null) WeakReference(remaining) else null
        }
    }

    fun getActivePickerInstance(): PickerActivity? {
        val latest = latestPickerRef?.get()
        if (latest != null && !latest.isFinishing && !latest.isDestroyed) {
            return latest
        }
        return synchronized(livePickers) {
            livePickers.lastOrNull { !it.isFinishing && !it.isDestroyed }
        }
    }

    fun getActiveCompanionInstance(): CompanionPanelActivity? {
        val latest = latestCompanionRef?.get()
        if (latest != null && !latest.isFinishing && !latest.isDestroyed) {
            return latest
        }
        return synchronized(liveCompanions) {
            liveCompanions.lastOrNull { !it.isFinishing && !it.isDestroyed }
        }
    }

    /**
     * Finishes any existing CompanionPanelActivity instances (such as a fullscreen instance from an
     * earlier launch) so that when Phase 2 launches the Companion with `adjacentLaunchFlags()`,
     * exactly one Companion exists and docks inside the split stage.
     */
    fun finishExistingCompanions(onlyIfNotConfined: Boolean = false): Int {
        val snapshot = synchronized(liveCompanions) { liveCompanions.toList() }
        var closed = 0
        for (comp in snapshot) {
            if (comp.isFinishing || comp.isDestroyed) continue
            val shouldClose = if (onlyIfNotConfined) {
                !comp.isInMultiWindowMode || !isActivityConfinedToSplitStage(comp)
            } else {
                true
            }
            if (shouldClose) {
                liveCompanions.remove(comp)
                if (latestCompanionRef?.get() === comp) {
                    latestCompanionRef = null
                }
                try {
                    comp.finishAndRemoveTask()
                } catch (_: Throwable) {
                    try {
                        comp.finish()
                    } catch (_: Throwable) {
                    }
                }
                closed++
            }
        }
        return closed
    }

    /**
     * Checks whether [activity] is in multi-window mode AND confined to roughly one half of the
     * current display (30%..65% of display width in landscape/wide posture, or 30%..65% of width
     * or height in portrait top/bottom posture).
     */
    fun isActivityConfinedToSplitStage(activity: Activity): Boolean {
        if (!activity.isInMultiWindowMode) return false
        val decor = activity.window?.decorView
        val viewWidth = decor?.width ?: 0
        val viewHeight = decor?.height ?: 0
        val wm = activity.windowManager
        val currentBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                wm.currentWindowMetrics.bounds
            } catch (_: Throwable) {
                Rect(0, 0, viewWidth, viewHeight)
            }
        } else {
            Rect(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
        }
        val maxBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                wm.maximumWindowMetrics.bounds
            } catch (_: Throwable) {
                currentBounds
            }
        } else {
            val pt = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(pt)
            Rect(0, 0, pt.x, pt.y)
        }
        val displayW = maxOf(currentBounds.width(), maxBounds.width())
        val displayH = maxOf(currentBounds.height(), maxBounds.height())
        if (displayW <= 0 || displayH <= 0) return false

        val measuredW = when {
            viewWidth > 0 && currentBounds.width() > 0 -> maxOf(viewWidth, currentBounds.width())
            viewWidth > 0 -> viewWidth
            else -> currentBounds.width()
        }
        val measuredH = when {
            viewHeight > 0 && currentBounds.height() > 0 -> maxOf(viewHeight, currentBounds.height())
            viewHeight > 0 -> viewHeight
            else -> currentBounds.height()
        }
        val minW = (displayW * 0.30f).toInt()
        val maxW = (displayW * 0.65f).toInt()
        val widthConfined = measuredW in minW..maxW
        if (widthConfined) return true

        val isPortraitDisplay = displayH > displayW
        if (isPortraitDisplay) {
            val minH = (displayH * 0.30f).toInt()
            val maxH = (displayH * 0.65f).toInt()
            if (measuredH in minH..maxH) return true
        }
        return false
    }

    /**
     * Returns the window frame of [activity] in `[left,top][right,bottom]` format using its root/decor
     * view location on screen and currentWindowMetrics bounds.
     */
    fun getActivityWindowFrameString(activity: Activity?): String {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return "none"
        }
        val decor = activity.window?.decorView
        val loc = IntArray(2)
        val hasDecor = if (decor != null && decor.width > 0 && decor.height > 0) {
            decor.getLocationOnScreen(loc)
            true
        } else {
            false
        }
        val decorRect = if (hasDecor && decor != null) {
            Rect(loc[0], loc[1], loc[0] + decor.width, loc[1] + decor.height)
        } else {
            null
        }
        val metricsRect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.windowManager.currentWindowMetrics.bounds
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val chosen = when {
            decorRect != null && metricsRect != null -> {
                if (decorRect.left == 0 && decorRect.top == 0 && (metricsRect.left > 0 || metricsRect.top > 0)) {
                    metricsRect
                } else {
                    decorRect
                }
            }
            decorRect != null -> decorRect
            metricsRect != null -> metricsRect
            else -> null
        }
        return if (chosen != null) {
            "[${chosen.left},${chosen.top}][${chosen.right},${chosen.bottom}]"
        } else {
            "unknown"
        }
    }

    private fun getDisplayBoundsString(activity: Activity?, context: Context): String {
        return try {
            val wm = activity?.windowManager
                ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                "[${b.left},${b.top}][${b.right},${b.bottom}]"
            } else {
                val pt = Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(pt)
                "[0,0][${pt.x},${pt.y}]"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    /**
     * PATCH 0.1.3 (Part C): Schedules a `FORMATION_CHECK` log entry ~1.2s after formation completes,
     * recording both PickerActivity's and CompanionPanelActivity's window frames and multi-window state.
     */
    fun scheduleFormationCheck(
        context: Context,
        formationSide: String,
        delayMs: Long = FORMATION_CHECK_DELAY_MS
    ) {
        val appCtx = context.applicationContext
        pendingFormationCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            pendingFormationCheckRunnable = null
            recordFormationCheck(appCtx, formationSide)
        }
        pendingFormationCheckRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun recordFormationCheck(context: Context, formationSide: String) {
        val picker = getActivePickerInstance()
        val companion = getActiveCompanionInstance()

        val pickerAlive = picker != null && !picker.isFinishing && !picker.isDestroyed
        val pickerMw = if (pickerAlive) picker!!.isInMultiWindowMode else false
        val pickerFrame = getActivityWindowFrameString(picker)
        val pickerConfined = if (pickerAlive) isActivityConfinedToSplitStage(picker!!) else false

        val companionAlive = companion != null && !companion.isFinishing && !companion.isDestroyed
        val companionMw = if (companionAlive) companion!!.isInMultiWindowMode else false
        val companionFrame = getActivityWindowFrameString(companion)
        val companionConfined =
            if (companionAlive) isActivityConfinedToSplitStage(companion!!) else false

        val displayBounds = getDisplayBoundsString(picker ?: companion, context)
        val bothDocked = pickerAlive && pickerMw && pickerConfined &&
            companionAlive && companionMw && companionConfined

        DuoPrefs.recordLaunchLog(
            context = context,
            flowOrStrategy = "FORMATION_CHECK",
            side = formationSide,
            targetDesc = "PickerActivity + CompanionPanelActivity",
            flagsHex = "0x00000000",
            optionsInfo = "picker(inMultiWindow=$pickerMw,frame=$pickerFrame,confined=$pickerConfined); companion(inMultiWindow=$companionMw,frame=$companionFrame,confined=$companionConfined); display=$displayBounds",
            result = "bothDocked=$bothDocked; picker=[inMultiWindow=$pickerMw,frame=$pickerFrame]; companion=[inMultiWindow=$companionMw,frame=$companionFrame]"
        )

        val formationState = activeFormationState ?: return
        val pickerDocked = pickerAlive && pickerMw && pickerConfined
        val companionDocked = companionAlive && companionMw && companionConfined

        if (bothDocked || formationState.retryAttempted) {
            activeFormationState = null
            return
        }

        if (pickerDocked && !companionDocked && picker != null) {
            formationState.retryAttempted = true
            val previousPath = formationState.lastPathUsed
            val retryPath = if (previousPath == Phase2Path.RELAY) {
                Phase2Path.DIRECT
            } else {
                Phase2Path.RELAY
            }
            val retryFlags = adjacentLaunchFlags()
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "FORMATION_RETRY",
                side = formationState.companionDisplaySide.name,
                targetDesc = "CompanionPanelActivity(side=${formationState.companionDisplaySide.name},eff=${formationState.effectiveCompanionSide.name},paired=${formationState.pairedPackage ?: "none"})",
                flagsHex = describeFlags(retryFlags),
                optionsInfo = "path=${retryPath.logName},previousPath=${previousPath.logName},picker(inMultiWindow=$pickerMw,frame=$pickerFrame,confined=$pickerConfined),companion(inMultiWindow=$companionMw,frame=$companionFrame,confined=$companionConfined)",
                result = "Retrying Phase 2 once via ${retryPath.logName} after FORMATION_CHECK(bothDocked=false)"
            )
            if (retryPath == Phase2Path.DIRECT) {
                formationState.lastPathUsed = Phase2Path.DIRECT
                dockCompanionAdjacent(
                    callerActivity = picker,
                    companionDisplaySide = formationState.companionDisplaySide,
                    effectiveCompanionSide = formationState.effectiveCompanionSide,
                    pairedPackage = formationState.pairedPackage,
                    logFlowName = "PANEL_PAIR_PHASE2",
                    pathTag = "direct"
                )
            } else {
                dockCompanionViaAnchorRelay(
                    callerActivity = picker,
                    companionDisplaySide = formationState.companionDisplaySide,
                    effectiveCompanionSide = formationState.effectiveCompanionSide,
                    pairedPackage = formationState.pairedPackage,
                    isRetry = true
                )
            }
        } else {
            activeFormationState = null
        }
    }

    // PATCH 0.1.10: the records are written through to DuoPrefs. The in-memory map alone lost
    // them whenever Android reaped the process after a pick finished the Stage, so the next
    // entry's companion pane fell back to generic state text instead of naming the app.
    fun recordActivePanelApp(context: Context, panelSide: DuoPrefs.PanelSide, packageName: String?) {
        synchronized(activePanelApps) {
            if (packageName.isNullOrBlank()) {
                activePanelApps.remove(panelSide)
            } else {
                activePanelApps[panelSide] = packageName
            }
        }
        DuoPrefs.setPersistedActivePanelApp(context, panelSide, packageName)
        val listeners = synchronized(activePanelListeners) { activePanelListeners.toList() }
        for (l in listeners) {
            l.invoke()
        }
    }

    fun getActivePanelApp(context: Context, panelSide: DuoPrefs.PanelSide): String? {
        synchronized(activePanelApps) { activePanelApps[panelSide] }?.let { return it }
        // Cold process: fall back to the persisted record and warm the in-memory map.
        val persisted = DuoPrefs.getPersistedActivePanelApp(context, panelSide) ?: return null
        synchronized(activePanelApps) { activePanelApps[panelSide] = persisted }
        return persisted
    }

    fun clearActivePanelApps(context: Context) {
        synchronized(activePanelApps) { activePanelApps.clear() }
        DuoPrefs.clearPersistedActivePanelApps(context)
        val listeners = synchronized(activePanelListeners) { activePanelListeners.toList() }
        for (l in listeners) {
            l.invoke()
        }
    }

    fun registerActivePanelListener(listener: () -> Unit) {
        synchronized(activePanelListeners) { activePanelListeners.add(listener) }
    }

    fun unregisterActivePanelListener(listener: () -> Unit) {
        synchronized(activePanelListeners) { activePanelListeners.remove(listener) }
    }

    fun buildCompanionIntent(
        context: Context,
        companionSide: DuoPrefs.PanelSide,
        pairedPackage: String? = null
    ): Intent {
        return Intent(context, CompanionPanelActivity::class.java).apply {
            putExtra(ShortcutHelper.EXTRA_SIDE, companionSide.name)
            if (!pairedPackage.isNullOrBlank()) {
                putExtra(ShortcutHelper.EXTRA_PAIRED_PKG, pairedPackage)
            }
        }
    }

    fun adjacentLaunchFlags(): Int {
        return Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
            PUBLIC_FLAG_LAUNCH_ADJACENT or
            FLAG_LAUNCH_ADJACENT_TO_RECENT
    }

    fun replaceSlotFlags(): Int {
        // Without adjacent flag so the newly launched app replaces the Picker's own split slot
        return Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    }

    fun normalPrimaryFlags(): Int {
        return Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    /**
     * Formats exact flag bits used per attempt so Launch Lab shows what was actually sent
     * on Galaxy Z Fold8 and S24+ hardware.
     */
    fun describeFlags(flags: Int): String {
        val hex = String.format(Locale.US, "0x%08X", flags)
        val tokens = mutableListOf<String>()
        if ((flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0) tokens.add("NEW_TASK")
        if ((flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0) tokens.add("MULTIPLE_TASK")
        if ((flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) tokens.add("CLEAR_TOP")
        if ((flags and PUBLIC_FLAG_LAUNCH_ADJACENT) != 0) {
            tokens.add(String.format(Locale.US, "LAUNCH_ADJACENT(0x%X)", PUBLIC_FLAG_LAUNCH_ADJACENT))
        }
        if ((flags and FLAG_LAUNCH_ADJACENT_TO_RECENT) != 0) {
            tokens.add(
                String.format(
                    Locale.US,
                    "LAUNCH_ADJACENT_TO_RECENT(0x%X,distinct=%b)",
                    FLAG_LAUNCH_ADJACENT_TO_RECENT,
                    IS_PUBLIC_ADJACENT_DISTINCT
                )
            )
        }
        return "$hex [${tokens.joinToString("|")}]"
    }

    /**
     * Guarded reflection fallback for ActivityOptions.setLaunchWindowingMode(int).
     * Never crashes; if hidden-API restriction blocks reflection, returns (null, status)
     * so the caller falls through cleanly to pure Intent-flag launch.
     */
    fun tryCreateWindowingModeBundle(windowingMode: Int): Pair<Bundle?, String> {
        return try {
            val opts = ActivityOptions.makeBasic()
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            method.invoke(opts, windowingMode)
            Pair(opts.toBundle(), "reflection:ok(windowingMode=$windowingMode)")
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Pair(null, "reflection:fallback(${cause.javaClass.simpleName})")
        }
    }

    /**
     * Width-based posture check per SPEC.md lines 29-30 & 45:
     * Split engages on wide windows (>= 600dp, e.g. unfolded Galaxy Z Fold8) when SplitMode=AUTO,
     * always when SplitMode=ALWAYS (for Galaxy S24+ split testing), and never when SplitMode=OFF.
     * TODO(WindowManager): Optional Jetpack WindowManager FoldingFeature posture hook can be layered
     * here in a future online build; width >= 600dp requires zero external AndroidX dependencies.
     */
    fun isWideScreen(context: Context): Boolean {
        val cfg = context.resources.configuration
        return cfg.screenWidthDp >= 600 || cfg.smallestScreenWidthDp >= 600
    }

    fun shouldEngageSplit(context: Context): Boolean {
        return when (DuoPrefs.getSplitMode(context)) {
            DuoPrefs.SplitMode.ALWAYS -> true
            DuoPrefs.SplitMode.OFF -> false
            DuoPrefs.SplitMode.AUTO -> isWideScreen(context)
        }
    }

    /**
     * Computes half-screen launch bounds for Strategy C (Freeform half) using public
     * WindowMetrics (API 30+) or Display.getRealSize (API 29).
     * In landscape/wide posture: LEFT = left half, RIGHT = right half.
     * In portrait (height > width): LEFT = top half, RIGHT = bottom half.
     */
    fun computeHalfScreenBounds(context: Context, side: DuoPrefs.PanelSide): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fullWidth: Int
        val fullHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            fullWidth = b.width()
            fullHeight = b.height()
        } else {
            val pt = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(pt)
            fullWidth = pt.x
            fullHeight = pt.y
        }

        val isPortrait = fullHeight > fullWidth
        return if (isPortrait) {
            val halfH = fullHeight / 2
            if (side == DuoPrefs.PanelSide.LEFT) {
                Rect(0, 0, fullWidth, halfH)
            } else {
                Rect(0, halfH, fullWidth, fullHeight)
            }
        } else {
            val halfW = fullWidth / 2
            if (side == DuoPrefs.PanelSide.LEFT) {
                Rect(0, 0, halfW, fullHeight)
            } else {
                Rect(halfW, 0, fullWidth, fullHeight)
            }
        }
    }

    /**
     * SPEC-ADDENDUM.md primary flow: called when the user picks an app inside PickerActivity
     * (or triggers the Left/Right flow test in Launch Lab).
     *
     * @param activity The foreground Activity (typically PickerActivity or LaunchLabActivity).
     * @param pkg Target app package name.
     * @param side Which side the picked app is intended to occupy on first pick (LEFT or RIGHT).
     * @param replaceMySide If true (or if [activity] is already in multi-window mode), the picked
     *                      app is launched WITHOUT adjacent flags so it takes the Picker's own panel
     *                      slot, and the Picker finishes (if auto-replace is enabled).
     */
    fun launchPickedApp(
        activity: Activity,
        pkg: String,
        side: DuoPrefs.PanelSide,
        replaceMySide: Boolean = activity.isInMultiWindowMode
    ): Boolean {
        val baseIntent = AppRepository.resolveLaunchIntent(activity, pkg)
        if (baseIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = activity,
                flowOrStrategy = "PICKER_LAUNCH",
                side = side.name,
                targetDesc = pkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "ERROR: Unable to resolve launch intent for $pkg"
            )
            Toast.makeText(activity, "Cannot launch $pkg", Toast.LENGTH_SHORT).show()
            return false
        }

        // Case 1: Narrow/cover screen when SplitMode is AUTO or OFF (and not already in split) -> launch fullscreen normally.
        if (!activity.isInMultiWindowMode && !shouldEngageSplit(activity)) {
            val flags = normalPrimaryFlags()
            baseIntent.addFlags(flags)
            return try {
                activity.startActivity(baseIntent)
                DuoPrefs.recordLaunchLog(
                    context = activity,
                    flowOrStrategy = "COVER_FULLSCREEN",
                    side = side.name,
                    targetDesc = pkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = "splitMode=${DuoPrefs.getSplitMode(activity).name},wide=${isWideScreen(activity)}",
                    result = "OK (cover/narrow normal launch)"
                )
                if (activity is PickerActivity) {
                    activity.finish()
                }
                true
            } catch (t: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = activity,
                    flowOrStrategy = "COVER_FULLSCREEN",
                    side = side.name,
                    targetDesc = pkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = "splitMode=${DuoPrefs.getSplitMode(activity).name}",
                    result = "FAIL: ${t.javaClass.simpleName}"
                )
                false
            }
        }

        // Case 2: Default corner-shortcut / in-split mode ("Replace this panel").
        if (replaceMySide) {
            val flags = replaceSlotFlags()
            baseIntent.addFlags(flags)
            return try {
                activity.startActivity(baseIntent)
                DuoPrefs.recordLaunchLog(
                    context = activity,
                    flowOrStrategy = "REPLACE_PANEL_SLOT",
                    side = side.name,
                    targetDesc = pkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = "inMultiWindow=${activity.isInMultiWindowMode}",
                    result = "OK (app replaces Picker side)"
                )
                if (activity is PickerActivity && DuoPrefs.isPickerAutoReplace(activity)) {
                    activity.finish()
                }
                true
            } catch (t: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = activity,
                    flowOrStrategy = "REPLACE_PANEL_SLOT",
                    side = side.name,
                    targetDesc = pkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = "inMultiWindow=${activity.isInMultiWindowMode}",
                    result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
                )
                false
            }
        }

        // Case 3: RIGHT flow (App goes RIGHT/secondary, existing Picker stays alive on LEFT/primary).
        if (side == DuoPrefs.PanelSide.RIGHT) {
            val flags = adjacentLaunchFlags()
            baseIntent.addFlags(flags)
            val (optsBundle, optsDesc) = tryCreateWindowingModeBundle(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
            return try {
                if (optsBundle != null) {
                    activity.startActivity(baseIntent, optsBundle)
                } else {
                    activity.startActivity(baseIntent)
                }
                DuoPrefs.recordLaunchLog(
                    context = activity,
                    flowOrStrategy = "RIGHT_FLOW",
                    side = "RIGHT (Picker stays LEFT)",
                    targetDesc = pkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = optsDesc,
                    result = "OK (app launched adjacent right; Picker stays alive left)"
                )
                // Do NOT finish PickerActivity in the RIGHT flow: it stays alive on the left panel!
                true
            } catch (t: Throwable) {
                // Retry pure flags without options bundle if reflection bundle caused an exception
                try {
                    activity.startActivity(baseIntent)
                    DuoPrefs.recordLaunchLog(
                        context = activity,
                        flowOrStrategy = "RIGHT_FLOW_FLAGS_ONLY",
                        side = "RIGHT (Picker stays LEFT)",
                        targetDesc = pkg,
                        flagsHex = describeFlags(flags),
                        optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName}",
                        result = "OK (pure flags fallback)"
                    )
                    true
                } catch (t2: Throwable) {
                    DuoPrefs.recordLaunchLog(
                        context = activity,
                        flowOrStrategy = "RIGHT_FLOW",
                        side = "RIGHT",
                        targetDesc = pkg,
                        flagsHex = describeFlags(flags),
                        optionsInfo = optsDesc,
                        result = "FAIL: ${t2.javaClass.simpleName}: ${t2.message}"
                    )
                    false
                }
            }
        }

        // Case 4: LEFT flow (App goes LEFT/primary, Picker re-anchors to RIGHT/secondary).
        return launchLeftPrimaryAndReanchorPicker(
            context = activity,
            pkg = pkg,
            logFlowName = "LEFT_FLOW_STEP1_APP",
            logSideName = "LEFT (primary)"
        )
    }

    /**
     * Alias for [launchPickedApp] used by PickerActivity (manual pick and EXTRA_AUTO_TARGET flow).
     */
    fun launchFromPicker(
        activity: Activity,
        pkg: String,
        side: DuoPrefs.PanelSide,
        replaceMySide: Boolean = activity.isInMultiWindowMode
    ): Boolean = launchPickedApp(
        activity = activity,
        pkg = pkg,
        side = side,
        replaceMySide = replaceMySide
    )

    /**
     * Shared LEFT flow step 1 + Step 2 re-anchor sequence:
     * 1. Launches [pkg] normally (`NEW_TASK | CLEAR_TOP`, no adjacent flag) as the primary task.
     * 2. Reuses [reanchorPicker] (`AnchorActivity`) to spawn `PickerActivity` adjacent on the RIGHT,
     *    paired with [pkg].
     */
    fun launchLeftPrimaryAndReanchorPicker(
        context: Context,
        pkg: String,
        logFlowName: String = "LEFT_FLOW_STEP1_APP",
        logSideName: String = "LEFT (primary)",
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        val baseIntent = AppRepository.resolveLaunchIntent(context, pkg)
        if (baseIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = logFlowName,
                side = logSideName,
                targetDesc = pkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "ERROR: Unable to resolve launch intent for $pkg"
            )
            Toast.makeText(context, "Cannot launch $pkg", Toast.LENGTH_SHORT).show()
            onComplete?.invoke(false)
            return false
        }
        val primaryFlags = normalPrimaryFlags()
        baseIntent.addFlags(primaryFlags)
        return try {
            context.startActivity(baseIntent)
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = logFlowName,
                side = logSideName,
                targetDesc = pkg,
                flagsHex = describeFlags(primaryFlags),
                optionsInfo = "normalPrimaryTask",
                result = "OK (primary app started; queueing reanchorPicker)"
            )
            // Step 2: Re-anchor Picker onto the RIGHT/secondary panel via AnchorActivity trampoline.
            reanchorPicker(
                context = context,
                side = DuoPrefs.PanelSide.RIGHT,
                pairedPackage = pkg,
                onComplete = onComplete
            )
            true
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = logFlowName,
                side = "LEFT",
                targetDesc = pkg,
                flagsHex = describeFlags(primaryFlags),
                optionsInfo = "normalPrimaryTask",
                result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
            )
            onComplete?.invoke(false)
            false
        }
    }

    /**
     * SPEC-ADDENDUM.md LEFT flow Step 2 & PATCH 0.1.3 Phase-1 confinement (`reanchorPicker`):
     * Starts `AnchorActivity` (translucent, excludeFromRecents=true, taskAffinity="") which immediately
     * launches a fresh `PickerActivity` with `adjacentLaunchFlags()` (+ guarded split options)
     * so the new Picker lands confined inside a split stage while the old fullscreen Picker finishes.
     * When [phase2Companion] is true, the newly confined PickerActivity will launch CompanionPanelActivity
     * adjacent into the other stage in Phase 2 once it enters multi-window mode.
     */
    fun reanchorPicker(
        context: Context,
        side: DuoPrefs.PanelSide,
        pairedPackage: String? = null,
        phase2Companion: Boolean = false,
        effectiveSide: DuoPrefs.PanelSide = DuoPrefs.effectiveSide(context, side),
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        return try {
            mainHandler.postDelayed({
                try {
                    val anchorIntent = Intent(context, AnchorActivity::class.java).apply {
                        putExtra(ShortcutHelper.EXTRA_SIDE, side.name)
                        putExtra(ShortcutHelper.EXTRA_EFFECTIVE_SIDE, effectiveSide.name)
                        if (!pairedPackage.isNullOrBlank()) {
                            putExtra(ShortcutHelper.EXTRA_PAIRED_PKG, pairedPackage)
                        }
                        if (phase2Companion) {
                            putExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION, true)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
                    context.startActivity(anchorIntent)
                    DuoPrefs.recordLaunchLog(
                        context = context,
                        flowOrStrategy = "LEFT_FLOW_STEP2_ANCHOR",
                        side = side.name,
                        targetDesc = "AnchorActivity -> PickerActivity(paired=${pairedPackage ?: "none"},phase2Companion=$phase2Companion)",
                        flagsHex = describeFlags(anchorIntent.flags),
                        optionsInfo = "delay=${ADJACENT_STEP_DELAY_MS}ms,effectiveSide=${effectiveSide.name}",
                        result = "OK (AnchorActivity dispatched)"
                    )
                    if (context is PickerActivity) {
                        context.finish()
                    }
                    onComplete?.invoke(true)
                } catch (inner: Throwable) {
                    DuoPrefs.recordLaunchLog(
                        context = context,
                        flowOrStrategy = "LEFT_FLOW_STEP2_ANCHOR",
                        side = side.name,
                        targetDesc = "AnchorActivity",
                        flagsHex = "0x00000000",
                        optionsInfo = "postDelayed",
                        result = "FAIL: ${inner.javaClass.simpleName}: ${inner.message}"
                    )
                    onComplete?.invoke(false)
                }
            }, ADJACENT_STEP_DELAY_MS)
            true
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "REANCHOR_PICKER",
                side = side.name,
                targetDesc = "PickerActivity",
                flagsHex = "0x00000000",
                optionsInfo = "handler",
                result = "FAIL: ${t.javaClass.simpleName}"
            )
            onComplete?.invoke(false)
            false
        }
    }

    /**
     * PATCH 0.1.3 (Part B): Two-phase corner-shortcut panel-pair formation for BOTH entry sides
     * and BOTH flip states:
     * - Phase 1 (here): Reuses the AnchorActivity mechanism ONLY as a Phase-1 confinement step
     *   to get a fresh PickerActivity confined into a split stage with `adjacentLaunchFlags()`
     *   (`0x18101000`) and `EXTRA_PHASE2_COMPANION = true`, while the initial fullscreen
     *   PickerActivity finishes (and any stray fullscreen Companion is finished).
     * - Phase 2 (in `PickerActivity.maybeRunPhase2CompanionDock` -> [dockCompanionAdjacent]):
     *   Once the confined PickerActivity enters multi-window mode, the CONFINED Picker launches
     *   CompanionPanelActivity with `adjacentLaunchFlags()` (`0x18101000`) so the Companion docks
     *   into the OTHER split stage instead of being stranded fullscreen.
     * - Logs `PANEL_PAIR_FORMED` in Phase 1 (or `PANEL_PAIR_FAILED` on error) and `PANEL_PAIR_PHASE2`
     *   in Phase 2, followed by `FORMATION_CHECK` ~1.2s after formation completes.
     */
    fun formCornerPanelPair(
        pickerActivity: Activity,
        effectivePickerSide: DuoPrefs.PanelSide,
        requestedPickerSide: DuoPrefs.PanelSide
    ): Boolean {
        if (!shouldEngageSplit(pickerActivity)) {
            DuoPrefs.recordLaunchLog(
                context = pickerActivity,
                flowOrStrategy = "PANEL_PAIR_FAILED",
                side = requestedPickerSide.name,
                targetDesc = "PickerActivity + CompanionPanelActivity",
                flagsHex = "0x00000000",
                optionsInfo = "splitMode=${DuoPrefs.getSplitMode(pickerActivity).name},wide=${isWideScreen(pickerActivity)}",
                result = "Split not engaged for current posture/mode; Picker staying fullscreen"
            )
            return false
        }

        // Reset in-memory Phase 2 retry state and ensure no previous fullscreen Companion remains.
        activeFormationState = null
        finishExistingCompanions()

        val phase1Flags = adjacentLaunchFlags()
        val isEffectiveLeft = (effectivePickerSide == DuoPrefs.PanelSide.LEFT)
        val sideLabel = if (isEffectiveLeft) "LEFT" else "RIGHT"
        val targetDesc = if (isEffectiveLeft) {
            "PickerActivity(LEFT) + CompanionPanelActivity(RIGHT)"
        } else {
            "CompanionPanelActivity(LEFT) + PickerActivity(RIGHT)"
        }
        val phaseOptsDesc = if (isEffectiveLeft) {
            "twoPhase(LEFT,req=${requestedPickerSide.name}):phase1AnchorConfinePicker->phase2ConfinedPickerDocksCompanion"
        } else {
            "twoPhase(RIGHT,req=${requestedPickerSide.name}):phase1AnchorConfinePicker->phase2ConfinedPickerDocksCompanion"
        }

        val reanchorQueued = reanchorPicker(
            context = pickerActivity,
            side = requestedPickerSide,
            pairedPackage = null,
            phase2Companion = true,
            effectiveSide = effectivePickerSide,
            onComplete = { ok ->
                if (!ok) {
                    DuoPrefs.recordLaunchLog(
                        context = pickerActivity,
                        flowOrStrategy = "PANEL_PAIR_FAILED",
                        side = sideLabel,
                        targetDesc = "AnchorActivity -> PickerActivity(${requestedPickerSide.name})",
                        flagsHex = describeFlags(phase1Flags),
                        optionsInfo = "reanchorCallback",
                        result = "FAIL: AnchorActivity failed to dispatch Phase 1 Picker confinement"
                    )
                }
            }
        )

        return if (reanchorQueued) {
            DuoPrefs.recordLaunchLog(
                context = pickerActivity,
                flowOrStrategy = "PANEL_PAIR_FORMED",
                side = sideLabel,
                targetDesc = targetDesc,
                flagsHex = describeFlags(phase1Flags),
                optionsInfo = phaseOptsDesc,
                result = "OK (Phase 1 queued: AnchorActivity confines Picker to stage; Phase 2 will launch Companion adjacent)"
            )
            scheduleFormationCheck(
                context = pickerActivity.applicationContext,
                formationSide = "${requestedPickerSide.name}->${requestedPickerSide.opposite().name}",
                delayMs = FORMATION_CHECK_DELAY_MS + 600L
            )
            true
        } else {
            DuoPrefs.recordLaunchLog(
                context = pickerActivity,
                flowOrStrategy = "PANEL_PAIR_FAILED",
                side = sideLabel,
                targetDesc = targetDesc,
                flagsHex = describeFlags(phase1Flags),
                optionsInfo = phaseOptsDesc,
                result = "FAIL: Unable to queue Phase 1 reanchorPicker"
            )
            false
        }
    }

    /**
     * PATCH 0.1.4: Phase 2 of corner-shortcut panel-pair formation via AnchorActivity relay.
     * The confined Picker starts AnchorActivity with `adjacentLaunchFlags()` (intent flags only,
     * no ActivityOptions windowing-mode reflection), carrying `EXTRA_ANCHOR_TARGET = ANCHOR_TARGET_COMPANION`,
     * `EXTRA_PHASE2_COMPANION = true`, and the companion's extras (`EXTRA_SIDE`, `EXTRA_EFFECTIVE_SIDE`,
     * `EXTRA_PAIRED_PKG`). AnchorActivity then spawns CompanionPanelActivity with plain
     * `NEW_TASK | MULTIPLE_TASK` (or falls back to direct adjacent launch if Anchor is not in multi-window).
     */
    fun dockCompanionViaAnchorRelay(
        callerActivity: Activity,
        companionDisplaySide: DuoPrefs.PanelSide,
        effectiveCompanionSide: DuoPrefs.PanelSide =
            DuoPrefs.effectiveSide(callerActivity, companionDisplaySide),
        pairedPackage: String? = null,
        isRetry: Boolean = false
    ): Boolean {
        if (!isRetry) {
            activeFormationState = ActiveFormationState(
                companionDisplaySide = companionDisplaySide,
                effectiveCompanionSide = effectiveCompanionSide,
                pairedPackage = pairedPackage,
                lastPathUsed = Phase2Path.RELAY,
                retryAttempted = false
            )
        } else {
            activeFormationState?.lastPathUsed = Phase2Path.RELAY
        }

        finishExistingCompanions()
        val flags = adjacentLaunchFlags()
        val anchorIntent = Intent(callerActivity, AnchorActivity::class.java).apply {
            putExtra(ShortcutHelper.EXTRA_ANCHOR_TARGET, ShortcutHelper.ANCHOR_TARGET_COMPANION)
            putExtra(ShortcutHelper.EXTRA_SIDE, companionDisplaySide.name)
            putExtra(ShortcutHelper.EXTRA_EFFECTIVE_SIDE, effectiveCompanionSide.name)
            putExtra(ShortcutHelper.EXTRA_PHASE2_COMPANION, true)
            if (!pairedPackage.isNullOrBlank()) {
                putExtra(ShortcutHelper.EXTRA_PAIRED_PKG, pairedPackage)
            }
            addFlags(flags)
        }

        return try {
            callerActivity.startActivity(anchorIntent)
            scheduleFormationCheck(
                context = callerActivity.applicationContext,
                formationSide = "${companionDisplaySide.opposite().name}->${companionDisplaySide.name}",
                delayMs = FORMATION_CHECK_DELAY_MS + 300L
            )
            true
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = callerActivity,
                flowOrStrategy = "PANEL_PAIR_FAILED",
                side = companionDisplaySide.name,
                targetDesc = "AnchorActivity -> CompanionPanelActivity",
                flagsHex = describeFlags(flags),
                optionsInfo = "path=relayAnchor,caller=${callerActivity.javaClass.simpleName}(inMultiWindow=${callerActivity.isInMultiWindowMode},frame=${getActivityWindowFrameString(callerActivity)})",
                result = "FAIL (PANEL_PAIR_PHASE2 relay dispatch): ${t.javaClass.simpleName}: ${t.message}"
            )
            scheduleFormationCheck(
                context = callerActivity.applicationContext,
                formationSide = "${companionDisplaySide.opposite().name}->${companionDisplaySide.name}",
                delayMs = FORMATION_CHECK_DELAY_MS
            )
            false
        }
    }

    /**
     * PATCH 0.1.4: Invoked by AnchorActivity.onCreate during Phase 2 of corner-shortcut panel-pair
     * formation (`EXTRA_ANCHOR_TARGET = ANCHOR_TARGET_COMPANION`, `EXTRA_PHASE2_COMPANION = true`).
     * - If AnchorActivity is in multi-window mode, launches CompanionPanelActivity with NO adjacent
     *   flags (plain `NEW_TASK | MULTIPLE_TASK` only) so Companion takes the anchor's stage, and
     *   logs `PANEL_PAIR_PHASE2` with `path=relayAnchor`.
     * - If AnchorActivity is NOT in multi-window mode, falls back to launching CompanionPanelActivity
     *   WITH the adjacent flag set via [dockCompanionAdjacent] (the 0.1.3 direct path) and logs
     *   `PANEL_PAIR_PHASE2` with `path=relayFallbackDirect`.
     */
    fun spawnCompanionFromAnchorRelay(
        anchor: Activity,
        companionSide: DuoPrefs.PanelSide,
        effectiveCompanionSide: DuoPrefs.PanelSide = DuoPrefs.effectiveSide(anchor, companionSide),
        pairedPackage: String?
    ): Boolean {
        val anchorInMultiWindow = anchor.isInMultiWindowMode
        if (!anchorInMultiWindow) {
            activeFormationState?.lastPathUsed = Phase2Path.DIRECT
            return dockCompanionAdjacent(
                callerActivity = anchor,
                companionDisplaySide = companionSide,
                effectiveCompanionSide = effectiveCompanionSide,
                pairedPackage = pairedPackage,
                logFlowName = "PANEL_PAIR_PHASE2",
                pathTag = "relayFallbackDirect",
                anchorInMultiWindow = false
            )
        }

        activeFormationState?.lastPathUsed = Phase2Path.RELAY
        val flags = replaceSlotFlags()
        val companionIntent = buildCompanionIntent(
            context = anchor,
            companionSide = companionSide,
            pairedPackage = pairedPackage
        ).apply {
            addFlags(flags)
        }

        val relayOptionsInfo =
            "path=relayAnchor,anchorInMultiWindow=$anchorInMultiWindow,target=CompanionPanelActivity,anchorFrame=${getActivityWindowFrameString(anchor)},eff=${effectiveCompanionSide.name},paired=${pairedPackage ?: "none"}"

        val ok = try {
            anchor.startActivity(companionIntent)
            DuoPrefs.recordLaunchLog(
                context = anchor,
                flowOrStrategy = "PANEL_PAIR_PHASE2",
                side = companionSide.name,
                targetDesc = "CompanionPanelActivity(side=${companionSide.name},eff=${effectiveCompanionSide.name},paired=${pairedPackage ?: "none"})",
                flagsHex = describeFlags(flags),
                optionsInfo = relayOptionsInfo,
                result = "OK (Companion launched via anchor relay with plain NEW_TASK|MULTIPLE_TASK into anchor stage)"
            )
            true
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = anchor,
                flowOrStrategy = "PANEL_PAIR_FAILED",
                side = companionSide.name,
                targetDesc = "CompanionPanelActivity",
                flagsHex = describeFlags(flags),
                optionsInfo = relayOptionsInfo,
                result = "FAIL (PANEL_PAIR_PHASE2 relayAnchor): ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }

        scheduleFormationCheck(
            context = anchor.applicationContext,
            formationSide = "${companionSide.opposite().name}->${companionSide.name}",
            delayMs = FORMATION_CHECK_DELAY_MS
        )
        return ok
    }

    /**
     * PATCH 0.1.3 (Part B) / PATCH 0.1.4 fallback: Direct adjacent Companion docking helper.
     * Finishes any pre-existing CompanionPanelActivity instance first so no fullscreen Companion
     * remains, then launches CompanionPanelActivity from the caller with `adjacentLaunchFlags()`
     * (`NEW_TASK | MULTIPLE_TASK | LAUNCH_ADJACENT | LAUNCH_ADJACENT_TO_RECENT`), logs
     * [logFlowName] (`PANEL_PAIR_PHASE2`), and schedules `FORMATION_CHECK` ~1.2s later.
     */
    fun dockCompanionAdjacent(
        callerActivity: Activity,
        companionDisplaySide: DuoPrefs.PanelSide,
        effectiveCompanionSide: DuoPrefs.PanelSide =
            DuoPrefs.effectiveSide(callerActivity, companionDisplaySide),
        pairedPackage: String? = null,
        logFlowName: String = "PANEL_PAIR_PHASE2",
        pathTag: String? = null,
        anchorInMultiWindow: Boolean? = null
    ): Boolean {
        val closedOld = finishExistingCompanions()
        val flags = adjacentLaunchFlags()
        val companionIntent = buildCompanionIntent(
            context = callerActivity,
            companionSide = companionDisplaySide,
            pairedPackage = pairedPackage
        ).apply {
            addFlags(flags)
        }

        val targetWindowingMode = if (effectiveCompanionSide == DuoPrefs.PanelSide.LEFT) {
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
        } else {
            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
        }
        val (optsBundle, optsDesc) = tryCreateWindowingModeBundle(targetWindowingMode)
        val pathPrefix = buildString {
            if (!pathTag.isNullOrBlank()) {
                append("path=").append(pathTag).append(",")
            }
            if (anchorInMultiWindow != null) {
                append("anchorInMultiWindow=").append(anchorInMultiWindow).append(",")
            }
            if (!pathTag.isNullOrBlank()) {
                append("target=CompanionPanelActivity,")
            }
        }
        val callerDesc =
            "${pathPrefix}caller=${callerActivity.javaClass.simpleName}(inMultiWindow=${callerActivity.isInMultiWindowMode},frame=${getActivityWindowFrameString(callerActivity)}),closedOld=$closedOld,$optsDesc"

        val ok = try {
            if (optsBundle != null) {
                callerActivity.startActivity(companionIntent, optsBundle)
            } else {
                callerActivity.startActivity(companionIntent)
            }
            DuoPrefs.recordLaunchLog(
                context = callerActivity,
                flowOrStrategy = logFlowName,
                side = companionDisplaySide.name,
                targetDesc = "CompanionPanelActivity(side=${companionDisplaySide.name},eff=${effectiveCompanionSide.name},paired=${pairedPackage ?: "none"})",
                flagsHex = describeFlags(flags),
                optionsInfo = callerDesc,
                result = "OK (Companion launched adjacent from confined stage into other split stage)"
            )
            true
        } catch (t: Throwable) {
            try {
                callerActivity.startActivity(companionIntent)
                DuoPrefs.recordLaunchLog(
                    context = callerActivity,
                    flowOrStrategy = logFlowName,
                    side = companionDisplaySide.name,
                    targetDesc = "CompanionPanelActivity(side=${companionDisplaySide.name},eff=${effectiveCompanionSide.name},paired=${pairedPackage ?: "none"})",
                    flagsHex = describeFlags(flags),
                    optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName};$callerDesc",
                    result = "OK (pure flags fallback; Companion launched adjacent into other split stage)"
                )
                true
            } catch (t2: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = callerActivity,
                    flowOrStrategy = "PANEL_PAIR_FAILED",
                    side = companionDisplaySide.name,
                    targetDesc = "CompanionPanelActivity",
                    flagsHex = describeFlags(flags),
                    optionsInfo = callerDesc,
                    result = "FAIL ($logFlowName): ${t2.javaClass.simpleName}: ${t2.message}"
                )
                false
            }
        }

        scheduleFormationCheck(
            context = callerActivity.applicationContext,
            formationSide = "${companionDisplaySide.opposite().name}->${companionDisplaySide.name}",
            delayMs = FORMATION_CHECK_DELAY_MS
        )
        return ok
    }

    /**
     * Invoked by AnchorActivity.onCreate to spawn the fresh PickerActivity adjacent to the
     * primary task (or into a confined split stage for Phase 1 of corner pair formation).
     */
    fun spawnAdjacentPickerFromAnchor(
        anchor: Activity,
        pickerSide: DuoPrefs.PanelSide,
        pairedPackage: String?,
        phase2Companion: Boolean = false,
        effectivePickerSide: DuoPrefs.PanelSide = DuoPrefs.effectiveSide(anchor, pickerSide)
    ): Boolean {
        val flags = adjacentLaunchFlags()
        val pickerIntent = ShortcutHelper.buildPanelPickerIntent(
            context = anchor,
            side = pickerSide,
            pairedPackage = pairedPackage,
            reanchored = true,
            panelEntry = false,
            defaultReplaceMode = true,
            phase2Companion = phase2Companion
        ).apply {
            addFlags(flags)
        }
        val windowingMode = if (phase2Companion && effectivePickerSide == DuoPrefs.PanelSide.LEFT) {
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
        } else {
            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
        }
        val (optsBundle, optsDesc) = tryCreateWindowingModeBundle(windowingMode)
        return try {
            if (optsBundle != null) {
                anchor.startActivity(pickerIntent, optsBundle)
            } else {
                anchor.startActivity(pickerIntent)
            }
            DuoPrefs.recordLaunchLog(
                context = anchor,
                flowOrStrategy = "REANCHOR_SPAWN_PICKER",
                side = pickerSide.name,
                targetDesc = "PickerActivity(paired=${pairedPackage ?: "none"},phase2Companion=$phase2Companion)",
                flagsHex = describeFlags(flags),
                optionsInfo = "eff=${effectivePickerSide.name},$optsDesc",
                result = "OK (fresh Picker launched adjacent into split stage)"
            )
            true
        } catch (t: Throwable) {
            try {
                anchor.startActivity(pickerIntent)
                DuoPrefs.recordLaunchLog(
                    context = anchor,
                    flowOrStrategy = "REANCHOR_SPAWN_PICKER_FLAGS_ONLY",
                    side = pickerSide.name,
                    targetDesc = "PickerActivity(phase2Companion=$phase2Companion)",
                    flagsHex = describeFlags(flags),
                    optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName}",
                    result = "OK (pure flags fallback)"
                )
                true
            } catch (t2: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = anchor,
                    flowOrStrategy = "REANCHOR_SPAWN_PICKER",
                    side = pickerSide.name,
                    targetDesc = "PickerActivity",
                    flagsHex = describeFlags(flags),
                    optionsInfo = optsDesc,
                    result = "FAIL: ${t2.javaClass.simpleName}: ${t2.message}"
                )
                false
            }
        }
    }

    /**
     * Invoked by AnchorActivity.onCreate during Solo shortcut flow to dock CompanionPanelActivity
     * adjacent on the free panel using the shared [dockCompanionAdjacent] helper.
     */
    fun spawnAdjacentCompanionFromAnchor(
        anchor: Activity,
        companionSide: DuoPrefs.PanelSide,
        pairedPackage: String?
    ): Boolean {
        return dockCompanionAdjacent(
            callerActivity = anchor,
            companionDisplaySide = companionSide,
            effectiveCompanionSide = DuoPrefs.effectiveSide(anchor, companionSide),
            pairedPackage = pairedPackage,
            logFlowName = "PANEL_PAIR_PHASE2"
        )
    }

    /**
     * Invoked by AnchorActivity.onCreate during RIGHT Solo shortcut flow to spawn the target app
     * adjacent on the RIGHT beside the newly launched primary CompanionPanelActivity.
     */
    fun spawnAdjacentAppFromAnchor(
        anchor: Activity,
        targetPkg: String,
        appSide: DuoPrefs.PanelSide
    ): Boolean {
        val appIntent = AppRepository.resolveLaunchIntent(anchor, targetPkg)
        if (appIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = anchor,
                flowOrStrategy = "REANCHOR_SPAWN_APP",
                side = appSide.name,
                targetDesc = targetPkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "FAIL: Unable to resolve launch intent for $targetPkg"
            )
            return false
        }
        val flags = adjacentLaunchFlags()
        appIntent.addFlags(flags)
        val (optsBundle, optsDesc) = tryCreateWindowingModeBundle(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
        return try {
            if (optsBundle != null) {
                anchor.startActivity(appIntent, optsBundle)
            } else {
                anchor.startActivity(appIntent)
            }
            DuoPrefs.recordLaunchLog(
                context = anchor,
                flowOrStrategy = "REANCHOR_SPAWN_APP",
                side = appSide.name,
                targetDesc = targetPkg,
                flagsHex = describeFlags(flags),
                optionsInfo = optsDesc,
                result = "OK (target app launched adjacent)"
            )
            true
        } catch (t: Throwable) {
            try {
                anchor.startActivity(appIntent)
                DuoPrefs.recordLaunchLog(
                    context = anchor,
                    flowOrStrategy = "REANCHOR_SPAWN_APP_FLAGS_ONLY",
                    side = appSide.name,
                    targetDesc = targetPkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName}",
                    result = "OK (pure flags fallback)"
                )
                true
            } catch (t2: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = anchor,
                    flowOrStrategy = "REANCHOR_SPAWN_APP",
                    side = appSide.name,
                    targetDesc = targetPkg,
                    flagsHex = describeFlags(flags),
                    optionsInfo = optsDesc,
                    result = "FAIL: ${t2.javaClass.simpleName}: ${t2.message}"
                )
                false
            }
        }
    }

    /**
     * Launches a per-app Solo shortcut:
     * - PATCH 0.1.1C: When split engages and `pickerOnFreePanel` is true (default), the free panel
     *   shows `CompanionPanelActivity` instead of the 0.1.1B auto-target Picker, logging `SOLO_WITH_COMPANION`:
     *   - LEFT solo: target app launched as primary (`NEW_TASK | CLEAR_TOP`) + AnchorActivity-spawned
     *     `CompanionPanelActivity` adjacent on the RIGHT.
     *   - RIGHT solo: `CompanionPanelActivity` launched as primary (`NEW_TASK | CLEAR_TOP`) +
     *     AnchorActivity-spawned target app adjacent on the RIGHT.
     * - When `pickerOnFreePanel` is false (or when testing an explicit strategy in Launch Lab),
     *   Solo behavior stays as in 0.1.0 (remembered strategy first, then A -> B -> C -> D).
     */
    fun launchSoloShortcut(
        context: Context,
        targetPkg: String,
        side: DuoPrefs.PanelSide,
        forcedStrategy: DuoPrefs.Strategy? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!shouldEngageSplit(context) && forcedStrategy == null) {
            val ok = executeStrategyD(context, targetPkg, side, showRefusedToast = false)
            onComplete?.invoke(ok)
            return
        }

        if (forcedStrategy == null && DuoPrefs.isPickerOnFreePanel(context)) {
            activeFormationState = null
            val userAppSide = DuoPrefs.effectiveSide(context, side)
            val companionDisplaySide = userAppSide.opposite()
            recordActivePanelApp(context, userAppSide, targetPkg)
            finishExistingCompanions()

            val appIntent = AppRepository.resolveLaunchIntent(context, targetPkg)
            if (appIntent == null) {
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "SOLO_WITH_COMPANION",
                    side = side.name,
                    targetDesc = targetPkg,
                    flagsHex = "0x00000000",
                    optionsInfo = "none",
                    result = "ERROR: Unable to resolve launch intent for $targetPkg"
                )
                Toast.makeText(context, "Cannot launch $targetPkg", Toast.LENGTH_SHORT).show()
                onComplete?.invoke(false)
                return
            }

            // PATCH 0.1.3 (Part B): Never launch CompanionPanelActivity as a normal fullscreen
            // primary in Phase 1; stage the target app first, then dock CompanionPanelActivity
            // adjacent on the free panel via the shared docking helper (`spawnAdjacentCompanionFromAnchor`
            // -> `dockCompanionAdjacent`).
            val step1Flags = if (side == DuoPrefs.PanelSide.LEFT) {
                normalPrimaryFlags()
            } else {
                adjacentLaunchFlags()
            }
            appIntent.addFlags(step1Flags)
            try {
                context.startActivity(appIntent)
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "SOLO_WITH_COMPANION",
                    side = side.name,
                    targetDesc = if (side == DuoPrefs.PanelSide.LEFT) {
                        "$targetPkg(LEFT) + CompanionPanelActivity(RIGHT)"
                    } else {
                        "CompanionPanelActivity(LEFT) + $targetPkg(RIGHT)"
                    },
                    flagsHex = describeFlags(step1Flags),
                    optionsInfo = "phase1App(${side.name}) + phase2DockCompanion(${companionDisplaySide.name})",
                    result = "OK (target app launched; queueing AnchorActivity -> dockCompanionAdjacent)"
                )
                mainHandler.postDelayed({
                    try {
                        val anchorIntent = Intent(context, AnchorActivity::class.java).apply {
                            putExtra(ShortcutHelper.EXTRA_ANCHOR_TARGET, ShortcutHelper.ANCHOR_TARGET_COMPANION)
                            putExtra(ShortcutHelper.EXTRA_SIDE, companionDisplaySide.name)
                            putExtra(ShortcutHelper.EXTRA_PAIRED_PKG, targetPkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        }
                        context.startActivity(anchorIntent)
                        onComplete?.invoke(true)
                    } catch (inner: Throwable) {
                        DuoPrefs.recordLaunchLog(
                            context = context,
                            flowOrStrategy = "SOLO_WITH_COMPANION",
                            side = side.name,
                            targetDesc = "AnchorActivity -> CompanionPanelActivity",
                            flagsHex = "0x00000000",
                            optionsInfo = "postDelayed",
                            result = "FAIL: ${inner.javaClass.simpleName}: ${inner.message}"
                        )
                        onComplete?.invoke(false)
                    }
                }, ADJACENT_STEP_DELAY_MS)
            } catch (t: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "SOLO_WITH_COMPANION",
                    side = side.name,
                    targetDesc = targetPkg,
                    flagsHex = describeFlags(step1Flags),
                    optionsInfo = "phase1App",
                    result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
                )
                onComplete?.invoke(false)
            }
            return
        }

        val pickerOppositeSide = side.opposite()
        val secondaryPickerIntent = ShortcutHelper.buildPanelPickerIntent(
            context = context,
            side = pickerOppositeSide,
            pairedPackage = targetPkg,
            reanchored = true
        )

        if (forcedStrategy != null) {
            runSingleStrategy(
                context = context,
                strategy = forcedStrategy,
                primaryPkg = targetPkg,
                secondaryIntent = secondaryPickerIntent,
                secondaryDesc = "PickerActivity(${pickerOppositeSide.name})",
                side = side,
                onComplete = onComplete
            )
            return
        }

        val remembered = DuoPrefs.getRememberedStrategy(context, targetPkg)
        val order = linkedSetOf<DuoPrefs.Strategy>().apply {
            if (remembered != null) add(remembered)
            add(DuoPrefs.Strategy.A)
            add(DuoPrefs.Strategy.B)
            add(DuoPrefs.Strategy.C)
            add(DuoPrefs.Strategy.D)
        }.toList()

        tryStrategiesInOrder(
            context = context,
            strategies = order,
            index = 0,
            primaryPkg = targetPkg,
            secondaryIntent = secondaryPickerIntent,
            secondaryDesc = "PickerActivity(${pickerOppositeSide.name})",
            side = side,
            onComplete = onComplete
        )
    }

    /**
     * Launches a two-app Pair shortcut (primaryPkg + secondaryPkg adjacent) using the same engine.
     */
    fun launchPairShortcut(
        context: Context,
        primaryPkg: String,
        secondaryPkg: String,
        forcedStrategy: DuoPrefs.Strategy? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val secondaryIntent = AppRepository.resolveLaunchIntent(context, secondaryPkg)
        if (secondaryIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "PAIR_LAUNCH",
                side = "LEFT+RIGHT",
                targetDesc = "$primaryPkg + $secondaryPkg",
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "ERROR: Secondary package $secondaryPkg not found"
            )
            Toast.makeText(context, "Could not resolve $secondaryPkg", Toast.LENGTH_SHORT).show()
            onComplete?.invoke(false)
            return
        }

        if (!shouldEngageSplit(context) && forcedStrategy == null) {
            val ok = executeStrategyD(context, primaryPkg, DuoPrefs.PanelSide.LEFT, showRefusedToast = false)
            onComplete?.invoke(ok)
            return
        }

        val strategyToUse = forcedStrategy
            ?: DuoPrefs.getRememberedStrategy(context, primaryPkg)
            ?: DuoPrefs.Strategy.A

        runSingleStrategy(
            context = context,
            strategy = strategyToUse,
            primaryPkg = primaryPkg,
            secondaryIntent = secondaryIntent,
            secondaryDesc = secondaryPkg,
            side = DuoPrefs.PanelSide.LEFT,
            onComplete = { ok ->
                if (!ok && forcedStrategy == null) {
                    val fallbackOk = executeStrategyD(
                        context = context,
                        primaryPkg = primaryPkg,
                        side = DuoPrefs.PanelSide.LEFT,
                        showRefusedToast = true
                    )
                    onComplete?.invoke(fallbackOk)
                } else {
                    onComplete?.invoke(ok)
                }
            }
        )
    }

    private fun tryStrategiesInOrder(
        context: Context,
        strategies: List<DuoPrefs.Strategy>,
        index: Int,
        primaryPkg: String,
        secondaryIntent: Intent,
        secondaryDesc: String,
        side: DuoPrefs.PanelSide,
        onComplete: ((Boolean) -> Unit)?
    ) {
        if (index >= strategies.size) {
            onComplete?.invoke(false)
            return
        }
        val current = strategies[index]
        runSingleStrategy(
            context = context,
            strategy = current,
            primaryPkg = primaryPkg,
            secondaryIntent = Intent(secondaryIntent),
            secondaryDesc = secondaryDesc,
            side = side,
            onComplete = { success ->
                if (success) {
                    if (current != DuoPrefs.Strategy.D) {
                        DuoPrefs.setRememberedStrategy(context, primaryPkg, current)
                    }
                    onComplete?.invoke(true)
                } else {
                    tryStrategiesInOrder(
                        context = context,
                        strategies = strategies,
                        index = index + 1,
                        primaryPkg = primaryPkg,
                        secondaryIntent = secondaryIntent,
                        secondaryDesc = secondaryDesc,
                        side = side,
                        onComplete = onComplete
                    )
                }
            }
        )
    }

    private fun runSingleStrategy(
        context: Context,
        strategy: DuoPrefs.Strategy,
        primaryPkg: String,
        secondaryIntent: Intent,
        secondaryDesc: String,
        side: DuoPrefs.PanelSide,
        onComplete: ((Boolean) -> Unit)?
    ) {
        when (strategy) {
            DuoPrefs.Strategy.A -> executeStrategyA(
                context = context,
                primaryPkg = primaryPkg,
                secondaryIntent = secondaryIntent,
                secondaryDesc = secondaryDesc,
                side = side,
                onComplete = onComplete
            )
            DuoPrefs.Strategy.B -> executeStrategyB(
                context = context,
                primaryPkg = primaryPkg,
                secondaryIntent = secondaryIntent,
                secondaryDesc = secondaryDesc,
                side = side,
                onComplete = onComplete
            )
            DuoPrefs.Strategy.C -> {
                val ok = executeStrategyC(context, primaryPkg, side)
                onComplete?.invoke(ok)
            }
            DuoPrefs.Strategy.D -> {
                val ok = executeStrategyD(context, primaryPkg, side, showRefusedToast = true)
                onComplete?.invoke(ok)
            }
        }
    }

    /**
     * Strategy A (Split-adjacent, primary per SPEC.md & TECH-NOTES.md note 6):
     * 1. Launch target app normally (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP).
     * 2. After a short handler delay (110ms) so WindowManager registers the primary task,
     *    launch PickerActivity (or second app) with `adjacentLaunchFlags()`
     *    (`NEW_TASK | MULTIPLE_TASK | FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_LAUNCH_ADJACENT_TO_RECENT`)
     *    plus guarded reflection fallback for `WINDOWING_MODE_SPLIT_SCREEN_SECONDARY`.
     */
    fun executeStrategyA(
        context: Context,
        primaryPkg: String,
        secondaryIntent: Intent,
        secondaryDesc: String,
        side: DuoPrefs.PanelSide,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val primaryIntent = AppRepository.resolveLaunchIntent(context, primaryPkg)
        if (primaryIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_A",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "FAIL: Package $primaryPkg not found"
            )
            onComplete?.invoke(false)
            return
        }

        val step1Flags = normalPrimaryFlags()
        primaryIntent.addFlags(step1Flags)
        try {
            context.startActivity(primaryIntent)
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_A_STEP1_PRIMARY",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(step1Flags),
                optionsInfo = "normalPrimary",
                result = "OK"
            )
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_A_STEP1_PRIMARY",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(step1Flags),
                optionsInfo = "normalPrimary",
                result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
            )
            onComplete?.invoke(false)
            return
        }

        mainHandler.postDelayed({
            val step2Flags = adjacentLaunchFlags()
            secondaryIntent.addFlags(step2Flags)
            val (optsBundle, optsDesc) = tryCreateWindowingModeBundle(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
            val ok = try {
                if (optsBundle != null) {
                    context.startActivity(secondaryIntent, optsBundle)
                } else {
                    context.startActivity(secondaryIntent)
                }
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "STRATEGY_A_STEP2_ADJACENT",
                    side = side.opposite().name,
                    targetDesc = secondaryDesc,
                    flagsHex = describeFlags(step2Flags),
                    optionsInfo = optsDesc,
                    result = "OK"
                )
                true
            } catch (t: Throwable) {
                try {
                    context.startActivity(secondaryIntent)
                    DuoPrefs.recordLaunchLog(
                        context = context,
                        flowOrStrategy = "STRATEGY_A_STEP2_FLAGS_ONLY",
                        side = side.opposite().name,
                        targetDesc = secondaryDesc,
                        flagsHex = describeFlags(step2Flags),
                        optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName}",
                        result = "OK"
                    )
                    true
                } catch (t2: Throwable) {
                    DuoPrefs.recordLaunchLog(
                        context = context,
                        flowOrStrategy = "STRATEGY_A_STEP2_ADJACENT",
                        side = side.opposite().name,
                        targetDesc = secondaryDesc,
                        flagsHex = describeFlags(step2Flags),
                        optionsInfo = optsDesc,
                        result = "FAIL: ${t2.javaClass.simpleName}: ${t2.message}"
                    )
                    false
                }
            }
            if (ok) {
                DuoPrefs.setRememberedStrategy(context, primaryPkg, DuoPrefs.Strategy.A)
            }
            onComplete?.invoke(ok)
        }, ADJACENT_STEP_DELAY_MS)
    }

    /**
     * Strategy B (Split flags on target too, per SPEC.md & TECH-NOTES.md note 6):
     * 1. Launch target app itself with `adjacentLaunchFlags()` + guarded `WINDOWING_MODE_SPLIT_SCREEN_PRIMARY`.
     * 2. After 110ms, launch PickerActivity (or second app) with `adjacentLaunchFlags()`
     *    + guarded `WINDOWING_MODE_SPLIT_SCREEN_SECONDARY`.
     */
    fun executeStrategyB(
        context: Context,
        primaryPkg: String,
        secondaryIntent: Intent,
        secondaryDesc: String,
        side: DuoPrefs.PanelSide,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val primaryIntent = AppRepository.resolveLaunchIntent(context, primaryPkg)
        if (primaryIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_B",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "FAIL: Package $primaryPkg not found"
            )
            onComplete?.invoke(false)
            return
        }

        val step1Flags = adjacentLaunchFlags()
        primaryIntent.addFlags(step1Flags)
        val (primaryBundle, primaryOptsDesc) = tryCreateWindowingModeBundle(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
        val primaryStarted = try {
            if (primaryBundle != null) {
                context.startActivity(primaryIntent, primaryBundle)
            } else {
                context.startActivity(primaryIntent)
            }
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_B_STEP1_TARGET_SPLIT",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(step1Flags),
                optionsInfo = primaryOptsDesc,
                result = "OK"
            )
            true
        } catch (t: Throwable) {
            try {
                context.startActivity(primaryIntent)
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "STRATEGY_B_STEP1_FLAGS_ONLY",
                    side = side.name,
                    targetDesc = primaryPkg,
                    flagsHex = describeFlags(step1Flags),
                    optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName}",
                    result = "OK"
                )
                true
            } catch (t2: Throwable) {
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "STRATEGY_B_STEP1_TARGET_SPLIT",
                    side = side.name,
                    targetDesc = primaryPkg,
                    flagsHex = describeFlags(step1Flags),
                    optionsInfo = primaryOptsDesc,
                    result = "FAIL: ${t2.javaClass.simpleName}: ${t2.message}"
                )
                false
            }
        }

        if (!primaryStarted) {
            onComplete?.invoke(false)
            return
        }

        mainHandler.postDelayed({
            val step2Flags = adjacentLaunchFlags()
            secondaryIntent.addFlags(step2Flags)
            val (secBundle, secOptsDesc) = tryCreateWindowingModeBundle(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
            val ok = try {
                if (secBundle != null) {
                    context.startActivity(secondaryIntent, secBundle)
                } else {
                    context.startActivity(secondaryIntent)
                }
                DuoPrefs.recordLaunchLog(
                    context = context,
                    flowOrStrategy = "STRATEGY_B_STEP2_ADJACENT",
                    side = side.opposite().name,
                    targetDesc = secondaryDesc,
                    flagsHex = describeFlags(step2Flags),
                    optionsInfo = secOptsDesc,
                    result = "OK"
                )
                true
            } catch (t: Throwable) {
                try {
                    context.startActivity(secondaryIntent)
                    DuoPrefs.recordLaunchLog(
                        context = context,
                        flowOrStrategy = "STRATEGY_B_STEP2_FLAGS_ONLY",
                        side = side.opposite().name,
                        targetDesc = secondaryDesc,
                        flagsHex = describeFlags(step2Flags),
                        optionsInfo = "retryWithoutBundleAfter:${t.javaClass.simpleName}",
                        result = "OK"
                    )
                    true
                } catch (t2: Throwable) {
                    DuoPrefs.recordLaunchLog(
                        context = context,
                        flowOrStrategy = "STRATEGY_B_STEP2_ADJACENT",
                        side = side.opposite().name,
                        targetDesc = secondaryDesc,
                        flagsHex = describeFlags(step2Flags),
                        optionsInfo = secOptsDesc,
                        result = "FAIL: ${t2.javaClass.simpleName}: ${t2.message}"
                    )
                    false
                }
            }
            if (ok) {
                DuoPrefs.setRememberedStrategy(context, primaryPkg, DuoPrefs.Strategy.B)
            }
            onComplete?.invoke(ok)
        }, ADJACENT_STEP_DELAY_MS)
    }

    /**
     * Strategy C (Freeform half bounds per SPEC.md line 21 & TECH-NOTES.md note 4):
     * Uses public `ActivityOptions.makeBasic().setLaunchBounds(rect)` sized to the requested half
     * of the display, leaving windowing mode default (never pairing bounds with split windowing mode).
     */
    fun executeStrategyC(
        context: Context,
        primaryPkg: String,
        side: DuoPrefs.PanelSide
    ): Boolean {
        val primaryIntent = AppRepository.resolveLaunchIntent(context, primaryPkg)
        if (primaryIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_C_BOUNDS",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "FAIL: Package $primaryPkg not found"
            )
            return false
        }

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        primaryIntent.addFlags(flags)
        val bounds: Rect = computeHalfScreenBounds(context, side)
        val opts = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        val boundsDesc = "setLaunchBounds(${bounds.toShortString()})"
        return try {
            context.startActivity(primaryIntent, opts.toBundle())
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_C_BOUNDS",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(flags),
                optionsInfo = boundsDesc,
                result = "OK"
            )
            DuoPrefs.setRememberedStrategy(context, primaryPkg, DuoPrefs.Strategy.C)
            true
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_C_BOUNDS",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(flags),
                optionsInfo = boundsDesc,
                result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    /**
     * Strategy D (Graceful full-screen fallback per SPEC.md line 22):
     * Launches the target normally full-screen and shows the exact spec toast when split was refused.
     */
    fun executeStrategyD(
        context: Context,
        primaryPkg: String,
        side: DuoPrefs.PanelSide,
        showRefusedToast: Boolean = true
    ): Boolean {
        val primaryIntent = AppRepository.resolveLaunchIntent(context, primaryPkg)
        if (primaryIntent == null) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_D_NORMAL",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = "0x00000000",
                optionsInfo = "none",
                result = "FAIL: Package $primaryPkg not found"
            )
            Toast.makeText(context, "Unable to launch $primaryPkg", Toast.LENGTH_SHORT).show()
            return false
        }

        val flags = normalPrimaryFlags()
        primaryIntent.addFlags(flags)
        return try {
            context.startActivity(primaryIntent)
            if (showRefusedToast) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_split_refused),
                    Toast.LENGTH_LONG
                ).show()
            }
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_D_NORMAL",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(flags),
                optionsInfo = "showRefusedToast=$showRefusedToast",
                result = "OK (opened fullscreen)"
            )
            true
        } catch (t: Throwable) {
            DuoPrefs.recordLaunchLog(
                context = context,
                flowOrStrategy = "STRATEGY_D_NORMAL",
                side = side.name,
                targetDesc = primaryPkg,
                flagsHex = describeFlags(flags),
                optionsInfo = "normal",
                result = "FAIL: ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    /**
     * Picker header "Swap" button action:
     * Reverses the current app + Picker (or Picker + Companion) sides using the SoloLauncher engine.
     */
    fun swapCurrentPair(
        pickerActivity: Activity,
        currentSide: DuoPrefs.PanelSide,
        pairedPkg: String?
    ) {
        val newSide = currentSide.opposite()
        if (pairedPkg.isNullOrBlank()) {
            // No paired app yet: swap the Picker + Companion panels to the opposite side
            val refreshed = ShortcutHelper.buildPanelPickerIntent(
                context = pickerActivity,
                side = newSide,
                pairedPackage = null,
                reanchored = false,
                panelEntry = true,
                defaultReplaceMode = true
            ).apply {
                addFlags(normalPrimaryFlags())
            }
            DuoPrefs.recordLaunchLog(
                context = pickerActivity,
                flowOrStrategy = "SWAP_SIDE_TOGGLE",
                side = "${currentSide.name}->${newSide.name}",
                targetDesc = "PickerActivity <-> CompanionPanelActivity",
                flagsHex = describeFlags(normalPrimaryFlags()),
                optionsInfo = "noPairedApp",
                result = "OK (swapped Picker to ${newSide.name})"
            )
            pickerActivity.finish()
            pickerActivity.startActivity(refreshed)
            return
        }

        // Relaunch the paired app on the opposite side with Picker opposite it
        DuoPrefs.recordLaunchLog(
            context = pickerActivity,
            flowOrStrategy = "SWAP_PAIR",
            side = "${currentSide.name}->${newSide.name}",
            targetDesc = "$pairedPkg <-> PickerActivity",
            flagsHex = describeFlags(adjacentLaunchFlags()),
            optionsInfo = "relaunchReversed",
            result = "Dispatched reversed split sequence"
        )
        launchPickedApp(
            activity = pickerActivity,
            pkg = pairedPkg,
            side = newSide,
            replaceMySide = false
        )
    }

    /**
     * CompanionPanelActivity "Swap" button action:
     * Swaps the Companion panel and the other panel (either the paired app or the Picker).
     */
    fun swapFromCompanion(
        companionActivity: Activity,
        companionRequestedSide: DuoPrefs.PanelSide,
        pairedPkg: String?
    ) {
        val newCompanionSide = companionRequestedSide.opposite()
        if (pairedPkg.isNullOrBlank()) {
            // Picker is on the other panel (newCompanionSide); move Picker to companionRequestedSide
            val pickerIntent = ShortcutHelper.buildPanelPickerIntent(
                context = companionActivity,
                side = companionRequestedSide,
                pairedPackage = null,
                reanchored = false,
                panelEntry = true,
                defaultReplaceMode = true
            ).apply {
                addFlags(normalPrimaryFlags())
            }
            DuoPrefs.recordLaunchLog(
                context = companionActivity,
                flowOrStrategy = "SWAP_COMPANION_PICKER",
                side = "${companionRequestedSide.name}->${newCompanionSide.name}",
                targetDesc = "CompanionPanelActivity <-> PickerActivity",
                flagsHex = describeFlags(normalPrimaryFlags()),
                optionsInfo = "noPairedApp",
                result = "OK (moved Picker to ${companionRequestedSide.name})"
            )
            companionActivity.finish()
            companionActivity.startActivity(pickerIntent)
            return
        }

        // Paired app was on companionRequestedSide.opposite(); move app to companionRequestedSide
        val newEffectiveAppSide = DuoPrefs.effectiveSide(companionActivity, companionRequestedSide)
        DuoPrefs.recordLaunchLog(
            context = companionActivity,
            flowOrStrategy = "SWAP_COMPANION_PAIR",
            side = "${companionRequestedSide.opposite().name}->${companionRequestedSide.name}",
            targetDesc = "$pairedPkg <-> CompanionPanelActivity",
            flagsHex = describeFlags(normalPrimaryFlags()),
            optionsInfo = "relaunchSoloWithCompanion",
            result = "Dispatched swapped app + Companion pair"
        )
        companionActivity.finish()
        launchSoloShortcut(
            context = companionActivity,
            targetPkg = pairedPkg,
            side = newEffectiveAppSide
        )
    }

    /**
     * Picker header "Full" button action:
     * Relaunches the paired app full-screen and finishes the Picker.
     */
    fun expandPairedAppFullscreen(pickerActivity: Activity, pairedPkg: String?): Boolean {
        if (pairedPkg.isNullOrBlank()) {
            Toast.makeText(
                pickerActivity,
                "Pick an app first, or tap Close to dismiss this panel.",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        val ok = executeStrategyD(
            context = pickerActivity,
            primaryPkg = pairedPkg,
            side = DuoPrefs.PanelSide.LEFT,
            showRefusedToast = false
        )
        if (ok) {
            pickerActivity.finish()
        }
        return ok
    }
}
