package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.chennemann.agentic.ui.theme.MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThreadLineagePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disclosure_expands_without_selection_and_child_can_be_selected_then_collapsed() {
        val selections = mutableListOf<String>()
        var selected by mutableStateOf("parent")
        compose.setContent {
            MobileTheme {
                Column {
                    ProjectThreads(
                        ThreadPickerUiState(
                            threads = listOf(ThreadPickerItemUi("parent", "Main conversation")),
                            subagents = mapOf("parent" to listOf(ThreadPickerItemUi("child", "Contract review", active = true))),
                            selectedThreadId = selected,
                        ),
                        onEvent = {},
                        onThreadChosen = { selections += it; selected = it },
                        onNewThread = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Contract review").assertDoesNotExist()
        compose.onNodeWithText("1 sub-agent · 1 working").performClick()
        compose.onNodeWithText("Contract review").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), selections) }
        compose.onNodeWithText("Contract review").performClick()
        compose.runOnIdle { assertEquals(listOf("child"), selections) }
        compose.onNodeWithText("Main conversation").assertIsDisplayed()
        compose.onNodeWithText("1 sub-agent · 1 working").performClick()
        compose.onNodeWithText("Contract review").assertDoesNotExist()
    }
}
