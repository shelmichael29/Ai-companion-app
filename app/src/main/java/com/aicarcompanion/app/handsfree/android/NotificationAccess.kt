package com.aicarcompanion.app.handsfree.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the user has turned on notification access for Penelope (needed to read and answer messages, and to see what's playing). */
interface NotificationAccessState {
    val granted: StateFlow<Boolean>
    /** True while Android has Penelope's notification listener connected. Access can be on while it isn't. */
    val connected: StateFlow<Boolean>
    fun listenerConnected(connected: Boolean)
    /** Re-checks the system setting. Call when the app comes back to the foreground. */
    fun refresh()
}

class AndroidNotificationAccess(context: Context) : NotificationAccessState {
    private val appContext = context.applicationContext
    private val _granted = MutableStateFlow(read())
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override fun listenerConnected(connected: Boolean) {
        _connected.value = connected
    }

    override fun refresh() {
        _granted.value = read()
    }

    private fun read(): Boolean = appContext.packageName in NotificationManagerCompat.getEnabledListenerPackages(appContext)

    companion object {
        /** Asks Android to reconnect the listener when access is on but it isn't connected (API 24+). */
        fun requestRebind(context: Context) {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(context, PenelopeNotificationListener::class.java),
            )
        }

        /** Opens Penelope's own notification-access page (Android 11+), else the general list. */
        fun settingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        android.content.ComponentName(context, PenelopeNotificationListener::class.java).flattenToString(),
                    )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
