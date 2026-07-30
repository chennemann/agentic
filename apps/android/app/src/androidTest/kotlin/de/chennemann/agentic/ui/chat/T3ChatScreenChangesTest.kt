package de.chennemann.agentic.ui.chat

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Rule
import org.junit.Test

class T3ChatScreenChangesTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun latest_turn_changes_button_opens_changed_file_list() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                var state by remember { mutableStateOf(changesState()) }
                MobileTheme {
                    T3ChatScreen(
                        state = state,
                        onEvent = { event ->
                            if (event is ChatUiEvent.PickerRequested) {
                                state = state.copy(activePicker = event.picker)
                            }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Changes (2)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Latest turn changes").assertIsDisplayed()
        compose.onNodeWithText("app/src/main/App.kt").assertIsDisplayed()
        compose.onNodeWithText("README.md").assertIsDisplayed()
        compose.onNodeWithText("+8").assertIsDisplayed()
        compose.onNodeWithText("−2").assertIsDisplayed()
    }
}

private fun changesState(): ChatUiState = ChatUiState(
    title = "Diff view",
    environmentLabel = "Local",
    projectLabel = "agentic",
    threadId = "thread-1",
    timeline = emptyList(),
    connection = ChatConnectionUi.Live,
    composer = ComposerUiState(enabled = false),
    latestTurnChanges = LatestTurnChangesUiState(
        turnId = "turn-1",
        files = listOf(
            ChangedFileUi(
                path = "app/src/main/App.kt",
                kind = "modified",
                additions = 8,
                deletions = 2,
            ),
            ChangedFileUi(
                path = "README.md",
                kind = "added",
                additions = 4,
                deletions = 0,
            ),
        ),
    ),
)
