package de.chennemann.agentic.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class T3ChatScreenTerminalTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun terminalAndNavigationControlsDoNotOverlap() {
        compose.setContent {
            MobileTheme {
                T3ChatScreen(
                    state = ChatUiState(
                        title = "Thread",
                        environmentLabel = "Environment",
                        projectLabel = "Project",
                        threadId = "thread-1",
                        timeline = emptyList(),
                        connection = ChatConnectionUi.Live,
                        composer = ComposerUiState(),
                        canUseTerminal = true,
                    ),
                    onEvent = {},
                )
            }
        }

        val navigation = compose.onNodeWithTag(NavigationControlTestTag).fetchSemanticsNode().boundsInRoot
        val terminal = compose.onNodeWithTag(TerminalControlTestTag).fetchSemanticsNode().boundsInRoot

        assertTrue("Terminal and navigation controls overlap", !navigation.overlaps(terminal))
    }
}
