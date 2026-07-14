package com.v2rayez.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v2rayez.app.ui.screens.servers.ServerEditorScreen
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.ServerEditorViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun newServerEditor_showsFormAndSaveButton() {
        composeRule.setContent {
            V2RayEzTheme {
                ServerEditorScreen(serverId = null, onBack = {}, viewModel = ServerEditorViewModel())
            }
        }

        composeRule.onNodeWithText("New Server").assertIsDisplayed()
        composeRule.onNodeWithText("Name").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun nameField_acceptsInput() {
        composeRule.setContent {
            V2RayEzTheme {
                ServerEditorScreen(serverId = null, onBack = {}, viewModel = ServerEditorViewModel())
            }
        }

        composeRule.onNodeWithText("Name").performTextInput("Tokyo Node")
        composeRule.onNodeWithText("Tokyo Node").assertIsDisplayed()
    }
}
