package de.chennemann.opencode.mobile.streamingmarkdown

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingMarkdownComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keeps_streaming_and_snapshot_rendering_in_parity_across_updates() {
        val state = mutableStateOf("start `code`")

        compose.setContent {
            Column {
                StreamingMarkdownText(
                    content = state.value,
                    streaming = true,
                    modifier = Modifier.testTag("stream"),
                )
                StreamingMarkdownText(
                    content = state.value,
                    streaming = false,
                    modifier = Modifier.testTag("snapshot"),
                )
            }
        }
        compose.onNodeWithTag("stream").assertIsDisplayed()
        compose.onNodeWithTag("snapshot").assertIsDisplayed()

        listOf(
            "start `code`",
            "start `code` and *emphasis*",
            "visit [site](https://example.com) now",
            "a\n```kotlin\nval x = 1\n```\nb",
            "reset `done`",
        ).forEach { text ->
            compose.runOnIdle {
                state.value = text
            }
            compose.waitUntil(2_000) {
                semantics("stream") == semantics("snapshot")
            }

            val stream = semantics("stream")
            val snapshot = semantics("snapshot")
            assertEquals(snapshot, stream)
        }
    }

    private fun semantics(tag: String): List<AnnotatedString> {
        val config = compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
        if (!config.contains(SemanticsProperties.Text)) return emptyList()
        return config[SemanticsProperties.Text]
    }
}
