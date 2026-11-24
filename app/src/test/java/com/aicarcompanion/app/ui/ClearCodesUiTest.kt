package com.aicarcompanion.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicarcompanion.app.diagnostics.obd.ClearCodesResult
import com.aicarcompanion.app.ui.results.CLEAR_CODES_BUTTON_TAG
import com.aicarcompanion.app.ui.results.CLEAR_CODES_CANCEL_TAG
import com.aicarcompanion.app.ui.results.CLEAR_CODES_CONFIRM_TAG
import com.aicarcompanion.app.ui.results.CLEAR_CODES_DIALOG_TAG
import com.aicarcompanion.app.ui.results.CLEAR_CODES_UNDERSTAND_TAG
import com.aicarcompanion.app.ui.results.ClearCodesAvailability
import com.aicarcompanion.app.ui.results.ClearCodesContent
import com.aicarcompanion.app.ui.results.ClearCodesDialogState
import com.aicarcompanion.app.ui.results.ClearCodesUiState
import com.aicarcompanion.app.ui.theme.CarCompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class ClearCodesUiTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var opened = 0
    private var confirmed = 0
    private var dismissed = 0
    private var understood: Boolean? = null

    private fun show(ui: ClearCodesUiState) = compose.setContent {
        CarCompanionTheme(reduceMotion = true) {
            ClearCodesContent(ui, { opened++ }, { understood = it }, { confirmed++ }, { dismissed++ })
        }
    }

    private val ready = ClearCodesAvailability(visible = true, enabled = true, disabledReason = null)

    @Test fun `hidden for everyday`() {
        show(ClearCodesUiState(ClearCodesAvailability(false, false, null)))
        compose.onNodeWithTag(CLEAR_CODES_BUTTON_TAG).assertDoesNotExist()
        compose.onNodeWithText("Read codes first").assertDoesNotExist()
    }

    @Test fun `disabled with a reason until codes are read`() {
        show(ClearCodesUiState(ClearCodesAvailability(true, false, ClearCodesAvailability.READ_FIRST)))
        compose.onNodeWithTag(CLEAR_CODES_BUTTON_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Read codes first").assertExists()
        compose.onNodeWithTag(CLEAR_CODES_BUTTON_TAG).performClick()
        assertEquals(0, opened)
    }

    @Test fun `enabled button opens the dialog request`() {
        show(ClearCodesUiState(ready))
        compose.onNodeWithTag(CLEAR_CODES_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test fun `dialog explains the consequences and needs the checkbox`() {
        show(ClearCodesUiState(ready, ClearCodesDialogState(open = true)))
        compose.onNodeWithTag(CLEAR_CODES_DIALOG_TAG).assertExists()
        compose.onNodeWithText("readiness monitors", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("smog", substring = true).assertExists()
        compose.onNodeWithText("engine off", substring = true).assertExists()
        compose.onNodeWithTag(CLEAR_CODES_CONFIRM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(CLEAR_CODES_CANCEL_TAG).assertIsEnabled().performClick()
        assertEquals(1, dismissed)
        compose.onNodeWithTag(CLEAR_CODES_UNDERSTAND_TAG).performClick()
        assertEquals(true, understood)
        assertEquals(0, confirmed)
    }

    @Test fun `confirm enabled after understanding`() {
        show(ClearCodesUiState(ready, ClearCodesDialogState(open = true, understood = true)))
        compose.onNodeWithTag(CLEAR_CODES_CONFIRM_TAG).assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test fun `progress blocks both buttons`() {
        show(ClearCodesUiState(ready, ClearCodesDialogState(open = true, understood = true, working = true)))
        compose.onNodeWithText("Clearing codes", substring = true).assertExists()
        compose.onNodeWithTag(CLEAR_CODES_CONFIRM_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(CLEAR_CODES_CANCEL_TAG).assertIsNotEnabled()
    }

    @Test fun `result is shown honestly`() {
        show(ClearCodesUiState(ready, ClearCodesDialogState(open = true, result = ClearCodesResult.NotAccepted(1))))
        compose.onNodeWithText("did not accept", substring = true).assertExists()
        compose.onNodeWithText("Done").assertExists()
    }
}
