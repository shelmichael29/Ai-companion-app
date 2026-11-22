package com.aicarcompanion.app.handsfree.android

import android.app.Application
import android.content.Intent
import com.aicarcompanion.app.handsfree.ActionResult
import com.aicarcompanion.app.handsfree.CallTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidCallServiceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val service = AndroidCallService(app)

    @Test fun `an unconfirmed dial is refused and opens nothing`() = runTest {
        assertEquals(ActionResult.NeedsConfirmation, service.dial(CallTarget("Sam", "5550100"), confirmed = false))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `a confirmed dial hands the number to the dialer with ACTION_DIAL`() = runTest {
        assertEquals(ActionResult.Requested, service.dial(CallTarget(null, "(555) 010-0123"), confirmed = true))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:5550100123", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `a number with no digits is not dialed`() = runTest {
        assertTrue(service.dial(CallTarget(null, "abc"), confirmed = true) is ActionResult.Failed)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `answering, declining and hanging up are deferred`() = runTest {
        assertTrue(service.answer() is ActionResult.Unavailable)
        assertTrue(service.decline() is ActionResult.Unavailable)
        assertTrue(service.hangUp() is ActionResult.Unavailable)
    }

    @Test fun `the app never uses ACTION_CALL`() {
        File("src/main/java/com/aicarcompanion/app").walkTopDown().filter { it.extension == "kt" }.forEach {
            val text = it.readText()
            assertFalse("${it.name} uses ACTION_CALL", Regex("ACTION_CALL\\b").containsMatchIn(text))
            assertFalse("${it.name} uses ACTION_ANSWER", text.contains("ACTION_ANSWER"))
        }
    }
}
