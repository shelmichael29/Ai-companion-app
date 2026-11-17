package com.aicarcompanion.app.quality

import android.content.Context
import android.util.Log
import com.aicarcompanion.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Firebase Crashlytics crash reporting.
 *
 * Privacy:
 * - Only crash and error stack traces, plus device model, Android version and app version, are sent.
 * - No user ID, name, email, VIN, location, vehicle data or conversation text is ever attached.
 *   Penelope never calls `setUserId`, and never adds custom keys or log lines containing user data.
 * - The only custom key is the build type (debug or release), so test crashes are easy to filter.
 *
 * Without a Firebase config (e.g. a build made without google-services.json) everything here is a
 * safe no-op.
 */
object CrashReporting {
    private const val TAG = "PenelopeCrash"

    /** True when Crashlytics can report from this build (Firebase configured and initialized). */
    fun isAvailable(context: Context): Boolean =
        BuildConfig.FIREBASE_CONFIGURED && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    fun initialize(context: Context) {
        if (!isAvailable(context)) return
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCrashlyticsCollectionEnabled(true)
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            }
        }.onFailure { Log.w(TAG, "Crash reporting unavailable: ${it.javaClass.simpleName}") }
    }

    /**
     * DEBUG BUILDS ONLY: crashes the app on purpose so the setup can be verified end to end. The
     * report appears in the Firebase console after the app is opened again.
     */
    fun sendTestCrash(): Nothing {
        check(BuildConfig.DEBUG) { "Test crashes are only allowed in debug builds" }
        throw TestCrashException()
    }
}

/** The deliberate crash from [CrashReporting.sendTestCrash]; its name says it isn't a real bug. */
class TestCrashException : RuntimeException("Test crash requested from Settings in a debug build. Not a real problem.")
