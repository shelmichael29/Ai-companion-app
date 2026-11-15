package com.aicarcompanion.app.ai

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * DEBUG BUILDS ONLY (src/debug). Uses the App Check debug provider. On first run the Firebase
 * SDK itself prints a one-time debug secret to Logcat (tag "DebugAppCheckProvider"); register it
 * privately in the Firebase console. This app never logs, stores in source, or uploads the token.
 */
object AppCheckInstaller {
    const val PROVIDER_NAME = "Debug provider (debug build)"

    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
