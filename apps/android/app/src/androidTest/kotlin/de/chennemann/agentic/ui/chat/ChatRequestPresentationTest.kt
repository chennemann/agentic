package de.chennemann.agentic.ui.chat

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatRequestPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unanswered_request_is_anchored_and_can_be_collapsed_or_dismissed() {
        val events = mutableListOf<ChatUiEvent>()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.setContent {
            MobileTheme {
                T3ChatScreen(
                    state = ChatUiState(
                        title = "Test", environmentLabel = "Test", projectLabel = "Project", threadId = "thread",
                        connection = ChatConnectionUi.Live, composer = ComposerUiState(),
                        timeline = listOf(ChatTimelineItemUi.Activity(ChatActivityUi.UserInput("request", PendingUserInputUi(
                            requestId = "request", title = "Choose a direction", description = null,
                            questions = listOf(UserInputQuestionUi("q", "Which approach?", options = listOf(UserInputOptionUi("a", "First")))),
                            canSubmit = false, canDismiss = true,
                        )))),
                    ), onEvent = events::add,
                )
            }
        }
        compose.onNodeWithText("Submit answers").assertIsNotEnabled()
        compose.onNodeWithText("Or write your own answer").assertIsDisplayed()
        compose.onNodeWithText("Hide pending requests").performClick()
        compose.onNodeWithText("Response needed (1)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Dismiss question").performClick()
        compose.runOnIdle { assertEquals(ChatUiEvent.UserInputDismissed("request"), events.last()) }
    }
}
