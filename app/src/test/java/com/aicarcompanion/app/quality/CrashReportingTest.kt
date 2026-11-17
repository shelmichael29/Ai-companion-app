package com.aicarcompanion.app.quality

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Crash reporting: safe without Firebase, debug-only test crash, and a privacy guard that fails the
 * build if code ever attaches user identifiers or free text to crash reports.
 * Whether reports actually arrive in Firebase can only be verified on a device (see
 * docs/DEVELOPMENT_QUALITY_GATES.md, "Crashlytics").
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CrashReportingTest {

    @Test fun `without Firebase initialized, setup is a safe no-op`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(CrashReporting.isAvailable(app))
        CrashReporting.initialize(app) // must not throw
    }

    @Test fun `the test crash is clearly labeled and only in debug builds`() {
        val error = runCatching { CrashReporting.sendTestCrash() }.exceptionOrNull()
        // Unit tests run the debug variant.
        assertTrue(error is TestCrashException)
        assertTrue(error!!.message!!.contains("Not a real problem"))
    }

    @Test fun `no code attaches user identifiers or free text to crash reports`() {
        val src = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("source files not found from ${File(".").absolutePath}", src.isNotEmpty())
        val forbidden = listOf("setUserId(", ".recordException(", "Crashlytics.log(", "crashlytics.log(")
        for (file in src) {
            val text = file.readText()
            forbidden.forEach { assertFalse("${file.name} uses $it", text.contains(it)) }
            if (file.name != "CrashReporting.kt") {
                assertFalse("${file.name} must go through CrashReporting", text.contains("FirebaseCrashlytics"))
            }
        }
    }
}
