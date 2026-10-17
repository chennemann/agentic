package de.chennemann.agentic.ui.components

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import de.chennemann.agentic.domain.attachments.ImageAnnotation
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ImageViewerTest {
    @get:Rule val compose = createComposeRule()

    private val images = listOf("first.png", "second.png", "third.png").map {
        ViewerImage(key = it, dataUrl = pngDataUrl(), name = it)
    }

    @Test fun swipingMovesThroughTheImagesFromTheOpenedOne() {
        compose.setContent { MaterialTheme { ImageViewerDialog(images, initialIndex = 1, onClose = {}) } }

        compose.onNodeWithText("second.png").assertIsDisplayed()
        compose.onNodeWithText("2 of 3").assertIsDisplayed()
        compose.onNodeWithTag(ImageViewerTestTag).performTouchInput { swipeLeft() }
        compose.onNodeWithText("third.png").assertIsDisplayed()
        compose.onNodeWithText("3 of 3").assertIsDisplayed()
    }

    @Test fun doubleTapZoomsSoSwipingPansInsteadOfTurningThePage() {
        compose.setContent { MaterialTheme { ImageViewerDialog(images, initialIndex = 0, onClose = {}) } }

        compose.onNodeWithTag(ImageViewerStageTestTag).performTouchInput { doubleClick() }
        compose.onNodeWithTag(ImageViewerTestTag).performTouchInput { swipeLeft() }
        compose.onNodeWithText("1 of 3").assertIsDisplayed()

        compose.onNodeWithTag(ImageViewerStageTestTag).performTouchInput { doubleClick() }
        compose.onNodeWithTag(ImageViewerTestTag).performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 of 3").assertIsDisplayed()
    }

    @Test fun swipingDownClosesUnlessTheImageIsZoomed() {
        var closes = 0
        compose.setContent { MaterialTheme { ImageViewerDialog(images, initialIndex = 0, onClose = { closes++ }) } }

        compose.onNodeWithTag(ImageViewerStageTestTag).performTouchInput { doubleClick() }
        compose.onNodeWithTag(ImageViewerTestTag).performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, closes) }

        compose.onNodeWithTag(ImageViewerStageTestTag).performTouchInput { doubleClick() }
        compose.onNodeWithTag(ImageViewerTestTag).performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun annotatingHoldsThePageAndClosingReportsTheMarks() {
        var closed: Map<String, List<ImageAnnotation>>? = null
        compose.setContent { MaterialTheme { ImageViewerDialog(images, initialIndex = 0, onClose = { closed = it }) } }

        compose.onNodeWithContentDescription("Annotate first.png").performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithContentDescription("Annotation canvas").performClick()
        // While annotating, a one-finger swipe draws a second mark instead of turning the page.
        compose.onNodeWithTag(ImageViewerTestTag).performTouchInput { swipeLeft() }
        compose.onNodeWithText("1 of 3").assertIsDisplayed()

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Annotate first.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close image").performClick()
        assertEquals(2, closed?.get("first.png")?.size)
        assertEquals(0, closed?.get("second.png")?.size)
    }

    private fun pngDataUrl(): String {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
