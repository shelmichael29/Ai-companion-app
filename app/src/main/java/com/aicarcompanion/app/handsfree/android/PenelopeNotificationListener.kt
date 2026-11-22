package com.aicarcompanion.app.handsfree.android

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aicarcompanion.app.CarCompanionApplication

/**
 * Android's notification-access hook. It does nothing until the user turns on notification access.
 * Android then lets Penelope see all notifications; Penelope keeps only message notifications
 * (in memory) and media sessions, and never stores, logs or uploads anything.
 */
class PenelopeNotificationListener : NotificationListenerService() {
    private val events: NotificationEvents? get() = (application as? CarCompanionApplication)?.container?.notificationEvents

    override fun onListenerConnected() {
        val active = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        events?.onConnected(active)
    }

    override fun onListenerDisconnected() {
        events?.onDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) events?.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) events?.onRemoved(sbn)
    }
}
