package com.aicarcompanion.app.handsfree.android

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aicarcompanion.app.CarCompanionApplication

/**
 * Android's notification-access hook. It does nothing until the user turns on notification access
 * for Penelope, and then only forwards message notifications and media sessions to the app's
 * in-memory hands-free services. It never stores, logs or uploads anything.
 */
class PenelopeNotificationListener : NotificationListenerService() {
    private val events: NotificationEvents? get() = (application as? CarCompanionApplication)?.container?.notificationEvents

    override fun onListenerConnected() {
        events?.onConnected(activeNotifications?.toList().orEmpty())
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
