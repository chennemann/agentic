package de.chennemann.agentic.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import de.chennemann.agentic.ui.theme.MobileTheme
import de.chennemann.agentic.ui.components.ComposerInputTestTag
import org.junit.Rule
import org.junit.Test

class T3ChatScreenLongThreadTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun long_thread_pauses_following_and_recovers_to_streaming_tail_after_reconnect() {
        lateinit var updateState: ((ChatUiState) -> ChatUiState) -> Unit
        compose.setContent {
            var state by remember { mutableStateOf(longThreadState()) }
            updateState = { transform -> state = transform(state) }
            MobileTheme {
                T3ChatScreen(state = state, onEvent = {})
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("stream chunk 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("stream chunk 1").assertIsDisplayed()
        compose.onNodeWithText("Provider future activity").assertIsDisplayed()

        repeat(4) {
            compose.onNodeWithTag(ChatTimelineTestTag).performTouchInput { swipeDown() }
        }
        compose.onNodeWithTag(FollowLatestTestTag).assertIsDisplayed()

        compose.runOnIdle {
            updateState { state ->
                state.copy(
                    connection = ChatConnectionUi.Reconnecting("Recovering stream"),
                    timeline = state.timeline.dropLast(1) + streamingTail("stream chunk 2"),
                )
            }
        }
        compose.onNodeWithText("Recovering stream").assertIsDisplayed()
        compose.onAllNodesWithText("stream chunk 2").assertCountEquals(0)

        compose.onNodeWithTag(FollowLatestTestTag).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("stream chunk 2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("stream chunk 2").assertIsDisplayed()

        compose.runOnIdle {
            updateState { state ->
                state.copy(
                    connection = ChatConnectionUi.Live,
                    timeline = state.timeline.dropLast(1) + streamingTail("stream chunk 3"),
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("stream chunk 3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("stream chunk 3").assertIsDisplayed()
    }

    @Test
    fun automatic_composer_focus_only_follows_at_the_end_of_history() {
        compose.setContent {
            MobileTheme {
                T3ChatScreen(
                    state = longThreadState().copy(
                        composer = ComposerUiState(enabled = true),
                        isTurnRunning = false,
                    ),
                    onEvent = {},
                )
            }
        }

        compose.onNodeWithTag(ChatTimelineTestTag).performTouchInput { swipeDown() }
        compose.onNodeWithTag(ComposerInputTestTag).assertIsNotFocused()

        compose.onNodeWithTag(ChatTimelineTestTag).performTouchInput { swipeUp() }
        compose.onNodeWithTag(ComposerInputTestTag).assertIsNotFocused()

        compose.onNodeWithTag(FollowLatestTestTag).performClick()
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag(ComposerInputTestTag).assertIsFocused()
            }.isSuccess
        }
        compose.onNodeWithTag(ComposerInputTestTag).assertIsFocused()

        compose.onNodeWithTag(ChatTimelineTestTag).performTouchInput { swipeDown() }
        compose.onNodeWithTag(ComposerInputTestTag).assertIsNotFocused()
    }

    @Test
    fun manually_focused_composer_stays_focused_while_scrolling_history() {
        compose.setContent {
            MobileTheme {
                T3ChatScreen(
                    state = longThreadState().copy(
                        composer = ComposerUiState(enabled = true),
                        isTurnRunning = false,
                    ),
                    onEvent = {},
                )
            }
        }

        compose.onNodeWithTag(ComposerInputTestTag).performClick()
        compose.onNodeWithTag(ComposerInputTestTag).assertIsFocused()

        compose.onNodeWithTag(ChatTimelineTestTag).performTouchInput { swipeDown() }
        compose.onNodeWithTag(ComposerInputTestTag).assertIsFocused()
    }
}

private fun longThreadState(): ChatUiState = ChatUiState(
    title = "Long thread",
    environmentLabel = "Local",
    projectLabel = "agentic",
    threadId = "long-thread",
    connection = ChatConnectionUi.Live,
    timeline = buildList {
        repeat(250) { index ->
            add(
                ChatTimelineItemUi.Message(
                    ChatMessageUi(
                        id = "message-$index",
                        author = if (index % 2 == 0) {
                            ChatMessageAuthorUi.USER
                        } else {
                            ChatMessageAuthorUi.ASSISTANT
                        },
                        content = "Long thread message $index",
                    ),
                ),
            )
        }
        add(
            ChatTimelineItemUi.Activity(
                ChatActivityUi.Unknown(
                    id = "provider-neutral-activity",
                    summary = "Provider future activity",
                    typeLabel = "future.provider.event",
                    formattedDetail = "Preserved provider-neutral detail",
                ),
            ),
        )
        add(streamingTail("stream chunk 1"))
    },
    composer = ComposerUiState(enabled = false),
    isTurnRunning = true,
)

private fun streamingTail(content: String) = ChatTimelineItemUi.Message(
    ChatMessageUi(
        id = "streaming-tail",
        author = ChatMessageAuthorUi.ASSISTANT,
        content = content,
        isStreaming = true,
    ),
)
