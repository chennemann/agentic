package de.chennemann.agentic.ui.review

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import de.chennemann.agentic.domain.orchestration.ReviewSection
import de.chennemann.agentic.ui.theme.MobileTheme
import expo.modules.t3reviewdiff.T3ReviewDiffView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewDiffScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(ReviewDiffUiState())
    private val open = mutableStateOf(true)
    private var refreshes = 0
    private var selected: String? = null

    private fun show(longLine: Boolean = false) {
        val content = if (longLine) {
            "val message = \"" + "long value ".repeat(
                40
            ) + "\""
        } else {
            "val message = \"Hello, world!\""
        }
        state.value = ReviewDiffUiState(
            title = "Review example",
            sections = listOf(ReviewSection("turn:2", "Turn 2", 1, 2), ReviewSection("all", "All turns", 0, 2, true)),
            selectedId = "turn:2",
            document = parseReviewDiff(
                "diff --git a/src/Greeting.kt b/src/Greeting.kt\n--- a/src/Greeting.kt\n+++ b/src/Greeting.kt\n@@ -1,3 +1,3 @@\n fun greet() {\n-    println(\"Old greeting\")\n+    $content\n }\n"
            ),
            revision = 1
        )
        compose.setContent {
            MobileTheme {
                if (open.value) {
                    ReviewDiffScreen(state.value, {
                        open.value = false
                    }, { refreshes++ }, { selected = it })
                } else {
                    Text("Chat")
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(15000) { greenRows() > 0 }
    }

    private fun find(view: View): T3ReviewDiffView? {
        if (view is T3ReviewDiffView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun greenRows(): Int = compose.runOnIdle {
        val view = find(compose.activity.window.decorView) ?: return@runOnIdle 0
        if (view.height <= 0) return@runOnIdle 0
        val strip = Bitmap.createBitmap(1, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.translate(-view.resources.displayMetrics.density, 0f)
        view.draw(canvas)
        val count = (0 until strip.height).count { y ->
            val color = strip.getPixel(0, y)
            color == 0xFFA0CC5B.toInt() || color == 0xFF326B1D.toInt()
        }
        strip.recycle()
        count
    }

    @Test fun nativeCanvasRendersOnAFullPageAndNavigatesBack() {
        show()
        compose.onNodeWithTag("review-diff-page").assertExists()
        compose.onNodeWithText("Turn 2").performClick()
        compose.onNodeWithText("All turns").performClick()
        assertEquals("all", selected)
        compose.onNodeWithText("Files (1)").performClick()
        compose.onNodeWithText("src/Greeting.kt").performClick()
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("review-diff-page").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "review-diff.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Chat").assertExists()
        compose.onNodeWithTag("review-diff-page").assertDoesNotExist()
    }

    @Test fun wrappingUsesTheSameNativeViewAndExpandsLongCodeRows() {
        show(longLine = true)
        val before = greenRows()
        val view = compose.runOnIdle { find(compose.activity.window.decorView) }
        compose.onNodeWithText("Wrap").performClick()
        compose.waitUntil(15000) { greenRows() > before * 2 }
        compose.runOnIdle { assertTrue(view === find(compose.activity.window.decorView)) }
    }

    @Test fun nativeHeaderTapCollapsesAndExpandsItsFile() {
        show()
        val scale = compose.activity.resources.displayMetrics.density
        val header = Offset(20f * scale, 24f * scale)
        compose.onNodeWithTag("review-diff-surface").performTouchInput { click(header) }
        compose.waitUntil(15000) { greenRows() == 0 }
        compose.onNodeWithTag("review-diff-surface").performTouchInput { click(header) }
        compose.waitUntil(15000) { greenRows() > 0 }
    }

    @Test fun leavingDisposesTheExecutorAndLatePayloadsAreIgnored() {
        show()
        val view = compose.runOnIdle { find(compose.activity.window.decorView)!! }
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle {
            view.cleanup()
            view.setRowsJson("[]")
            view.setTokensJson("{}")
            view.setTokensPatchJson("{}")
            assertTrue(find(compose.activity.window.decorView) == null)
        }
    }

    @Test fun loadingErrorsAndEmptyRangesNeverRetainThePreviousPatch() {
        show()
        compose.runOnIdle { state.value = state.value.copy(document = ReviewDocument(), loading = true) }
        compose.onNodeWithTag("review-diff-surface").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "Connection unavailable") }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, refreshes)
        compose.runOnIdle { state.value = state.value.copy(error = null) }
        compose.onNodeWithText("No changes in this range.").assertExists()
        assertFalse(state.value.loading)
    }
}
