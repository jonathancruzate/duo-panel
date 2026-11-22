package com.jonathancruzate.duopanel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SharedPreferences persistence for DuoPanel:
 * - Split mode (AUTO wide >= 600dp / ALWAYS for S24+ proxy & force split / OFF)
 * - Preferred side (LEFT / RIGHT; top/bottom in portrait)
 * - Picker auto-replace on second pick vs stays alive
 * - Favorites set for the One UI Picker
 * - Saved per-app Solo shortcuts and two-app Pair shortcuts
 * - Per-package remembered winning Strategy (A / B / C)
 * - Launch Lab diagnostic attempt log with exact flag hex bits
 */
object DuoPrefs {

    private const val PREFS_NAME = "duopanel_prefs"

    private const val KEY_SPLIT_MODE = "split_mode"
    private const val KEY_PREFERRED_SIDE = "preferred_side"
    private const val KEY_PICKER_AUTO_REPLACE = "picker_auto_replace"
    private const val KEY_PICKER_ON_FREE_PANEL = "picker_on_free_panel"
    private const val KEY_FLIP_SIDES = "flip_sides"
    private const val KEY_FLIP_CALIBRATED = "flip_calibrated"
    private const val KEY_FIRST_RUN_DISMISSED = "first_run_dismissed"
    private const val KEY_FAVORITES = "favorite_packages"
    private const val KEY_SOLO_SHORTCUTS = "saved_solo_shortcuts_json"
    private const val KEY_PAIR_SHORTCUTS = "saved_pair_shortcuts_json"
    private const val KEY_STRATEGY_PREFIX = "strategy_pkg_"
    // PATCH 0.1.10: persisted active-panel-app records (per PanelSide name suffix). The
    // SoloLauncher engine map is process memory only; these keys are its durable copy so the
    // companion pane can still name the other panel's app after the process is reaped.
    private const val KEY_ACTIVE_PANEL_APP_PREFIX = "active_panel_app_"
    private const val KEY_LAUNCH_LOGS = "launch_lab_logs_json"
    // PATCH 0.1.7: Picker display customization (One UI Home screen settings parity)
    private const val KEY_PICKER_APP_SIZE = "picker_app_size"
    private const val KEY_PICKER_SHOW_LABELS = "picker_show_labels"
    private const val KEY_PICKER_GRID = "picker_grid"
    private const val KEY_PICKER_SORT_ORDER = "picker_sort_order"
    private const val KEY_PICKER_CUSTOM_ORDER = "picker_custom_order_json"
    private const val KEY_PICKER_SHOW_SEARCH = "picker_show_search"
    // PATCH 0.1.7 Part B: Hide apps + notification badges
    private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
    private const val KEY_BADGES_ENABLED = "badges_enabled"
    private const val KEY_BADGE_STYLE = "badge_style"
    // PATCH 0.2.0: Appearance — Theme (System / Light / Dark) + Color (mirrors the
    // Meta Muse Widget's color themes in WidgetPrefs: oneui / system / stephany /
    // custom, with the widget's custom_hue / custom_sat keys and defaults).
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_COLOR_THEME = "color_theme"
    private const val KEY_CUSTOM_HUE = "custom_hue"
    private const val KEY_CUSTOM_SAT = "custom_sat"
    // PATCH 0.2.0: Picker sections (Recent apps / Frequently used / Favorites) —
    // user-arranged order + per-section visibility above the always-present All Apps.
    private const val KEY_PICKER_SECTIONS_ORDER = "picker_sections_order"
    private const val KEY_PICKER_SECTIONS_HIDDEN = "picker_sections_hidden"

    private const val MAX_LOG_ENTRIES = 60

    @Volatile
    var pickerOnFreePanel: Boolean = true
        private set

    @Volatile
    var flipSides: Boolean = false
        private set

    @Volatile
    var flipCalibrated: Boolean = false
        private set

    enum class PanelSide(val title: String, val oppositeTitle: String) {
        LEFT("Left Panel", "Right Panel"),
        RIGHT("Right Panel", "Left Panel");

        fun opposite(): PanelSide = if (this == LEFT) RIGHT else LEFT

        companion object {
            fun fromString(raw: String?, fallback: PanelSide = LEFT): PanelSide {
                return when (raw?.trim()?.uppercase(Locale.US)) {
                    "RIGHT", "R" -> RIGHT
                    "LEFT", "L" -> LEFT
                    else -> fallback
                }
            }
        }
    }

    enum class SplitMode(val label: String, val summary: String) {
        AUTO(
            "Auto (Wide >= 600dp)",
            "Recommended for Galaxy Z Fold8: splits on unfolded inner screen, opens fullscreen on cover screen."
        ),
        ALWAYS(
            "Always Try Split",
            "Recommended for Galaxy S24+ testing: attempts split-screen even on narrow (< 600dp) displays."
        ),
        OFF(
            "Off (Fullscreen Only)",
            "Disables multi-window splitting; all shortcuts open target apps normally fullscreen."
        );

        companion object {
            fun fromString(raw: String?): SplitMode {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
            }
        }
    }

    /**
     * PATCH 0.1.7: Picker grid icon size, mirroring One UI "App size on Home and Apps screens".
     * Calibrated 2026-11-21 against Jonathan's S24+ drawer screenshots (Medium and Large):
     * Large measures ~15-20% larger than Medium in the 4-column grid with labels scaling
     * slightly, so the steps are 48 / 56 / 66dp (about an 18% step) with label text
     * 11.5 / 12 / 13sp.
     */
    enum class AppSize(val label: String, val iconDp: Int, val labelSp: Float) {
        SMALL("Small", 48, 11.5f),
        MEDIUM("Medium", 56, 12f),
        LARGE("Large", 66, 13f);

        companion object {
            fun fromString(raw: String?): AppSize {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEDIUM
            }
        }
    }

    /** PATCH 0.1.7: Picker grid columns, mirroring One UI "Apps screen grid". AUTO keeps the width-derived behavior. */
    enum class PickerGrid(val label: String, val columns: Int?) {
        AUTO("Auto", null),
        FOUR("4 columns", 4),
        FIVE("5 columns", 5);

        companion object {
            fun fromString(raw: String?): PickerGrid {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
            }
        }
    }

    /** PATCH 0.2.0: Appearance theme mode. SYSTEM follows the device night mode. */
    enum class ThemeMode(val label: String, val summary: String) {
        SYSTEM("System", "Follow the device night mode"),
        LIGHT("Light", "Always the light palette"),
        DARK("Dark", "Always the dark palette");

        companion object {
            fun fromString(raw: String?): ThemeMode {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
            }
        }
    }

    /**
     * PATCH 0.2.0: Accent color themes, mirroring the Meta Muse Widget's color
     * themes (WidgetPrefs.THEME_* / BarStyle.accent) with the same stored values
     * and user-facing names: One UI (the widget default), System palette (the
     * phone's theme colors), Stephany pink, and Custom (hue + saturation).
     */
    enum class ColorTheme(val key: String, val label: String, val summary: String) {
        ONEUI("oneui", "One UI", "Samsung blue, DuoPanel's default accent"),
        SYSTEM("system", "System palette", "The phone's theme colors (Material You / Samsung color palette)"),
        STEPHANY("stephany", "Stephany pink", "The muted pink of Stephany's sweater"),
        CUSTOM("custom", "Custom", "Your own hue and saturation");

        companion object {
            fun fromKey(raw: String?): ColorTheme {
                return values().firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: ONEUI
            }
        }
    }

    /**
     * PATCH 0.2.0: The arrangeable picker sections above All Apps. All Apps is the
     * always-present base list and is deliberately NOT in this set: it is the full
     * catalog the other sections sample from, so it stays pinned last.
     */
    enum class PickerSection(val id: String, val title: String, val summary: String) {
        RECENT("recent", "Recent apps", "Most recently used apps first, from Android usage stats"),
        FREQUENT("frequent", "Frequently used", "Apps you spend the most time in, from Android usage stats"),
        FAVORITES("favorites", "Favorites", "Apps you starred in the picker");

        companion object {
            fun fromId(raw: String?): PickerSection? {
                return values().firstOrNull { it.id.equals(raw, ignoreCase = true) }
            }
        }
    }

    /** PATCH 0.1.7 Part B: Picker icon badge style, mirroring One UI "Badge style". */
    enum class BadgeStyle(val label: String) {
        DOT("Dot"),
        NUMBER("Number");

        companion object {
            fun fromString(raw: String?): BadgeStyle {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DOT
            }
        }
    }

    /** PATCH 0.1.7: Picker sort order, mirroring the One UI drawer sort menu. */
    enum class SortOrder(val label: String) {
        ALPHABETICAL("Alphabetical order"),
        CUSTOM("Custom order");

        companion object {
            fun fromString(raw: String?): SortOrder {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALPHABETICAL
            }
        }
    }

    enum class Strategy(val shortName: String, val description: String) {
        A("Strategy A", "Target normal (NEW_TASK), then Picker/second app adjacent (0x00100000 | FLAG_ACTIVITY_LAUNCH_ADJACENT)"),
        B("Strategy B", "Target itself with adjacent flags + primary split reflection fallback, then Picker/second app adjacent"),
        C("Strategy C", "Freeform half-screen bounds via public ActivityOptions.setLaunchBounds(Rect)"),
        D("Strategy D", "Graceful fullscreen launch with informative toast fallback");

        companion object {
            fun fromString(raw: String?): Strategy? {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
        }
    }

    data class SavedSoloShortcut(
        val packageName: String,
        val label: String,
        val side: PanelSide
    )

    data class SavedPairShortcut(
        val id: String,
        val primaryPackage: String,
        val primaryLabel: String,
        val secondaryPackage: String,
        val secondaryLabel: String
    )

    data class LaunchLogEntry(
        val timestamp: String,
        val flowOrStrategy: String,
        val side: String,
        val targetDesc: String,
        val flagsHex: String,
        val optionsInfo: String,
        val result: String
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { p ->
            pickerOnFreePanel = p.getBoolean(KEY_PICKER_ON_FREE_PANEL, true)
            flipSides = p.getBoolean(KEY_FLIP_SIDES, false)
            flipCalibrated = p.getBoolean(KEY_FLIP_CALIBRATED, false)
        }

    fun isPickerOnFreePanel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PICKER_ON_FREE_PANEL, true).also { pickerOnFreePanel = it }

    fun getPickerOnFreePanel(context: Context): Boolean = isPickerOnFreePanel(context)

    fun setPickerOnFreePanel(context: Context, enabled: Boolean) {
        pickerOnFreePanel = enabled
        prefs(context).edit().putBoolean(KEY_PICKER_ON_FREE_PANEL, enabled).apply()
    }

    fun isFlipSides(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLIP_SIDES, false).also { flipSides = it }

    fun getFlipSides(context: Context): Boolean = isFlipSides(context)

    fun setFlipSides(context: Context, flip: Boolean) {
        flipSides = flip
        prefs(context).edit().putBoolean(KEY_FLIP_SIDES, flip).apply()
    }

    fun isFlipCalibrated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLIP_CALIBRATED, false).also { flipCalibrated = it }

    fun getFlipCalibrated(context: Context): Boolean = isFlipCalibrated(context)

    fun setFlipCalibrated(context: Context, calibrated: Boolean) {
        flipCalibrated = calibrated
        prefs(context).edit().putBoolean(KEY_FLIP_CALIBRATED, calibrated).apply()
    }

    fun effectiveSide(requested: PanelSide): PanelSide =
        if (flipSides) requested.opposite() else requested

    fun effectiveSide(context: Context, requested: PanelSide): PanelSide =
        if (isFlipSides(context)) requested.opposite() else requested

    fun getSplitMode(context: Context): SplitMode =
        SplitMode.fromString(prefs(context).getString(KEY_SPLIT_MODE, SplitMode.AUTO.name))

    fun setSplitMode(context: Context, mode: SplitMode) {
        prefs(context).edit().putString(KEY_SPLIT_MODE, mode.name).apply()
    }

    fun getPreferredSide(context: Context): PanelSide =
        PanelSide.fromString(prefs(context).getString(KEY_PREFERRED_SIDE, PanelSide.LEFT.name))

    fun setPreferredSide(context: Context, side: PanelSide) {
        prefs(context).edit().putString(KEY_PREFERRED_SIDE, side.name).apply()
    }

    // PATCH 0.1.10: durable copy of SoloLauncher's active-panel-app records.
    fun setPersistedActivePanelApp(context: Context, side: PanelSide, packageName: String?) {
        val editor = prefs(context).edit()
        if (packageName.isNullOrBlank()) {
            editor.remove(KEY_ACTIVE_PANEL_APP_PREFIX + side.name)
        } else {
            editor.putString(KEY_ACTIVE_PANEL_APP_PREFIX + side.name, packageName)
        }
        editor.apply()
    }

    fun getPersistedActivePanelApp(context: Context, side: PanelSide): String? =
        prefs(context).getString(KEY_ACTIVE_PANEL_APP_PREFIX + side.name, null)
            ?.takeIf { it.isNotBlank() }

    fun clearPersistedActivePanelApps(context: Context) {
        val editor = prefs(context).edit()
        for (side in PanelSide.values()) {
            editor.remove(KEY_ACTIVE_PANEL_APP_PREFIX + side.name)
        }
        editor.apply()
    }

    fun isPickerAutoReplace(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PICKER_AUTO_REPLACE, true)

    fun setPickerAutoReplace(context: Context, autoReplace: Boolean) {
        prefs(context).edit().putBoolean(KEY_PICKER_AUTO_REPLACE, autoReplace).apply()
    }

    fun isFirstRunDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_RUN_DISMISSED, false)

    fun setFirstRunDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIRST_RUN_DISMISSED, dismissed).apply()
    }

    fun getFavorites(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_FAVORITES, null)
        return stored?.toSet() ?: emptySet()
    }

    fun isFavorite(context: Context, packageName: String): Boolean =
        getFavorites(context).contains(packageName)

    fun toggleFavorite(context: Context, packageName: String): Boolean {
        val current = getFavorites(context).toMutableSet()
        val nowFav = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFav
    }

    fun getRememberedStrategy(context: Context, packageName: String): Strategy? {
        val raw = prefs(context).getString(KEY_STRATEGY_PREFIX + packageName, null)
        return Strategy.fromString(raw)
    }

    fun setRememberedStrategy(context: Context, packageName: String, strategy: Strategy) {
        if (strategy == Strategy.D) return // Only remember split/half strategies A, B, C
        prefs(context).edit().putString(KEY_STRATEGY_PREFIX + packageName, strategy.name).apply()
    }

    fun getAllRememberedStrategies(context: Context): Map<String, Strategy> {
        val result = linkedMapOf<String, Strategy>()
        for ((key, value) in prefs(context).all) {
            if (key.startsWith(KEY_STRATEGY_PREFIX) && value is String) {
                val pkg = key.removePrefix(KEY_STRATEGY_PREFIX)
                val strategy = Strategy.fromString(value)
                if (strategy != null) {
                    result[pkg] = strategy
                }
            }
        }
        return result
    }

    fun clearAllStrategyMemory(context: Context): Int {
        val p = prefs(context)
        val keys = p.all.keys.filter { it.startsWith(KEY_STRATEGY_PREFIX) }
        val editor = p.edit()
        for (k in keys) {
            editor.remove(k)
        }
        editor.apply()
        return keys.size
    }

    fun getSavedSoloShortcuts(context: Context): List<SavedSoloShortcut> {
        val raw = prefs(context).getString(KEY_SOLO_SHORTCUTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedSoloShortcut>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("packageName")
                val label = obj.optString("label", pkg)
                val side = PanelSide.fromString(obj.optString("side", "LEFT"))
                if (pkg.isNotBlank()) {
                    list.add(SavedSoloShortcut(pkg, label, side))
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addSavedSoloShortcut(context: Context, shortcut: SavedSoloShortcut) {
        val current = getSavedSoloShortcuts(context).toMutableList()
        current.removeAll { it.packageName == shortcut.packageName && it.side == shortcut.side }
        current.add(0, shortcut)
        saveSoloShortcutsList(context, current)
    }

    fun removeSavedSoloShortcut(context: Context, packageName: String, side: PanelSide) {
        val current = getSavedSoloShortcuts(context).toMutableList()
        current.removeAll { it.packageName == packageName && it.side == side }
        saveSoloShortcutsList(context, current)
    }

    private fun saveSoloShortcutsList(context: Context, list: List<SavedSoloShortcut>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("packageName", item.packageName)
            obj.put("label", item.label)
            obj.put("side", item.side.name)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_SOLO_SHORTCUTS, arr.toString()).apply()
    }

    fun getSavedPairShortcuts(context: Context): List<SavedPairShortcut> {
        val raw = prefs(context).getString(KEY_PAIR_SHORTCUTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedPairShortcut>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val p1 = obj.optString("primaryPackage")
                val l1 = obj.optString("primaryLabel", p1)
                val p2 = obj.optString("secondaryPackage")
                val l2 = obj.optString("secondaryLabel", p2)
                if (p1.isNotBlank() && p2.isNotBlank()) {
                    list.add(SavedPairShortcut(id.ifBlank { "${p1}_$p2" }, p1, l1, p2, l2))
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addSavedPairShortcut(context: Context, pair: SavedPairShortcut) {
        val current = getSavedPairShortcuts(context).toMutableList()
        current.removeAll { it.id == pair.id }
        current.add(0, pair)
        savePairShortcutsList(context, current)
    }

    fun removeSavedPairShortcut(context: Context, id: String) {
        val current = getSavedPairShortcuts(context).toMutableList()
        current.removeAll { it.id == id }
        savePairShortcutsList(context, current)
    }

    private fun savePairShortcutsList(context: Context, list: List<SavedPairShortcut>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("primaryPackage", item.primaryPackage)
            obj.put("primaryLabel", item.primaryLabel)
            obj.put("secondaryPackage", item.secondaryPackage)
            obj.put("secondaryLabel", item.secondaryLabel)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_PAIR_SHORTCUTS, arr.toString()).apply()
    }

    // ------------------------------------------------------------------
    // PATCH 0.1.7: Picker display customization
    // ------------------------------------------------------------------

    fun getAppSize(context: Context): AppSize =
        AppSize.fromString(prefs(context).getString(KEY_PICKER_APP_SIZE, AppSize.MEDIUM.name))

    fun setAppSize(context: Context, size: AppSize) {
        prefs(context).edit().putString(KEY_PICKER_APP_SIZE, size.name).apply()
    }

    fun isShowLabels(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PICKER_SHOW_LABELS, true)

    fun setShowLabels(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_PICKER_SHOW_LABELS, show).apply()
    }

    fun getPickerGrid(context: Context): PickerGrid =
        PickerGrid.fromString(prefs(context).getString(KEY_PICKER_GRID, PickerGrid.AUTO.name))

    fun setPickerGrid(context: Context, grid: PickerGrid) {
        prefs(context).edit().putString(KEY_PICKER_GRID, grid.name).apply()
    }

    fun isShowSearchBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PICKER_SHOW_SEARCH, true)

    fun setShowSearchBar(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_PICKER_SHOW_SEARCH, show).apply()
    }

    fun getSortOrder(context: Context): SortOrder =
        SortOrder.fromString(prefs(context).getString(KEY_PICKER_SORT_ORDER, SortOrder.ALPHABETICAL.name))

    fun setSortOrder(context: Context, order: SortOrder) {
        prefs(context).edit().putString(KEY_PICKER_SORT_ORDER, order.name).apply()
    }

    /** Persisted custom package order (PATCH 0.1.7). Empty when never seeded. */
    fun getCustomOrder(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PICKER_CUSTOM_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i)
                if (pkg.isNotBlank() && !list.contains(pkg)) {
                    list.add(pkg)
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setCustomOrder(context: Context, order: List<String>) {
        val arr = JSONArray()
        for (pkg in order) {
            arr.put(pkg)
        }
        prefs(context).edit().putString(KEY_PICKER_CUSTOM_ORDER, arr.toString()).apply()
    }

    /** Seeds the custom order from the current alphabetical order if it has never been seeded. */
    fun ensureCustomOrderSeeded(context: Context, alphabeticalPackages: List<String>) {
        if (getCustomOrder(context).isEmpty() && alphabeticalPackages.isNotEmpty()) {
            setCustomOrder(context, alphabeticalPackages)
        }
    }

    /** "Reset custom order" (PATCH 0.1.7): reseeds the custom order from alphabetical. */
    fun resetCustomOrder(context: Context, alphabeticalPackages: List<String>) {
        setCustomOrder(context, alphabeticalPackages)
    }

    /**
     * Returns the package display order for the Picker given the alphabetical package list.
     * ALPHABETICAL: the input unchanged. CUSTOM: the persisted order, seeded from alphabetical
     * on first use, reconciled against the installed set (uninstalled packages drop out,
     * newly installed packages append at the end) and re-persisted when reconciliation
     * changes it.
     */
    fun applyDisplayOrder(context: Context, alphabeticalPackages: List<String>): List<String> {
        if (getSortOrder(context) != SortOrder.CUSTOM) {
            return alphabeticalPackages
        }
        val stored = getCustomOrder(context)
        if (stored.isEmpty()) {
            setCustomOrder(context, alphabeticalPackages)
            return alphabeticalPackages
        }
        val installed = alphabeticalPackages.toSet()
        val storedSet = stored.toSet()
        val reconciled = stored.filter { installed.contains(it) } +
            alphabeticalPackages.filter { !storedSet.contains(it) }
        if (reconciled != stored) {
            setCustomOrder(context, reconciled)
        }
        return reconciled
    }

    // ------------------------------------------------------------------
    // PATCH 0.1.7 Part B: Hide apps + notification badges
    // ------------------------------------------------------------------

    fun getHiddenPackages(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_HIDDEN_PACKAGES, null)
        return stored?.toSet() ?: emptySet()
    }

    fun isHidden(context: Context, packageName: String): Boolean =
        getHiddenPackages(context).contains(packageName)

    fun setHidden(context: Context, packageName: String, hidden: Boolean) {
        val current = getHiddenPackages(context).toMutableSet()
        if (hidden) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN_PACKAGES, current).apply()
    }

    fun setHiddenPackages(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_HIDDEN_PACKAGES, packages.toSet()).apply()
    }

    /** Badges default OFF (PATCH 0.1.7 Part B): they only make sense once access is granted. */
    fun isBadgesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BADGES_ENABLED, false)

    fun setBadgesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BADGES_ENABLED, enabled).apply()
    }

    fun getBadgeStyle(context: Context): BadgeStyle =
        BadgeStyle.fromString(prefs(context).getString(KEY_BADGE_STYLE, BadgeStyle.DOT.name))

    fun setBadgeStyle(context: Context, style: BadgeStyle) {
        prefs(context).edit().putString(KEY_BADGE_STYLE, style.name).apply()
    }

    // ------------------------------------------------------------------
    // PATCH 0.2.0: Appearance (Theme + Color) and Picker sections config
    // ------------------------------------------------------------------

    fun getThemeMode(context: Context): ThemeMode =
        ThemeMode.fromString(prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name))

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getColorTheme(context: Context): ColorTheme =
        ColorTheme.fromKey(prefs(context).getString(KEY_COLOR_THEME, ColorTheme.ONEUI.key))

    fun setColorTheme(context: Context, theme: ColorTheme) {
        prefs(context).edit().putString(KEY_COLOR_THEME, theme.key).apply()
    }

    /** Custom-theme hue, 0-359 (default 205, the Meta Muse Widget's default). */
    fun getCustomHue(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_HUE, 205).coerceIn(0, 359)

    fun setCustomHue(context: Context, hue: Int) {
        prefs(context).edit().putInt(KEY_CUSTOM_HUE, hue.coerceIn(0, 359)).apply()
    }

    /** Custom-theme saturation, 0-100 (default 70, the Meta Muse Widget's default). */
    fun getCustomSat(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_SAT, 70).coerceIn(0, 100)

    fun setCustomSat(context: Context, sat: Int) {
        prefs(context).edit().putInt(KEY_CUSTOM_SAT, sat.coerceIn(0, 100)).apply()
    }

    /**
     * The user's section order above All Apps. Default (PATCH 0.2.0): Recent apps,
     * Frequently used, Favorites. Stored as comma-joined section ids; unknown or
     * missing ids are reconciled against the full set on every read.
     */
    fun getSectionOrder(context: Context): List<PickerSection> {
        val defaultOrder = listOf(PickerSection.RECENT, PickerSection.FREQUENT, PickerSection.FAVORITES)
        val raw = prefs(context).getString(KEY_PICKER_SECTIONS_ORDER, null)
        if (raw.isNullOrBlank()) return defaultOrder
        val parsed = raw.split(",").mapNotNull { PickerSection.fromId(it.trim()) }.distinct()
        if (parsed.isEmpty()) return defaultOrder
        val missing = defaultOrder.filter { !parsed.contains(it) }
        return parsed + missing
    }

    fun setSectionOrder(context: Context, order: List<PickerSection>) {
        prefs(context).edit()
            .putString(KEY_PICKER_SECTIONS_ORDER, order.joinToString(",") { it.id })
            .apply()
    }

    fun isSectionVisible(context: Context, section: PickerSection): Boolean {
        val hidden = prefs(context).getStringSet(KEY_PICKER_SECTIONS_HIDDEN, null) ?: emptySet()
        return !hidden.contains(section.id)
    }

    fun setSectionVisible(context: Context, section: PickerSection, visible: Boolean) {
        val hidden = (prefs(context).getStringSet(KEY_PICKER_SECTIONS_HIDDEN, null) ?: emptySet())
            .toMutableSet()
        if (visible) {
            hidden.remove(section.id)
        } else {
            hidden.add(section.id)
        }
        prefs(context).edit().putStringSet(KEY_PICKER_SECTIONS_HIDDEN, hidden).apply()
    }

    fun recordLaunchLog(
        context: Context,
        flowOrStrategy: String,
        side: String,
        targetDesc: String,
        flagsHex: String,
        optionsInfo: String,
        result: String
    ) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LaunchLogEntry(
            timestamp = stamp,
            flowOrStrategy = flowOrStrategy,
            side = side,
            targetDesc = targetDesc,
            flagsHex = flagsHex,
            optionsInfo = optionsInfo,
            result = result
        )
        val current = getLaunchLogs(context).toMutableList()
        current.add(0, entry)
        val trimmed = if (current.size > MAX_LOG_ENTRIES) current.subList(0, MAX_LOG_ENTRIES) else current
        val arr = JSONArray()
        for (e in trimmed) {
            val obj = JSONObject()
            obj.put("timestamp", e.timestamp)
            obj.put("flowOrStrategy", e.flowOrStrategy)
            obj.put("side", e.side)
            obj.put("targetDesc", e.targetDesc)
            obj.put("flagsHex", e.flagsHex)
            obj.put("optionsInfo", e.optionsInfo)
            obj.put("result", e.result)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_LAUNCH_LOGS, arr.toString()).apply()
    }

    fun getLaunchLogs(context: Context): List<LaunchLogEntry> {
        val raw = prefs(context).getString(KEY_LAUNCH_LOGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LaunchLogEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LaunchLogEntry(
                        timestamp = obj.optString("timestamp"),
                        flowOrStrategy = obj.optString("flowOrStrategy"),
                        side = obj.optString("side"),
                        targetDesc = obj.optString("targetDesc"),
                        flagsHex = obj.optString("flagsHex"),
                        optionsInfo = obj.optString("optionsInfo"),
                        result = obj.optString("result")
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clearLaunchLogs(context: Context) {
        prefs(context).edit().remove(KEY_LAUNCH_LOGS).apply()
    }
}
