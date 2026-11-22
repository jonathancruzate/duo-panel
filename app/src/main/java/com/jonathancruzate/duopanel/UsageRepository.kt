package com.jonathancruzate.duopanel

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * PATCH 0.2.0: live usage-stats reads backing the picker's Recent apps and
 * Frequently used sections.
 *
 * Data comes from UsageStatsManager.queryAndAggregateUsageStats:
 * - Recent: lastTimeUsed over the last 7 days, most recent first.
 * - Frequent: totalTimeInForeground over the last 30 days, most time first.
 *
 * Access is the user's PACKAGE_USAGE_STATS grant (system Usage Access
 * settings), checked via AppOps like the notification-access check for
 * badges. Nothing is persisted: every read is a live query, and callers
 * re-query on each picker populate. Without the grant (or on any query
 * failure) the reads return empty lists and the picker shows its honest
 * grant-prompt state instead of data.
 */
object UsageRepository {

    private const val RECENT_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000
    private const val FREQUENT_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** True when the user has granted Usage Access to DuoPanel. */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    /** Packages ordered by lastTimeUsed (most recent first), last 7 days. */
    fun recentPackages(context: Context, limit: Int): List<String> {
        if (!hasUsageAccess(context)) return emptyList()
        val now = System.currentTimeMillis()
        val stats = aggregate(context, now - RECENT_WINDOW_MS, now) ?: return emptyList()
        return stats.values
            .filter { it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .take(limit)
    }

    /** Packages ordered by total foreground time (most first), last 30 days. */
    fun frequentPackages(context: Context, limit: Int): List<String> {
        if (!hasUsageAccess(context)) return emptyList()
        val now = System.currentTimeMillis()
        val stats = aggregate(context, now - FREQUENT_WINDOW_MS, now) ?: return emptyList()
        return stats.values
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .map { it.packageName }
            .take(limit)
    }

    private fun aggregate(
        context: Context,
        begin: Long,
        end: Long
    ): Map<String, android.app.usage.UsageStats>? {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            usm.queryAndAggregateUsageStats(begin, end)
        } catch (_: Throwable) {
            null
        }
    }
}
