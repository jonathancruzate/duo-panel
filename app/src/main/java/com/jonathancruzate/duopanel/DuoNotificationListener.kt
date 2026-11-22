package com.jonathancruzate.duopanel

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DuoNotificationListener — PATCH 0.1.7 Part B.
 *
 * A bound system NotificationListenerService that tracks ACTIVE notification counts per
 * package so the Picker can render One UI style icon badges (dot or number). It requires
 * the one-time system "Notification access" grant; without the grant the system never
 * binds it, counts stay empty, and no badges render (never faked).
 *
 * Privacy: counts live in process memory only. Nothing about notification content (titles,
 * text, extras) is read, stored, or persisted, and DuoPanel still declares no INTERNET
 * permission.
 */
class DuoNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        recomputeCounts()
    }

    override fun onListenerDisconnected() {
        connected = false
        counts = emptyMap()
        notifyBadgeListeners()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        recomputeCounts()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        recomputeCounts()
    }

    private fun recomputeCounts() {
        val map = HashMap<String, Int>()
        try {
            val active = activeNotifications ?: emptyArray()
            for (sbn in active) {
                val pkg = sbn?.packageName ?: continue
                map[pkg] = (map[pkg] ?: 0) + 1
            }
        } catch (_: Throwable) {
            // activeNotifications can throw while the listener is mid-bind; keep prior counts.
            return
        }
        counts = map
        notifyBadgeListeners()
    }

    companion object {
        @Volatile
        private var counts: Map<String, Int> = emptyMap()

        @Volatile
        var connected: Boolean = false
            private set

        private val badgeListeners = CopyOnWriteArrayList<() -> Unit>()

        fun countFor(packageName: String): Int = counts[packageName] ?: 0

        fun registerBadgeListener(listener: () -> Unit) {
            if (!badgeListeners.contains(listener)) {
                badgeListeners.add(listener)
            }
        }

        fun unregisterBadgeListener(listener: () -> Unit) {
            badgeListeners.remove(listener)
        }

        private fun notifyBadgeListeners() {
            for (listener in badgeListeners) {
                try {
                    listener()
                } catch (_: Throwable) {
                }
            }
        }

        /** The system grant state, read from the secure setting the system settings screen edits. */
        fun isAccessGranted(context: Context): Boolean {
            val flat = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
            } catch (_: Throwable) {
                null
            } ?: return false
            val mine = ComponentName(context, DuoNotificationListener::class.java)
            return flat.split(":").any { entry ->
                try {
                    ComponentName.unflattenFromString(entry) == mine
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }
}
