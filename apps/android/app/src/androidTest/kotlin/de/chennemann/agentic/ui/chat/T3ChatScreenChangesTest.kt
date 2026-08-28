package de.chennemann.agentic.ui.chat

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

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

    @Test
    fun files_remain_available_when_the_latest_turn_has_changes() {
        var openedThreadId: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3ChatScreen(
                        state = changesState().copy(canBrowseFiles = true),
                        onEvent = {},
                        onOpenFiles = { openedThreadId = it },
                    )
                }
            }
        }

        compose.onNodeWithText("Files").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("thread-1", openedThreadId) }
    }

    @Test
    fun completed_messages_expose_a_copy_action() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3ChatScreen(
                        state = changesState().copy(
                            timeline = listOf(
                                ChatTimelineItemUi.Message(
                                    ChatMessageUi(
                                        id = "assistant-1",
                                        author = ChatMessageAuthorUi.ASSISTANT,
                                        content = "A useful answer",
                                        canCopy = true,
                                    ),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Copy message").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Copied").assertIsDisplayed()
    }

    @Test
    fun completed_turn_shows_server_derived_duration() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3ChatScreen(
                        state = changesState().copy(
                            latestTurnTiming = ChatTurnTimingUi(
                                startedAt = "2026-08-28T12:00:00Z",
                                completedAt = "2026-08-28T12:01:05Z",
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Worked for 1m 5s").assertIsDisplayed()
    }

    @Test
    fun swiping_conversation_right_opens_project_picker_prototype() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3ChatScreen(
                        state = changesState(),
                        onEvent = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(ChatTimelineTestTag).performTouchInput { swipeRight() }

        compose.onNodeWithText("Prototype · Compact list").assertIsDisplayed()
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
