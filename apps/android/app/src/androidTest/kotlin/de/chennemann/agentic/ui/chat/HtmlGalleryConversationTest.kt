package de.chennemann.agentic.ui.chat

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HtmlGalleryConversationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingTheGalleryAddsAnnotationsForEveryEditedImageToTheReply() {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val items = listOf("First.png", "Second.png", "Hidden.png").map { ImageViewerItem.Conversation(dataUrl, it) }
        val events = mutableListOf<ChatUiEvent>()
        var dismissed = false
        val state = ChatUiState("Thread", "Environment", null, "thread", emptyList(), connection = ChatConnectionUi.Live, composer = ComposerUiState())
        compose.setContent {
            MaterialTheme {
                ImageViewerHost(ImageViewerRequest(items, 1), state, events::add) { dismissed = true }
            }
        }
        compose.onNodeWithContentDescription("Annotate Second.png").performClick()
        compose.onNodeWithContentDescription("Annotation canvas").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Hidden.png").assertExists()
        compose.onNodeWithContentDescription("Annotation canvas").performClick()
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            val feedback = events.filterIsInstance<ChatUiEvent.ConversationImageAnnotated>()
            assertEquals(listOf("Second.png", "Hidden.png"), feedback.map { it.name })
            assertTrue(feedback.all { it.annotations.size == 1 && it.dataUrl == dataUrl && it.burnedAnnotationCount == 0 })
        }
    }
}
