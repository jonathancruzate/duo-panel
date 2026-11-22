package com.jonathancruzate.duopanel

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.function.Consumer

/**
 * Programmatic Samsung One UI dark-mode visual builders (framework-only, zero AndroidX).
 *
 * Matches DESIGN-REFERENCE.md, PATCH-0.1.2.md, and PATCH-0.1.2B.md:
 * - Deep navy-black solid background (#0B0E1A) for Settings/Main/LaunchLab/About
 * - Translucent frosted cross-window blur + ~72% dark navy scrim (#B80B0E1A) for Picker & Companion
 * - Grouped cards one step lighter (#1A1E2E) with 26dp rounded corners
 * - Hairline dividers (#282D42) inside cards only, never bordered boxes inside boxes
 * - White primary text (#FAFAFA), gray secondary text (#9A9FAE)
 * - Samsung blue (#0381FE) ONLY for links, selection, and active states
 * - One UI pill switches (light track with white round thumb when on)
 * - Floating rounded menu cards (22dp radius, plain rows, check mark on selected item, no title bar)
 */
object OneUiViews {

    // ------------------------------------------------------------------
    // PATCH 0.2.0: palette fields. These were compile-time constants for the
    // dark palette through 0.1.x; they are now mutable fields holding the
    // ACTIVE palette, pushed by DuoTheme.applyTo -> applyPalette before any
    // view is built. The initial values are the dark palette, so behavior
    // before the first applyPalette (and the default System theme on a
    // night-mode device) is byte-identical to 0.1.10. Every existing
    // OneUiViews.COLOR_* read site keeps working unchanged.
    // ------------------------------------------------------------------

    /** Solid surface background (dark: deep navy #0B0E1A; light: One UI light gray). */
    var COLOR_BG_NAVY: Int = 0xFF0B0E1A.toInt()
    val COLOR_BG_BLACK: Int get() = COLOR_BG_NAVY

    /**
     * Semi-transparent scrim for frosted blur panels when cross-window blur is active
     * (PATCH 0.1.9): the palette background at ~65% opacity (alpha 0xA6) layered directly
     * on the window background over the 140px cross-window blur so backdrop tones breathe
     * through as blurred glass while keeping labels crisp.
     */
    var COLOR_BG_SCRIM: Int = 0xA60B0E1A.toInt()

    /**
     * Deeper fallback scrim (PATCH 0.1.9): the palette background at ~85% opacity
     * (alpha 0xD9; ~88% / 0xE0 on the light palette) used when the system reports
     * cross-window blur disabled (API < 31, Battery Saver, or a ROM/GSI with
     * mBlurEnabled=false). Retains subtle translucency without allowing sharp
     * underlying text to collide with Picker labels.
     */
    var COLOR_BG_SCRIM_NO_BLUR: Int = 0xD90B0E1A.toInt()

    /**
     * Cross-window blur radius in px (PATCH 0.1.9): raised from 120 to 140px (within Android's
     * 150px performance guideline, ~56dp at 400dpi) for strong One UI frosted-glass dissolution.
     */
    const val PANEL_BLUR_RADIUS_PX: Int = 140

    var COLOR_CARD_BG: Int = 0xFF1A1E2E.toInt()
    val COLOR_FOCUS_BLOCK: Int get() = COLOR_CARD_BG
    var COLOR_SUBTLE_SURFACE: Int = 0xFF22273B.toInt()
    val COLOR_NESTED_CARD: Int get() = COLOR_SUBTLE_SURFACE
    var COLOR_HAIRLINE_DIVIDER: Int = 0xFF282D42.toInt()
    val COLOR_NESTED_STROKE: Int get() = COLOR_HAIRLINE_DIVIDER
    var COLOR_FLOATING_MENU: Int = 0xFF252A3E.toInt()
    var COLOR_FLOATING_STROKE: Int = 0xFF313750.toInt()
    var COLOR_SEARCH_PILL: Int = 0xFF151928.toInt()

    /** The active accent (PATCH 0.2.0: resolved by DuoTheme; default Samsung blue #0381FE). */
    var COLOR_ONEUI_BLUE: Int = 0xFF0381FE.toInt()
    var COLOR_ONEUI_BLUE_TINT: Int = 0x240381FE
    var COLOR_TEXT_PRIMARY: Int = 0xFFFAFAFA.toInt()
    var COLOR_TEXT_SECONDARY: Int = 0xFF9A9FAE.toInt()
    val COLOR_TEXT_ACCENT: Int get() = COLOR_ONEUI_BLUE
    var COLOR_SWITCH_TRACK_ON: Int = 0xFF8FA8D8.toInt()
    var COLOR_SWITCH_TRACK_OFF: Int = 0xFF525668.toInt()
    var COLOR_SWITCH_THUMB: Int = 0xFFFFFFFF.toInt()

    /** PanelGraphicView tile colors (PATCH 0.2.0: palette fields, were inline literals). */
    var COLOR_GRAPHIC_BG: Int = 0xFF121624.toInt()
    var COLOR_GRAPHIC_INACTIVE_FILL: Int = 0xFF262B3E.toInt()
    var COLOR_GRAPHIC_INACTIVE_STROKE: Int = 0xFF565D78.toInt()

    // Status colors are fixed in both palettes (never palette-swapped).
    const val COLOR_STATUS_OK: Int = 0xFF2ECC71.toInt()
    const val COLOR_STATUS_WARN: Int = 0xFFF39C12.toInt()
    const val COLOR_DANGER: Int = 0xFFE74C3C.toInt()

    /** Search pill outline (PATCH 0.2.0 design pass: light palette only, else 0 = none). */
    var COLOR_SEARCH_PILL_STROKE: Int = 0

    /** Usage-access grant prompt fill (PATCH 0.2.0 sections pass: translucent card). */
    var COLOR_GRANT_FILL: Int = 0x991A1E2E.toInt()

    /**
     * PATCH 0.2.0: pushes one resolved palette into the fields above.
     * Dark values are exactly the 0.1.x constants. Light values are the
     * DESIGN-PASS-0.2.0 spec (Gemini design pass, reconciled): One UI Light
     * canvas #F2F2F6 with white cards, blur scrim at 80% (a 65% light scrim
     * fails over dark blurred backdrops), and the no-blur fallback scrim at
     * 94% (the hard constraint on ROMs with mBlurEnabled=false: it compresses
     * sharp backdrop text to ~1.15:1 ghost contrast while keeping secondary
     * text at 4.83:1 even over solid black). The switch ON track is resolved
     * by DuoTheme (stock for the default accent, HSV-derived otherwise) and
     * passed in as [trackOn].
     */
    fun applyPalette(dark: Boolean, accent: Int, accentTint: Int, trackOn: Int) {
        if (dark) {
            COLOR_BG_NAVY = 0xFF0B0E1A.toInt()
            COLOR_BG_SCRIM = 0xA60B0E1A.toInt()
            COLOR_BG_SCRIM_NO_BLUR = 0xD90B0E1A.toInt()
            COLOR_CARD_BG = 0xFF1A1E2E.toInt()
            COLOR_SUBTLE_SURFACE = 0xFF22273B.toInt()
            COLOR_HAIRLINE_DIVIDER = 0xFF282D42.toInt()
            COLOR_FLOATING_MENU = 0xFF252A3E.toInt()
            COLOR_FLOATING_STROKE = 0xFF313750.toInt()
            COLOR_SEARCH_PILL = 0xFF151928.toInt()
            COLOR_SEARCH_PILL_STROKE = 0
            COLOR_GRANT_FILL = 0x991A1E2E.toInt()
            COLOR_TEXT_PRIMARY = 0xFFFAFAFA.toInt()
            COLOR_TEXT_SECONDARY = 0xFF9A9FAE.toInt()
            COLOR_SWITCH_TRACK_OFF = 0xFF525668.toInt()
            COLOR_GRAPHIC_BG = 0xFF121624.toInt()
            COLOR_GRAPHIC_INACTIVE_FILL = 0xFF262B3E.toInt()
            COLOR_GRAPHIC_INACTIVE_STROKE = 0xFF565D78.toInt()
        } else {
            COLOR_BG_NAVY = 0xFFF2F2F6.toInt()
            COLOR_BG_SCRIM = 0xCCF2F2F6.toInt()
            COLOR_BG_SCRIM_NO_BLUR = 0xF0F2F2F6.toInt()
            COLOR_CARD_BG = 0xFFFFFFFF.toInt()
            COLOR_SUBTLE_SURFACE = 0xFFE8EBF3.toInt()
            COLOR_HAIRLINE_DIVIDER = 0xFFE2E5EE.toInt()
            COLOR_FLOATING_MENU = 0xFFFFFFFF.toInt()
            COLOR_FLOATING_STROKE = 0xFFC2C7D6.toInt()
            COLOR_SEARCH_PILL = 0xFFFFFFFF.toInt()
            COLOR_SEARCH_PILL_STROKE = 0xFFDCE0EA.toInt()
            COLOR_GRANT_FILL = 0xBFE8EBF3.toInt()
            COLOR_TEXT_PRIMARY = 0xFF15171F.toInt()
            COLOR_TEXT_SECONDARY = 0xFF5C6170.toInt()
            COLOR_SWITCH_TRACK_OFF = 0xFF8D92A0.toInt()
            COLOR_GRAPHIC_BG = 0xFFE6E9F2.toInt()
            COLOR_GRAPHIC_INACTIVE_FILL = 0xFFD2D7E4.toInt()
            COLOR_GRAPHIC_INACTIVE_STROKE = 0xFF8E96AC.toInt()
        }
        COLOR_SWITCH_THUMB = 0xFFFFFFFF.toInt()
        COLOR_SWITCH_TRACK_ON = trackOn
        COLOR_ONEUI_BLUE = accent
        COLOR_ONEUI_BLUE_TINT = accentTint
    }

    data class FloatingMenuItem(
        val title: String,
        val subtitle: String? = null,
        val icon: Drawable? = null,
        val selected: Boolean = false,
        val onClick: () -> Unit
    )

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun dpF(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )

    fun screenMarginPx(context: Context): Int {
        val isWide = SoloLauncher.isWideScreen(context)
        return dp(context, if (isWide) 24 else 16)
    }

    /**
     * PATCH 0.1.9: Configures translucent panel windows (PickerActivity, StageActivity) for Android
     * cross-window blur on API 31+ with a single translucent dark navy scrim carried by the window
     * background drawable (`DecorView` composites `BackgroundBlurDrawable` underneath the window
     * background drawable in a `LayerDrawable`, while `FLAG_BLUR_BEHIND` + `blurBehindRadius`
     * instructs SurfaceFlinger's Dimmer to blur behind the translucent window surface).
     *
     * Key fixes over 0.1.2B / 0.1.7:
     * - Keeps the root view background transparent (`Color.TRANSPARENT`) and puts the translucent
     *   scrim (`ColorDrawable`) on the window background itself, avoiding extra overdraw and
     *   giving `DecorView` a non-zero-alpha original background drawable without tearing down the
     *   `LayerDrawable` on repeated `onResume()` calls.
     * - Always installs `setBackgroundBlurRadius(PANEL_BLUR_RADIUS_PX)`, `FLAG_BLUR_BEHIND`, and
     *   `lp.blurBehindRadius = PANEL_BLUR_RADIUS_PX` atomically on API 31+ (re-applied once
     *   `ViewRootImpl` attaches) instead of gating the blur request to 0 or letting the
     *   `addCrossWindowBlurEnabledListener` overwrite the radius back to 0.
     * - Uses `isCrossWindowBlurEnabled` / `addCrossWindowBlurEnabledListener` strictly to select
     *   between `COLOR_BG_SCRIM` (~65% alpha when blur is active) and `COLOR_BG_SCRIM_NO_BLUR`
     *   (~85% alpha when the OS has cross-window blur disabled), falling back to solid `#0B0E1A`
     *   only when hardware acceleration is unavailable or window setup throws.
     */
    @Suppress("DEPRECATION")
    fun applyTranslucentBlurWindow(
        activity: Activity,
        rootView: View?,
        attachListener: Boolean = false
    ) {
        val win = activity.window
        if (win == null) {
            rootView?.setBackgroundColor(COLOR_BG_NAVY)
            return
        }

        try {
            win.statusBarColor = Color.TRANSPARENT
            win.navigationBarColor = Color.TRANSPARENT
            win.setFormat(PixelFormat.TRANSLUCENT)

            if (rootView != null && rootView.isAttachedToWindow && !rootView.isHardwareAccelerated) {
                // Hardware acceleration unavailable in this window state: fall back to solid #0B0E1A
                applyApi31WindowBlur(win, 0)
                setWindowScrimColor(win, COLOR_BG_NAVY)
                rootView.setBackgroundColor(COLOR_BG_NAVY)
                return
            }

            val blurEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activity.windowManager?.isCrossWindowBlurEnabled == true
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }

            rootView?.setBackgroundColor(Color.TRANSPARENT)
            setWindowScrimColor(win, if (blurEnabled) COLOR_BG_SCRIM else COLOR_BG_SCRIM_NO_BLUR)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applyApi31WindowBlur(win, PANEL_BLUR_RADIUS_PX)

                if (attachListener && rootView != null) {
                    val blurListener = Consumer<Boolean> { enabled ->
                        if (activity.isFinishing || activity.isDestroyed) return@Consumer
                        try {
                            if (rootView.isAttachedToWindow && !rootView.isHardwareAccelerated) {
                                applyApi31WindowBlur(win, 0)
                                setWindowScrimColor(win, COLOR_BG_NAVY)
                                rootView.setBackgroundColor(COLOR_BG_NAVY)
                            } else {
                                rootView.setBackgroundColor(Color.TRANSPARENT)
                                setWindowScrimColor(
                                    win,
                                    if (enabled == true) COLOR_BG_SCRIM else COLOR_BG_SCRIM_NO_BLUR
                                )
                                applyApi31WindowBlur(win, PANEL_BLUR_RADIUS_PX)
                            }
                        } catch (_: Throwable) {
                            setWindowScrimColor(win, COLOR_BG_NAVY)
                            rootView.setBackgroundColor(COLOR_BG_NAVY)
                        }
                    }

                    val attachStateListener = object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            if (!v.isHardwareAccelerated) {
                                try {
                                    applyApi31WindowBlur(win, 0)
                                    setWindowScrimColor(win, COLOR_BG_NAVY)
                                } catch (_: Throwable) {
                                }
                                v.setBackgroundColor(COLOR_BG_NAVY)
                                return
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try {
                                    val liveBlurEnabled =
                                        activity.windowManager?.isCrossWindowBlurEnabled == true
                                    v.setBackgroundColor(Color.TRANSPARENT)
                                    setWindowScrimColor(
                                        win,
                                        if (liveBlurEnabled) COLOR_BG_SCRIM else COLOR_BG_SCRIM_NO_BLUR
                                    )
                                    // Re-apply now that ViewRootImpl is attached so DecorView
                                    // creates BackgroundBlurDrawable and WindowManager gets live attrs.
                                    applyApi31WindowBlur(win, PANEL_BLUR_RADIUS_PX)
                                    activity.windowManager?.addCrossWindowBlurEnabledListener(blurListener)
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try {
                                    activity.windowManager?.removeCrossWindowBlurEnabledListener(blurListener)
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                    rootView.addOnAttachStateChangeListener(attachStateListener)
                    if (rootView.isAttachedToWindow) {
                        attachStateListener.onViewAttachedToWindow(rootView)
                    }
                }
            } else {
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        } catch (_: Throwable) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    applyApi31WindowBlur(win, 0)
                } else {
                    win.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
            } catch (_: Throwable) {
            }
            try {
                setWindowScrimColor(win, COLOR_BG_NAVY)
            } catch (_: Throwable) {
            }
            rootView?.setBackgroundColor(COLOR_BG_NAVY)
        }
    }

    /**
     * PATCH 0.1.9: Clears blur and scrim on a translucent window (used by StageActivity's over-app
     * mode while capturing the single underlying screenshot frame so the Stage itself is invisible).
     */
    @Suppress("DEPRECATION")
    fun clearTranslucentBlurWindow(activity: Activity) {
        val win = activity.window ?: return
        try {
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.statusBarColor = Color.TRANSPARENT
            win.navigationBarColor = Color.TRANSPARENT
            win.setFormat(PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applyApi31WindowBlur(win, 0)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Updates the window background scrim color only when it actually differs from the current
     * window background drawable, avoiding unnecessary `DecorView` `LayerDrawable` churn on
     * repeated `onResume()` / `onMultiWindowModeChanged()` calls.
     */
    private fun setWindowScrimColor(win: Window, color: Int) {
        val decorBg = win.peekDecorView()?.background
        val existingColor = when (decorBg) {
            is ColorDrawable -> decorBg.color
            is LayerDrawable -> {
                var found: Int? = null
                for (i in 0 until decorBg.numberOfLayers) {
                    val layer = decorBg.getDrawable(i)
                    if (layer is ColorDrawable) {
                        found = layer.color
                        break
                    }
                }
                found
            }
            else -> null
        }
        if (existingColor != color) {
            win.setBackgroundDrawable(ColorDrawable(color))
        }
    }

    @Suppress("DEPRECATION")
    private fun applyApi31WindowBlur(win: Window, radiusPx: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = radiusPx.coerceAtLeast(0)
        val enable = radius > 0
        win.setBackgroundBlurRadius(radius)
        val lp = win.attributes ?: WindowManager.LayoutParams()
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = if (enable) {
            (lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        } else {
            lp.flags and
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv() and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
        lp.blurBehindRadius = radius
        win.attributes = lp
    }

    /**
     * PATCH 0.1.9: Mirror of the 0.1.8 bottom navigationBars inset listener for top header
     * containers. Adds the status bar top inset on top of the view's base top padding so
     * header rows never collide with the status bar under Android 15/16 edge-to-edge enforcement.
     * Insets are returned unconsumed.
     */
    fun applyTopStatusBarInsetPadding(view: View) {
        val baseTopPad = view.paddingTop
        view.setOnApplyWindowInsetsListener { v, insets ->
            val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetTop
            }
            v.setPadding(
                v.paddingLeft,
                baseTopPad + topInset,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }
    }

    fun roundedBg(
        context: Context,
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != 0 && strokeWidthDp > 0) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    /**
     * Creates a 26dp-radius One UI grouped card (#1A1E2E) for settings-style rows.
     * Rows inside use horizontal padding 20dp and hairline dividers between rows.
     */
    fun createGroupedCard(
        context: Context,
        bottomMarginDp: Int = 14,
        verticalPadDp: Int = 6
    ): LinearLayout {
        val vPad = dp(context, verticalPadDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(context, COLOR_CARD_BG, 26)
            setPadding(0, vPad, 0, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, bottomMarginDp)
            }
        }
    }

    /**
     * Creates a 26dp-radius One UI card container with comfortable inner padding.
     */
    fun createFocusBlock(context: Context, bottomMarginDp: Int = 14): LinearLayout {
        val pad = dp(context, if (SoloLauncher.isWideScreen(context)) 20 else 18)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(context, COLOR_CARD_BG, 26)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, bottomMarginDp)
            }
        }
    }

    /**
     * Creates an unbordered inner surface block (no boxed-border-inside-box).
     */
    fun createNestedCard(
        context: Context,
        radiusDp: Int = 18,
        bottomMarginDp: Int = 8,
        accentStroke: Boolean = false
    ): LinearLayout {
        val pad = dp(context, 14)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                context = context,
                fillColor = if (accentStroke) COLOR_ONEUI_BLUE_TINT else COLOR_SUBTLE_SURFACE,
                radiusDp = radiusDp
            )
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, bottomMarginDp)
            }
        }
    }

    /**
     * Hairline divider used ONLY between rows inside a grouped card.
     */
    fun createHairlineDivider(context: Context, horizontalInsetDp: Int = 20): View {
        val inset = dp(context, horizontalInsetDp)
        val heightPx = dp(context, 1).coerceAtLeast(1)
        return View(context).apply {
            setBackgroundColor(COLOR_HAIRLINE_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            ).apply {
                marginStart = inset
                marginEnd = inset
            }
        }
    }

    /**
     * One UI settings screen top bar: `< Title` on the deep navy background (no outlined pill button).
     */
    fun createSettingsTopBar(
        activity: Activity,
        title: String,
        onBack: () -> Unit = { activity.finish() }
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(activity, 14)
            }
            addView(BackChevronView(activity, onClick = onBack))
            addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(activity, 8), 0, 0, 0)
                }
            )
        }
    }

    /**
     * One UI unboxed group label above a card when needed.
     */
    fun createSectionHeader(
        context: Context,
        title: String,
        subtitle: String? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 10)
            }
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                    setTypeface(typeface, Typeface.BOLD)
                }
            )
            if (!subtitle.isNullOrBlank()) {
                addView(
                    TextView(context).apply {
                        text = subtitle
                        setTextColor(COLOR_TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(context, 3), 0, 0)
                    }
                )
            }
        }
    }

    /**
     * Creates a One UI settings-style row inside a grouped card:
     * Title (white, or Samsung blue when `titleAccent = true` for active/link rows),
     * optional gray subtitle underneath (or blue when `subtitleAccent = true`),
     * optional leading icon/graphic view, and optional trailing view (OneUiSwitch, CheckmarkView, etc.).
     */
    fun createSettingsRow(
        context: Context,
        title: String,
        subtitle: String? = null,
        leadingView: View? = null,
        trailingView: View? = null,
        titleAccent: Boolean = false,
        subtitleAccent: Boolean = false,
        horizontalPadDp: Int = 20,
        verticalPadDp: Int = 14,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val hPad = dp(context, horizontalPadDp)
        val vPad = dp(context, verticalPadDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            if (leadingView != null) {
                val lp = (leadingView.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                lp.marginEnd = dp(context, 14)
                leadingView.layoutParams = lp
                addView(leadingView)
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (trailingView != null) {
                        marginEnd = dp(context, 12)
                    }
                }
            }

            textCol.addView(
                TextView(context).apply {
                    text = title
                    setTextColor(if (titleAccent) COLOR_ONEUI_BLUE else COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    if (titleAccent) {
                        setTypeface(typeface, Typeface.BOLD)
                    }
                }
            )

            if (!subtitle.isNullOrBlank()) {
                textCol.addView(
                    TextView(context).apply {
                        text = subtitle
                        setTextColor(if (subtitleAccent) COLOR_ONEUI_BLUE else COLOR_TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(context, 3), 0, 0)
                    }
                )
            }

            addView(textCol)

            if (trailingView != null) {
                addView(trailingView)
            }

            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
    }

    /**
     * Creates a quiet button without any blue outline.
     * Samsung blue (#0381FE) is used ONLY when `primary = true` (active/selected state).
     */
    fun createButton(
        context: Context,
        label: String,
        primary: Boolean = true,
        compact: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val vPad = dp(context, if (compact) 8 else 12)
        val hPad = dp(context, if (compact) 12 else 16)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            // PATCH 0.2.0 (design pass on-accent rule): a primary button's text
            // is white when white contrasts the accent at 3.7:1 or better, else
            // the dark ink #15171F, so bright custom/system accents never carry
            // illegible white text. Default accent keeps white, as before.
            setTextColor(if (primary) DuoTheme.onAccent(COLOR_ONEUI_BLUE) else COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14.5f)
            setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            background = if (primary) {
                roundedBg(context, COLOR_ONEUI_BLUE, if (compact) 16 else 22)
            } else {
                roundedBg(
                    context = context,
                    fillColor = COLOR_SUBTLE_SURFACE,
                    radiusDp = if (compact) 16 else 22
                )
            }
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /**
     * Creates a quiet plain text link/action (no pill, no outline).
     */
    fun createTextAction(
        context: Context,
        label: String,
        activeOrLink: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val hPad = dp(context, 10)
        val vPad = dp(context, 6)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (activeOrLink) COLOR_ONEUI_BLUE else COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            if (activeOrLink) {
                setTypeface(typeface, Typeface.BOLD)
            }
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /**
     * Shows a Samsung One UI floating rounded menu card (approx 22dp radius, #252A3E surface,
     * plain rows, check mark on the selected item, no title bar) matching
     * `design-reference/oneui-drawer-sort-menu.png`.
     */
    fun showFloatingMenu(
        activity: Activity,
        anchor: View? = null,
        items: List<FloatingMenuItem>
    ) {
        if (activity.isFinishing || activity.isDestroyed || items.isEmpty()) return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                context = activity,
                fillColor = COLOR_FLOATING_MENU,
                radiusDp = 22,
                strokeColor = COLOR_FLOATING_STROKE,
                strokeWidthDp = 1
            )
            val vPad = dp(activity, 8)
            setPadding(0, vPad, 0, vPad)
            elevation = dpF(activity, 12f)
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        for (item in items) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hPad = dp(activity, 20)
                val vPad = dp(activity, 13)
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                if (item.icon != null) {
                    addView(
                        ImageView(activity).apply {
                            setImageDrawable(item.icon)
                            val sz = dp(activity, 28)
                            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                                marginEnd = dp(activity, 14)
                            }
                        }
                    )
                }

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginEnd = dp(activity, 12)
                    }
                }
                textCol.addView(
                    TextView(activity).apply {
                        text = item.title
                        setTextColor(
                            if (item.selected) COLOR_TEXT_PRIMARY else COLOR_TEXT_PRIMARY
                        )
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        if (item.selected) {
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    }
                )
                if (!item.subtitle.isNullOrBlank()) {
                    textCol.addView(
                        TextView(activity).apply {
                            text = item.subtitle
                            setTextColor(COLOR_TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, dp(activity, 2), 0, 0)
                        }
                    )
                }
                addView(textCol)

                if (item.selected) {
                    addView(CheckmarkView(activity, color = COLOR_TEXT_PRIMARY, sizeDp = 20))
                }

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        dialog.dismiss()
                    } catch (_: Throwable) {
                    }
                    item.onClick()
                }
            }
            listContainer.addView(row)
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(listContainer)
        }
        card.addView(scroll)
        dialog.setContentView(card)

        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = activity.resources.displayMetrics
            val menuWidth = if (anchor != null) {
                dp(activity, 236).coerceAtMost((dm.widthPixels * 0.78f).toInt())
            } else {
                dp(activity, 310).coerceAtMost((dm.widthPixels * 0.88f).toInt())
            }
            val lp = win.attributes ?: WindowManager.LayoutParams()
            lp.width = menuWidth
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            if (anchor != null) {
                val loc = IntArray(2)
                anchor.getLocationOnScreen(loc)
                lp.gravity = Gravity.TOP or Gravity.END
                lp.x = dp(activity, 16)
                lp.y = (loc[1] + anchor.height - dp(activity, 12)).coerceAtLeast(dp(activity, 24))
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            } else {
                lp.gravity = Gravity.CENTER
                lp.dimAmount = 0.45f
                win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            win.attributes = lp
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * One UI Pill Switch (light blue-slate track with crisp white round thumb when ON,
     * dark gray pill track with white thumb when OFF, matching `oneui-home-settings.png`).
     * Extends `android.widget.Switch` so `isChecked` and `setOnCheckedChangeListener` work
     * identically to the framework widget.
     */
    class OneUiSwitch(context: Context) : Switch(context) {

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SWITCH_THUMB
            style = Paint.Style.FILL
        }
        private val trackRect = RectF()
        private val switchW = dp(context, 46)
        private val switchH = dp(context, 26)

        init {
            textOn = ""
            textOff = ""
            showText = false
            background = null
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(switchW, switchH)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val r = h / 2f
            trackRect.set(0f, 0f, w, h)
            trackPaint.color = if (isChecked) COLOR_SWITCH_TRACK_ON else COLOR_SWITCH_TRACK_OFF
            canvas.drawRoundRect(trackRect, r, r, trackPaint)

            val pad = dpF(context, 3.5f)
            val thumbR = (h / 2f) - pad
            val cx = if (isChecked) (w - r) else r
            val cy = h / 2f
            canvas.drawCircle(cx, cy, thumbR, thumbPaint)
        }
    }

    /**
     * Clean thin `<` back chevron matching `oneui-home-settings.png`.
     */
    class BackChevronView(
        context: Context,
        onClick: () -> Unit
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            style = Paint.Style.STROKE
            strokeWidth = dpF(context, 2.1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        init {
            val sz = dp(context, 36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { onClick() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            path.reset()
            path.moveTo(w * 0.62f, h * 0.24f)
            path.lineTo(w * 0.34f, h * 0.50f)
            path.lineTo(w * 0.62f, h * 0.76f)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Three vertical dots (`⋮`) overflow icon matching `oneui-app-drawer.png`.
     */
    class ThreeDotMenuView(
        context: Context,
        onClick: (View) -> Unit
    ) : View(context) {

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            style = Paint.Style.FILL
        }

        init {
            val sz = dp(context, 36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            isClickable = true
            isFocusable = true
            contentDescription = "More options"
            setOnClickListener { onClick(this) }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val h = height.toFloat()
            val r = dpF(context, 1.9f)
            canvas.drawCircle(cx, h * 0.28f, r, dotPaint)
            canvas.drawCircle(cx, h * 0.50f, r, dotPaint)
            canvas.drawCircle(cx, h * 0.72f, r, dotPaint)
        }
    }

    /**
     * Magnifier search icon matching the bottom search pill in `oneui-app-drawer.png`.
     */
    class MagnifierIconView(
        context: Context,
        sizeDp: Int = 20
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            style = Paint.Style.STROKE
            strokeWidth = dpF(context, 1.9f)
            strokeCap = Paint.Cap.ROUND
        }

        /**
         * PATCH 0.2.0 (design pass search treatment): the magnifier is neutral
         * at rest and tints to the accent only while search is focused or has
         * a query; the pill itself never takes an accent fill or outline.
         */
        var iconTint: Int
            get() = paint.color
            set(value) {
                if (paint.color != value) {
                    paint.color = value
                    invalidate()
                }
            }

        init {
            val sz = dp(context, sizeDp)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.44f
            val cy = h * 0.44f
            val r = w * 0.28f
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, w * 0.84f, h * 0.84f, paint)
        }
    }

    /**
     * Clean thin check mark (`✓`) matching `oneui-drawer-sort-menu.png`.
     */
    class CheckmarkView(
        context: Context,
        color: Int = COLOR_TEXT_PRIMARY,
        sizeDp: Int = 20
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dpF(context, 2.0f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        init {
            val sz = dp(context, sizeDp)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            path.reset()
            path.moveTo(w * 0.18f, h * 0.54f)
            path.lineTo(w * 0.42f, h * 0.76f)
            path.lineTo(w * 0.84f, h * 0.26f)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Custom canvas view that draws the two-panel icon with either the LEFT panel,
     * RIGHT panel, or BOTH panels highlighted in One UI Blue (#0381FE).
     */
    class PanelGraphicView(
        context: Context,
        var highlightLeft: Boolean,
        var highlightRight: Boolean,
        sizeDp: Int = 36
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GRAPHIC_BG
            style = Paint.Style.FILL
        }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ONEUI_BLUE
            style = Paint.Style.FILL
        }
        private val inactiveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GRAPHIC_INACTIVE_FILL
            style = Paint.Style.FILL
        }
        private val inactiveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GRAPHIC_INACTIVE_STROKE
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat().coerceAtLeast(2f)
        }
        private val rect = RectF()

        init {
            val px = dp(context, sizeDp)
            layoutParams = LinearLayout.LayoutParams(px, px)
        }

        fun setSide(side: DuoPrefs.PanelSide) {
            highlightLeft = (side == DuoPrefs.PanelSide.LEFT)
            highlightRight = (side == DuoPrefs.PanelSide.RIGHT)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val outerR = w * 0.24f
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, outerR, outerR, bgPaint)

            val pad = w * 0.14f
            val gap = w * 0.07f
            val panelW = (w - pad * 2f - gap) / 2f
            val panelTop = h * 0.18f
            val panelBottom = h * 0.82f
            val panelR = panelW * 0.28f

            // Left panel
            rect.set(pad, panelTop, pad + panelW, panelBottom)
            if (highlightLeft) {
                canvas.drawRoundRect(rect, panelR, panelR, activePaint)
            } else {
                canvas.drawRoundRect(rect, panelR, panelR, inactiveFillPaint)
                canvas.drawRoundRect(rect, panelR, panelR, inactiveStrokePaint)
            }

            // Right panel
            val rightStart = pad + panelW + gap
            rect.set(rightStart, panelTop, rightStart + panelW, panelBottom)
            if (highlightRight) {
                canvas.drawRoundRect(rect, panelR, panelR, activePaint)
            } else {
                canvas.drawRoundRect(rect, panelR, panelR, inactiveFillPaint)
                canvas.drawRoundRect(rect, panelR, panelR, inactiveStrokePaint)
            }
        }
    }
}
