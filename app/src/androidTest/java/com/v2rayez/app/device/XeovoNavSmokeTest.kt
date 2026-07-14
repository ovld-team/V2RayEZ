package com.v2rayez.app.device

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v2rayez.app.ui.screens.servers.ServerEditorScreen
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.ServerEditorViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device Lab DV1–DV3 smoke test: launches a real screen (mock-backed ViewModel, no Hilt/live
 * Device-lab nav smoke. Kept small; deeper connection coverage lives in Maestro flows
 * and scripts/device-lab helpers.
 */
@RunWith(AndroidJUnit4::class)
class XeovoNavSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun serverEditor_launchesAndShowsStableChrome() {
        composeRule.setContent {
            V2RayEzTheme {
                ServerEditorScreen(serverId = null, onBack = {}, viewModel = ServerEditorViewModel())
            }
        }

        composeRule.onNodeWithText("New Server").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }
}
