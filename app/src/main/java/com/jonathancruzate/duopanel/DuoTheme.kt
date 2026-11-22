package com.jonathancruzate.duopanel

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build

/**
 * PATCH 0.2.0: Appearance resolution — Theme (System / Light / Dark) plus the
 * accent Color themes mirrored from the Meta Muse Widget.
 *
 * The widget's model (WidgetPrefs + BarStyle.accent in the Meta Muse Widget):
 * - "oneui"    -> its fixed One UI accent. DuoPanel's equivalent is its own
 *                 Samsung blue #0381FE, so the out-of-box look is unchanged.
 * - "system"   -> the phone's theme colors: android.R.color.system_accent1_600
 *                 on API 31+, fixed accent below 31.
 * - "stephany" -> the sweater pink: #B46C5D on light, #C98374 on dark
 *                 (the widget's values / values-night stephany_accent).
 * - "custom"   -> HSV from custom_hue (default 205) and custom_sat (default 70,
 *                 scaled x1.15, capped at 1.0), value 0.92 on dark / 0.40 on
 *                 light (the widget's night/day value rule, applied here to
 *                 DuoPanel's effective palette instead of the device mode).
 *
 * Every activity calls [applyTo] before building its views (and Settings calls
 * it again before each rebuild), which resolves the effective palette and
 * pushes it into [OneUiViews]' palette fields. All view builders read those
 * fields at construction/draw time, so a palette swap needs no other wiring.
 */
object DuoTheme {

    /** DuoPanel's default accent: Samsung blue #0381FE (unchanged from 0.1.x). */
    const val ACCENT_ONEUI: Int = 0xFF0381FE.toInt()

    /** Stephany pink on light palettes (widget stephany_accent, values). */
    const val ACCENT_STEPHANY_LIGHT: Int = 0xFFB46C5D.toInt()

    /** Stephany pink on dark palettes (widget stephany_accent, values-night). */
    const val ACCENT_STEPHANY_DARK: Int = 0xFFC98374.toInt()

    fun isDeviceNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Effective palette darkness after the Theme option is applied. */
    fun isDark(context: Context): Boolean = when (DuoPrefs.getThemeMode(context)) {
        DuoPrefs.ThemeMode.SYSTEM -> isDeviceNight(context)
        DuoPrefs.ThemeMode.LIGHT -> false
        DuoPrefs.ThemeMode.DARK -> true
    }

    /** The resolved accent for the chosen Color option and effective palette. */
    fun accent(context: Context): Int = accentFor(context, DuoPrefs.getColorTheme(context), isDark(context))

    /** The accent one Color option resolves to under a given palette darkness. */
    fun accentFor(context: Context, theme: DuoPrefs.ColorTheme, dark: Boolean): Int {
        return when (theme) {
            DuoPrefs.ColorTheme.ONEUI -> ACCENT_ONEUI
            DuoPrefs.ColorTheme.SYSTEM -> systemAccent(context, dark) ?: ACCENT_ONEUI
            DuoPrefs.ColorTheme.STEPHANY -> if (dark) ACCENT_STEPHANY_DARK else ACCENT_STEPHANY_LIGHT
            DuoPrefs.ColorTheme.CUSTOM -> {
                val hue = DuoPrefs.getCustomHue(context).toFloat()
                var sat = minOf(1f, DuoPrefs.getCustomSat(context) / 100f * 1.15f)
                if (!dark) {
                    Color.HSVToColor(floatArrayOf(hue, sat, 0.40f))
                } else {
                    // DESIGN-PASS-0.2.0 dark contrast-floor guard: deep indigo
                    // hues (225-260 deg) at high saturation land under 3:1 on
                    // the dark card; iteratively desaturate (floor 0.35) with
                    // value raised to 0.95 until the accent reads at 3.5:1.
                    var color = Color.HSVToColor(floatArrayOf(hue, sat, 0.92f))
                    var guard = 0
                    while (contrastRatio(color, 0xFF1A1E2E.toInt()) < 3.5f &&
                        sat > 0.35f && guard < 12
                    ) {
                        sat = maxOf(0.35f, sat - 0.05f)
                        color = Color.HSVToColor(floatArrayOf(hue, sat, 0.95f))
                        guard++
                    }
                    color
                }
            }
        }
    }

    /**
     * The phone's theme color (Material You / Samsung palette), API 31+.
     * DESIGN-PASS-0.2.0: the light palette reads system_accent1_600 (Monet
     * Tone 40, calibrated for light surfaces, as the widget does); the dark
     * palette reads system_accent1_300 (Tone 70), because Tone 40 measures
     * only ~2.55:1 on the dark card and fails the 3:1 UI floor.
     */
    fun systemAccent(context: Context, dark: Boolean): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            @Suppress("NewApi")
            context.getColor(
                if (dark) android.R.color.system_accent1_300
                else android.R.color.system_accent1_600
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Soft accent tint fill. DESIGN-PASS-0.2.0: 14% alpha (0x24) on the dark
     * palette (unchanged from 0.1.x), 12% (0x1F) on the light palette so dark
     * accents stay an airy wash on white cards.
     */
    fun accentTint(accent: Int, dark: Boolean): Int =
        ((if (dark) 0x24 else 0x1F) shl 24) or (accent and 0x00FFFFFF)

    /**
     * Switch ON track (DESIGN-PASS-0.2.0 derivation). The default One UI
     * accent keeps One UI's native tracks (dark #8FA8D8, light #0381FE).
     * Non-default accents derive from the accent's HSV: dark desaturates to a
     * calm pastel (S capped via min(S*0.55, 0.42), V 0.78); light keeps the
     * saturation and clamps value into [0.55, 0.75].
     */
    fun switchTrackOn(context: Context, accent: Int, dark: Boolean): Int {
        if (DuoPrefs.getColorTheme(context) == DuoPrefs.ColorTheme.ONEUI) {
            return if (dark) 0xFF8FA8D8.toInt() else ACCENT_ONEUI
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        return if (dark) {
            Color.HSVToColor(floatArrayOf(hsv[0], minOf(hsv[1] * 0.55f, 0.42f), 0.78f))
        } else {
            Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2].coerceIn(0.55f, 0.75f)))
        }
    }

    /**
     * Foreground to place ON an accent fill (DESIGN-PASS-0.2.0 on-accent
     * rule): white when it contrasts the accent at 3.7:1 or better, else the
     * dark ink #15171F. Keeps white on the default blue and Stephany pink,
     * switches to ink on bright system/custom accents.
     */
    fun onAccent(accent: Int): Int =
        if (contrastRatio(Color.WHITE, accent) >= 3.7f) Color.WHITE else 0xFF15171F.toInt()

    /** WCAG contrast ratio between two colors (1.0..21.0). */
    fun contrastRatio(a: Int, b: Int): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return ((lighter + 0.05) / (darker + 0.05)).toFloat()
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    /**
     * Resolves the current Appearance prefs and pushes the palette into
     * [OneUiViews]. Also aligns an Activity's window background with the
     * palette's solid background so solid surfaces never flash the dark
     * style background under the Light palette, and flips the system bar
     * icon appearance with the palette (dark icons on the Light palette).
     */
    fun applyTo(context: Context) {
        val dark = isDark(context)
        val accent = accent(context)
        OneUiViews.applyPalette(
            dark = dark,
            accent = accent,
            accentTint = accentTint(accent, dark),
            trackOn = switchTrackOn(context, accent, dark)
        )
        if (context is Activity) {
            try {
                context.window?.setBackgroundDrawable(ColorDrawable(OneUiViews.COLOR_BG_NAVY))
            } catch (_: Throwable) {
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    context.window?.insetsController?.setSystemBarsAppearance(
                        if (dark) 0 else mask, mask
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }
}
