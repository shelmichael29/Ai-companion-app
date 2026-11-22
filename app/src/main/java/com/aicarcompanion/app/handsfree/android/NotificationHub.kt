package com.aicarcompanion.app.handsfree.android

import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import com.aicarcompanion.app.handsfree.MediaControl

/** What the notification listener forwards to the app. */
interface NotificationEvents {
    fun onConnected(active: List<StatusBarNotification>)
    fun onDisconnected()
    fun onPosted(sbn: StatusBarNotification)
    fun onRemoved(sbn: StatusBarNotification)
}

/** Connects the listener to the in-memory messaging service, the access state and the media session. */
class NotificationHub(
    private val context: Context,
    private val access: NotificationAccessState,
    private val messaging: AndroidMessagingService,
    private val media: MediaControl,
) : NotificationEvents {

    override fun onConnected(active: List<StatusBarNotification>) {
        messaging.clear()
        access.refresh()
        access.listenerConnected(true)
        active.forEach(::onPosted)
        media.refresh()
    }

    override fun onDisconnected() {
        messaging.clear()
        access.listenerConnected(false)
        access.refresh()
        media.refresh()
    }

    override fun onPosted(sbn: StatusBarNotification) = onPosted(sbn.key, sbn.packageName, sbn.notification)

    /** An updated notification that is no longer a message (or has no text) drops the old message under its key. */
    internal fun onPosted(key: String, packageName: String, notification: android.app.Notification) {
        MessageNotificationParser.parse(key, packageName, appLabel(packageName), notification, context.packageName)
            ?.let(messaging::onPosted) ?: messaging.onRemoved(key)
    }

    override fun onRemoved(sbn: StatusBarNotification) = messaging.onRemoved(sbn.key)

    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
