package com.aicarcompanion.app.ai

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** RELEASE BUILDS (src/release). Play Integrity attestation; requires installation from Google Play. */
object AppCheckInstaller {
    const val PROVIDER_NAME = "Play Integrity"

    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
