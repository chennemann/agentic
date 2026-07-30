package de.chennemann.agentic.ui.components

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
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
    fun summary_reveals_and_collapses_controls() {
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
                                        ProviderOptionValueUi("high", "High"),
                                    ),
                                    selectedValueId = "high",
                                ),
                            ),
                            selectedRuntimeModeId = "full-access",
                            runtimeModes = listOf(
                                RuntimeModeOptionUi("full-access", "Full access"),
                            ),
                        ),
                        turnRunning = false,
                        onEvent = {},
                    )
                }
            }
        }

        compose.onAllNodesWithText("Model selection").assertCountEquals(0)
        compose.onNodeWithContentDescription(
            "Open model, reasoning, and access controls",
        ).performClick()
        compose.onNodeWithText("Model selection").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Model selection").assertCountEquals(0)
    }
}
