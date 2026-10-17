package com.vgs.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Runs on the GATEWAY phone (needs "Notification access" in Android settings).
 * Every new notification is forwarded to the remote phone over the Bluetooth link.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotifListener? = null

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val cn = ComponentName(ctx, NotifListener::class.java)
            if (Build.VERSION.SDK_INT >= 27) {
                try {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    if (nm != null) return nm.isNotificationListenerAccessGranted(cn)
                } catch (e: Exception) {
                }
            }
            // Fallback: the setting may store the long or the short component name.
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(':').any {
                it == cn.flattenToString() || it == cn.flattenToShortString()
            }
        }

        /** Android sometimes unbinds the listener after an app update; ask it to reconnect. */
        fun rebind(ctx: Context) {
            if (instance != null || !isEnabled(ctx)) return
            try {
                NotificationListenerService.requestRebind(ComponentName(ctx, NotifListener::class.java))
                Hub.log("Asked Android to reconnect notification access")
            } catch (e: Exception) {
                Hub.log("Notification rebind failed: ${e.message}")
            }
        }
    }

    private val lastSent = HashMap<String, String>()

    /** The last "not sent" reason that was logged, so it is logged once instead of per notification. */
    @Volatile
    private var lastBlockReason: String? = null

    override fun onListenerConnected() {
        instance = this
        Hub.log("🔔 Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Hub.log("🔕 Notification listener disconnected")
        rebind(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (AppCalls.isCall(sbn)) { // WhatsApp call: ringing / active
            val svc = Hub.service?.takeIf { it.isActive } ?: return // CallCompanion stopped: ignore
            val call = AppCalls.parse(sbn) ?: return
            svc.onAppCall(call)
            return
        }
        if (sbn.isOngoing) return // music players, navigation, downloads…
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return // e.g. "3 new messages" bundle
        if (n.category == Notification.CATEGORY_CALL) return // calls are handled separately
        // Android keeps this listener running even when CallCompanion is stopped. In that case the
        // notification is ignored without reading it, and the reason is logged only once.
        val svc = Hub.service
        val why = if (svc == null) "CallCompanion is stopped (press Start)" else svc.mirrorBlockReason()
        if (svc == null || why != null) {
            synchronized(lastSent) { lastSent.clear() }
            if (why != lastBlockReason) {
                lastBlockReason = why
                Hub.log("🔔 Notifications are not being sent: $why")
            }
            return
        }
        lastBlockReason = null
        val app = appName(sbn.packageName)

        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: ""
        if (title.isEmpty() && text.isEmpty()) {
            Hub.log("🔔 $app notification skipped (no text)")
            return
        }

        val sig = title + "\u0000" + text
        synchronized(lastSent) {
            if (lastSent[sbn.key] == sig) return // same content re-posted, skip
            lastSent[sbn.key] = sig
        }
        svc.mirrorPosted(sbn.key, app, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val svc = Hub.service ?: return // CallCompanion stopped: nothing to clear on the remote
        if (AppCalls.isCall(sbn)) {
            svc.onAppCallRemoved(sbn.key)
            return
        }
        synchronized(lastSent) { lastSent.remove(sbn.key) }
        svc.mirrorRemoved(sbn.key)
    }

    /** Called when the user swipes the copy away on the remote phone. */
    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Hub.log("Could not dismiss: ${e.message}")
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
