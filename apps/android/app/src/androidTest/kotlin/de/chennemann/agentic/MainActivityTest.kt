package de.chennemann.agentic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launch_smoke_keeps_main_host_visible() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open settings").assertIsDisplayed()
    }

    @Test
    fun renders_initial_compose_host_controls() {
        compose.waitForIdle()
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("Build").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open wireless debugging settings").assertIsDisplayed()
    }
}
