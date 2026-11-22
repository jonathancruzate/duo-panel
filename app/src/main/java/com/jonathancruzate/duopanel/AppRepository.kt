package com.jonathancruzate.duopanel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Discovers installed launchable apps and resolves target app launch intents
 * using PackageManager.getLaunchIntentForPackage with an explicit ACTION_MAIN /
 * CATEGORY_LAUNCHER fallback per SPEC.md.
 */
object AppRepository {

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val componentName: ComponentName,
        val icon: Drawable?
    )

    @Volatile
    private var cachedApps: List<InstalledApp>? = null

    fun getLaunchableApps(context: Context, forceRefresh: Boolean = false): List<InstalledApp> {
        val existing = cachedApps
        if (!forceRefresh && existing != null && existing.isNotEmpty()) {
            return existing
        }
        val pm = context.packageManager
        val selfPkg = context.packageName
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (_: Throwable) {
            emptyList()
        }

        val byPkg = linkedMapOf<String, InstalledApp>()
        for (ri in resolved) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (pkg == selfPkg || byPkg.containsKey(pkg)) continue
            val label = try {
                ri.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            } catch (_: Throwable) {
                pkg
            }
            val icon = try {
                ri.loadIcon(pm)
            } catch (_: Throwable) {
                null
            }
            byPkg[pkg] = InstalledApp(
                packageName = pkg,
                label = label,
                componentName = ComponentName(pkg, ai.name),
                icon = icon
            )
        }

        val sorted = byPkg.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        cachedApps = sorted
        return sorted
    }

    fun getAppLabel(context: Context, packageName: String): String {
        cachedApps?.firstOrNull { it.packageName == packageName }?.let { return it.label }
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
        } catch (_: Throwable) {
            packageName
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        cachedApps?.firstOrNull { it.packageName == packageName }?.icon?.let { return it }
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves a clean launch intent for [packageName]:
     * 1. PackageManager.getLaunchIntentForPackage(packageName)
     * 2. Fallback to PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)
     */
    fun resolveLaunchIntent(context: Context, packageName: String): Intent? {
        val pm: PackageManager = context.packageManager
        try {
            val direct = pm.getLaunchIntentForPackage(packageName)
            if (direct != null) {
                return Intent(direct).apply {
                    setPackage(packageName)
                }
            }
        } catch (_: Throwable) {
            // Fall through to queryIntentActivities fallback
        }

        return try {
            val query = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val list = pm.queryIntentActivities(query, 0)
            val first = list.firstOrNull()?.activityInfo ?: return null
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, first.name)
                setPackage(packageName)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Creates a shortcut icon bitmap combining the target app's icon with a subtle
     * One UI Blue (#0381FE) left or right panel indicator badge.
     */
    fun createBadgedSoloIconBitmap(
        context: Context,
        packageName: String,
        side: DuoPrefs.PanelSide
    ): Bitmap? {
        val appDrawable = getAppIcon(context, packageName) ?: return null
        val sizePx = (64f * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val pad = (sizePx * 0.08f).toInt()
        appDrawable.setBounds(pad, pad, sizePx - pad, sizePx - pad)
        appDrawable.draw(canvas)

        // Draw a mini two-panel indicator pill at the bottom corner
        val badgeW = sizePx * 0.42f
        val badgeH = sizePx * 0.24f
        val badgeLeft = if (side == DuoPrefs.PanelSide.LEFT) {
            sizePx * 0.04f
        } else {
            sizePx - badgeW - sizePx * 0.04f
        }
        val badgeTop = sizePx - badgeH - sizePx * 0.04f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE121215.toInt()
            style = Paint.Style.FILL
        }
        val radius = badgeH * 0.35f
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
            radius,
            radius,
            bgPaint
        )

        val innerPad = badgeH * 0.18f
        val halfW = (badgeW - innerPad * 3f) / 2f
        val panelTop = badgeTop + innerPad
        val panelBottom = badgeTop + badgeH - innerPad

        val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (side == DuoPrefs.PanelSide.LEFT) 0xFF0381FE.toInt() else 0xFF3E3E46.toInt()
            style = Paint.Style.FILL
        }
        val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (side == DuoPrefs.PanelSide.RIGHT) 0xFF0381FE.toInt() else 0xFF3E3E46.toInt()
            style = Paint.Style.FILL
        }
        val panelRadius = innerPad * 0.8f
        canvas.drawRoundRect(
            RectF(badgeLeft + innerPad, panelTop, badgeLeft + innerPad + halfW, panelBottom),
            panelRadius,
            panelRadius,
            leftPaint
        )
        canvas.drawRoundRect(
            RectF(
                badgeLeft + innerPad * 2f + halfW,
                panelTop,
                badgeLeft + innerPad * 2f + halfW * 2f,
                panelBottom
            ),
            panelRadius,
            panelRadius,
            rightPaint
        )
        return bmp
    }
}
