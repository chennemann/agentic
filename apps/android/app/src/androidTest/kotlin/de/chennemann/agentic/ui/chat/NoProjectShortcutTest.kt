package de.chennemann.agentic.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoProjectShortcutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun new_thread_button_distinguishes_tap_from_long_press() {
        val events = mutableListOf<ChatUiEvent>()
        compose.setContent {
            MobileTheme {
                T3ChatScreen(
                    state = ChatUiState(
                        title = "New thread",
                        environmentLabel = "Test environment",
                        projectLabel = "Test project",
                        threadId = null,
                        timeline = emptyList(),
                        connection = ChatConnectionUi.Live,
                        composer = ComposerUiState(enabled = true),
                    ),
                    onEvent = { events += it },
                )
            }
        }

        compose.onNodeWithContentDescription("New thread").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(ChatUiEvent.ScratchProjectRequested), events)
            events.clear()
        }

        compose.onNodeWithContentDescription("New thread").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ChatUiEvent.NewThreadRequested), events)
        }
    }
}
