package de.chennemann.agentic.ui.components

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.chat.ProviderModelOptionUi
import de.chennemann.agentic.ui.chat.ProviderOptionUi
import de.chennemann.agentic.ui.chat.ProviderOptionValueUi
import de.chennemann.agentic.ui.chat.RuntimeModeOptionUi
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Rule
import org.junit.Test

class T3MessageComposerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun summary_reveals_controls_and_input_collapses_them() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3MessageComposer(
                        state = ComposerUiState(
                            selectedProviderModelId = "provider/model",
                            providerModels = listOf(
                                ProviderModelOptionUi(
                                    id = "provider/model",
                                    providerInstanceId = "provider",
                                    providerLabel = "Provider",
                                    modelLabel = "Model",
                                ),
                            ),
                            providerOptions = listOf(
                                ProviderOptionUi.Select(
                                    id = "effort",
                                    label = "Reasoning",
                                    values = listOf(
                                        ProviderOptionValueUi("low", "Low"),
                                        ProviderOptionValueUi("xhigh", "Extra high"),
                                    ),
                                    selectedValueId = "xhigh",
                                ),
                                ProviderOptionUi.Select(
                                    id = "service-tier",
                                    label = "Service mode",
                                    values = listOf(
                                        ProviderOptionValueUi("default", "Default"),
                                        ProviderOptionValueUi("fast", "Fast"),
                                    ),
                                    selectedValueId = "default",
                                ),
                            ),
                            selectedRuntimeModeId = "full-access",
                            runtimeModes = listOf(
                                RuntimeModeOptionUi(
                                    "approval-required",
                                    "Supervised",
                                    "Ask before changes.",
                                ),
                                RuntimeModeOptionUi(
                                    "full-access",
                                    "Full access",
                                    "Allow all supported actions without approval.",
                                ),
                            ),
                        ),
                        turnRunning = false,
                        onEvent = {},
                    )
                }
            }
        }

        compose.onAllNodesWithText("Model Selection").assertCountEquals(0)
        compose.onNodeWithText("Message").performClick().assertIsFocused()
        compose.onNodeWithContentDescription(
            "Open model, reasoning, and access controls",
        ).performClick()
        compose.onNodeWithText("Message").assertIsNotFocused()
        compose.onNodeWithText("Model Selection").assertIsDisplayed()
        compose.onNodeWithText("Reasoning").assertIsDisplayed()
        compose.onNodeWithText("xhigh").assertIsDisplayed()
        compose.onAllNodesWithText("Extra high").assertCountEquals(0)
        compose.onAllNodesWithText("Service mode").assertCountEquals(0)
        compose.onNodeWithText("Allow all supported actions without approval.").assertIsDisplayed()
        compose.onAllNodesWithText("Ask before changes.").assertCountEquals(0)

        compose.onNodeWithText("Message").performClick()
        compose.onAllNodesWithText("Model Selection").assertCountEquals(0)
        compose.onNodeWithContentDescription("Access: Full access").assertIsDisplayed()
    }

    @Test
    fun empty_composer_uses_microphone_when_voice_input_is_configured() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3MessageComposer(
                        state = ComposerUiState(voiceInputAvailable = true),
                        turnRunning = false,
                        onEvent = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertDoesNotExist()
    }
}
