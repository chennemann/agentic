package de.chennemann.agentic.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import de.chennemann.agentic.ui.theme.MobileTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatSendLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sendingFromFocusedComposerCanClearAndReplaceTheDraft() = exerciseSend(replaceDraft = true)

    @Test
    fun sendingInExistingThreadCanClearTheFocusedDraft() = exerciseSend(replaceDraft = false)

    private fun exerciseSend(replaceDraft: Boolean) {
        val draft = MutableStateFlow("Message")
        val state = mutableStateOf(
            ChatUiState(
                title = "Test thread", environmentLabel = "Test", projectLabel = "Project", threadId = if (replaceDraft) null else "thread",
                timeline = emptyList(), connection = ChatConnectionUi.Live,
                composer = ComposerUiState(draft = draft.value, draftIdentity = "project", enabled = true)
            )
        )
        compose.setContent {
            MobileTheme {
                T3ChatScreen(state.value, composerDraft = draft, onEvent = { event ->
                    if (event == ChatUiEvent.MessageSubmitted) {
                        state.value = state.value.copy(composer = state.value.composer.copy(sending = true))
                    }
                })
            }
        }
        repeat(3) { attempt ->
            compose.runOnIdle {
                draft.value = "Message $attempt"
                state.value = state.value.copy(
                    isTurnRunning = false,
                    composer = state.value.composer.copy(draft = draft.value, sending = false)
                )
            }
            compose.onNodeWithContentDescription("Message").performClick()
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.runOnIdle { assertTrue(state.value.composer.sending) }
            compose.runOnIdle {
                draft.value = ""
                state.value = state.value.copy(
                    threadId = if (replaceDraft) "thread-$attempt" else "thread", isTurnRunning = true,
                    composer = state.value.composer.copy(draft = "", sending = false, draftIdentity = if (replaceDraft) "thread-$attempt" else "project")
                )
            }
            compose.onNodeWithContentDescription("Stop turn").assertIsDisplayed()
            compose.onNodeWithContentDescription("Message").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
        }
    }
}
