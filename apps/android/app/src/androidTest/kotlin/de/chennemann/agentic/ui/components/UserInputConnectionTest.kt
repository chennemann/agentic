package de.chennemann.agentic.ui.components

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import de.chennemann.agentic.ui.chat.ChatActivityUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.PendingUserInputUi
import de.chennemann.agentic.ui.chat.UserInputOptionUi
import de.chennemann.agentic.ui.chat.UserInputQuestionUi
import de.chennemann.agentic.ui.theme.MobileTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class UserInputConnectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var connection: InputConnection

    // Use the field's real Android InputConnection, with one controlled IME driver.
    // A concurrently attached software keyboard can finish our composing region.
    private val ime = PlatformTextInputInterceptor { request, _ ->
        connection = request.createInputConnection(EditorInfo())
        awaitCancellation()
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            InterceptPlatformTextInput(ime) {
                MobileTheme(content = content)
            }
        }
    }

    @Test
    fun composing_edits_keep_cursor_and_candidates_when_timeline_feedback_is_delayed() {
        val question = UserInputQuestionUi("question", "Question")
        val request = mutableStateOf(
            PendingUserInputUi("request", "Answer", null, listOf(question)),
        )
        val edits = mutableListOf<ChatUiEvent.UserInputTextChanged>()
        setContent {
            ChatActivityCard(ChatActivityUi.UserInput("activity", request.value), false, {
                if (it is ChatUiEvent.UserInputTextChanged) edits += it
            })
        }
        compose.onNodeWithText("Your answer").performClick()
        compose.runOnIdle {
            connection.setComposingText("e", 1)
        }
        compose.waitForIdle()
        compose.runOnIdle { request.value = request.value.copy(questions = listOf(question.copy(textAnswer = "e"))) }
        assertEditor("e")
        compose.runOnIdle { connection.setComposingText("ee", 1) }
        assertEditor("ee")

        // An older mapping completes while the IME still owns the composing region.
        compose.runOnIdle {
            request.value = request.value.copy(questions = listOf(question.copy(textAnswer = "e", description = "Updated question")))
        }
        assertEditor("ee")
        compose.runOnIdle { connection.setComposingText("each", 1) }
        assertEditor("each")
        compose.runOnIdle {
            connection.finishComposingText()
            connection.commitText(" ", 1)
        }
        assertEditor("each ")
        compose.runOnIdle { assertEquals(listOf("e", "ee", "each", "each "), edits.map { it.value }) }
    }

    @Test
    fun choosing_an_option_clears_the_editor_before_the_model_echoes_the_change() {
        val question = UserInputQuestionUi(
            "question",
            "Question",
            textAnswer = "Saved answer",
            options = listOf(UserInputOptionUi("option", "Suggested answer")),
        )
        val request = mutableStateOf(PendingUserInputUi("request", "Answer", null, listOf(question)))
        val events = mutableListOf<ChatUiEvent>()
        setContent {
            ChatActivityCard(ChatActivityUi.UserInput("activity", request.value), false, events::add)
        }
        val label = "Or write your own answer"
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText("Suggested answer").performClick()
        assertEditor("", label)
        compose.runOnIdle {
            assertEquals(listOf(ChatUiEvent.UserInputOptionToggled("request", "question", "option")), events)
            request.value = request.value.copy(title = "Updated request")
        }
        assertEditor("", label)
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle {
            connection.setComposingText("e", 1)
        }
        assertEditor("e", label)
        compose.runOnIdle {
            request.value = request.value.copy(questions = listOf(question.copy(textAnswer = "", selectedOptionIds = setOf("option"))))
        }
        assertEditor("e", label)
        compose.runOnIdle { assertEquals(ChatUiEvent.UserInputTextChanged("request", "question", "e"), events.last()) }
    }

    @Test
    fun replacement_request_and_question_seed_their_own_answer() {
        val question = UserInputQuestionUi("question", "Question", textAnswer = "First answer")
        val request = mutableStateOf(PendingUserInputUi("first", "Answer", null, listOf(question)))
        setContent {
            ChatActivityCard(ChatActivityUi.UserInput("activity", request.value), false, {})
        }
        assertEquals("First answer", editorText())
        compose.runOnIdle {
            request.value = request.value.copy(requestId = "second", questions = listOf(question.copy(textAnswer = "Second answer")))
        }
        assertEquals("Second answer", editorText())
        compose.runOnIdle {
            request.value = request.value.copy(questions = listOf(question.copy(id = "other", textAnswer = "Other answer")))
        }
        assertEquals("Other answer", editorText())
    }

    private fun editorText() = compose.onNodeWithText("Your answer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun assertEditor(text: String, label: String = "Your answer") {
        val node = compose.onNodeWithText(label).fetchSemanticsNode()
        assertEquals(text, node.config[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(text.length), node.config[SemanticsProperties.TextSelectionRange])
    }
}
