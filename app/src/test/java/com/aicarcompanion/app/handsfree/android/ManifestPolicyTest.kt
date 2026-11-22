package com.aicarcompanion.app.handsfree.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drive must not need risky permissions: no SMS, call, contacts or phone-state, and no foreground
 * service. Reads the manifest source so a regression fails the build.
 */
class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val permissions = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()

    @Test fun `no SMS, call, contacts, phone-state or foreground-service permission is declared`() {
        val banned = listOf(
            "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "CALL_PHONE", "READ_CONTACTS", "WRITE_CONTACTS",
            "ANSWER_PHONE_CALLS", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "READ_CALL_LOG", "WRITE_CALL_LOG", "FOREGROUND_SERVICE",
            "MANAGE_OWN_CALLS", "BIND_INCALL_SERVICE",
        )
        banned.forEach { word -> assertTrue("$word is declared", permissions.none { it.contains(word) }) }
    }

    @Test fun `the permission list is exactly the audited one`() {
        assertEquals(
            setOf(
                "INTERNET", "ACCESS_NETWORK_STATE", "RECORD_AUDIO", "BLUETOOTH", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN",
                "BLUETOOTH_ADMIN", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "CAMERA",
            ),
            permissions.map { it.substringAfterLast('.') }.toSet(),
        )
    }

    @Test fun `the notification listener is protected by the system permission`() {
        val service = Regex("<service[^>]*PenelopeNotificationListener[^>]*>", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value
        assertTrue(service.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
    }

    @Test fun `no service runs in the foreground`() {
        assertFalse(manifest.contains("foregroundServiceType"))
    }
}
