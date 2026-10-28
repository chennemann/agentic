package de.chennemann.agentic.ui.chat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.chennemann.agentic.MainActivity
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatPagerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun showChat() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MobileTheme {
                    T3ChatScreen(
                        state = ChatUiState(
                            title = "Layout verification",
                            timeline = emptyList(),
                            connection = ChatConnectionUi.Cached(),
                            environmentLabel = "Test environment",
                            projectLabel = "Agentic",
                            projectId = "project-1",
                            threadId = "thread-1",
                            canBrowseFiles = true,
                            canUseTerminal = true,
                            composer = ComposerUiState(enabled = true),
                            threadPicker = ThreadPickerUiState(
                                projects = listOf(ProjectPickerItemUi("project-1", "Agentic")),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
        }
    }

    private fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test
    fun pages_swipe_and_navigation_does_not_open_keyboard() {
        showChat()
        compose.onNodeWithText("Tools").assertDoesNotExist()
        compose.onNodeWithText("Projects").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("Search projects").assertIsDisplayed().assertIsNotFocused()
        assertTrue("Navigation must not open the keyboard", !keyboardVisible())
        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Message").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithText("Terminal").assertIsDisplayed()
    }

    @Test
    fun open_keyboard_follows_search_and_composer() {
        showChat()
        compose.onNodeWithContentDescription("Message").performClick()
        compose.waitUntil(10_000) { keyboardVisible() }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("Search projects").assertIsFocused()
        compose.waitUntil(10_000) { keyboardVisible() }
        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Message").assertIsFocused()
        compose.waitUntil(10_000) { keyboardVisible() }
    }

    @Test
    fun tools_close_keyboard_and_clear_previous_input_focus() {
        showChat()
        compose.onNodeWithContentDescription("Message").performClick()
        compose.waitUntil(10_000) { keyboardVisible() }
        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.onNodeWithContentDescription("Message").assertIsNotFocused()
        InstrumentationRegistry.getInstrumentation().sendStringSync("x")
        compose.onNodeWithContentDescription("Message").assertIsNotFocused()
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Message").assertIsNotFocused()
        compose.waitUntil(10_000) { !keyboardVisible() }
    }
}
