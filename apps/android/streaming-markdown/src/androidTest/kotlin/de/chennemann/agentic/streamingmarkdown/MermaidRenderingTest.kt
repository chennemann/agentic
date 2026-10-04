package de.chennemann.agentic.streamingmarkdown

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MermaidRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun showTestContent() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun expanded_viewer_zooms_and_returns_to_fit() {
        compose.setContent { MermaidBlock("flowchart TD\nA --> B --> C --> D --> E --> F --> G --> H", onCopy = {}) }
        compose.onNodeWithText("Expand diagram").performClick()
        awaitDocument("document.querySelector('#diagram svg') !== null")
        awaitDocument("typeof window.diagramZoomBy === 'function'")
        compose.onNodeWithText("Zoom in").assertIsEnabled().performClick()
        awaitDocument("new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).a > 1.1")
        compose.onNodeWithText("Zoom out").performClick()
        awaitDocument("new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).a === 1")
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Fit").performClick()
        awaitDocument("new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).isIdentity")
        pinchOut()
        awaitDocument("new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).a > 1.1")
        awaitDocument("(window.panBefore = new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).e, true)")
        onView(isAssignableFrom(WebView::class.java)).perform(
            GeneralSwipeAction(Swipe.SLOW, GeneralLocation.CENTER, GeneralLocation.CENTER_RIGHT, Press.FINGER),
        )
        awaitDocument("new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).e > window.panBefore + 5")
        compose.onNodeWithText("Fit").performClick()
        awaitDocument("new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#diagram svg')).transform).isIdentity")
        compose.onNodeWithText("Close diagram").performClick()
        compose.onNodeWithText("Expand diagram").assertExists()
    }

    private fun pinchOut() {
        onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
            override fun getDescription() = "Pinch outward on the diagram"
            override fun perform(controller: UiController, view: View) {
                val start = SystemClock.uptimeMillis()
                val centerX = view.width / 2f
                val centerY = view.height / 2f
                fun send(action: Int, distance: Float, count: Int) {
                    val properties = Array(count) { index ->
                        MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
                    }
                    val coordinates = Array(count) { index ->
                        MotionEvent.PointerCoords().apply {
                            x = centerX + if (index == 0) -distance else distance
                            y = centerY
                            pressure = 1f
                            size = 1f
                        }
                    }
                    val event = MotionEvent.obtain(
                        start, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
                    )
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                    controller.loopMainThreadForAtLeast(20)
                }
                send(MotionEvent.ACTION_DOWN, view.width * 0.1f, 1)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), view.width * 0.1f, 2)
                for (step in 1..8) send(MotionEvent.ACTION_MOVE, view.width * (0.1f + step * 0.025f), 2)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), view.width * 0.3f, 2)
                send(MotionEvent.ACTION_UP, view.width * 0.3f, 1)
            }
        })
    }

    @Test
    fun swiping_over_preview_scrolls_the_conversation() {
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            list = rememberLazyListState()
            LazyColumn(state = list) {
                item {
                    Box(Modifier.testTag("preview")) {
                        MermaidBlock("flowchart TD\nA --> B --> C --> D --> E --> F --> G --> H", onCopy = {})
                    }
                }
                items(100) { Text("Message $it") }
            }
        }
        awaitDocument("document.querySelector('#diagram svg') !== null")
        compose.onNodeWithTag("preview").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("The diagram must not trap chat scrolling", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) }
    }

    @Test
    fun tall_diagram_fits_inside_the_preview() {
        compose.setContent { MermaidBlock("flowchart TD\nA --> B --> C --> D --> E --> F --> G --> H", onCopy = {}) }
        awaitDocument("(() => { const svg = document.querySelector('#diagram svg'); if (!svg) return false; const r = svg.getBoundingClientRect(); return r.top >= 0 && r.left >= 0 && r.bottom <= innerHeight && r.right <= innerWidth; })()")
    }

    @Test
    fun original_chat_example_fits_inside_the_preview() {
        val source = """
            flowchart TD
                A[Start] --> B{Is the diagram visible?}
                B -->|Yes| C[Mermaid rendering works]
                B -->|No| D[Check the source fallback]
                C --> E[Try zooming in]
                D --> F[Report what you see]
                E --> G[Done]
                F --> G
        """.trimIndent()
        compose.setContent { MermaidBlock(source, onCopy = {}) }
        awaitDocument("(() => { const svg = document.querySelector('#diagram svg'); if (!svg) return false; const r = svg.getBoundingClientRect(); return r.top >= 0 && r.left >= 0 && r.bottom <= innerHeight && r.right <= innerWidth; })()")
    }

    @Test
    fun bundled_renderer_produces_svg_without_network() {
        compose.setContent { StreamingMarkdownText("```mermaid\nflowchart TD\nA[Start] --> B[Finish]\n```", streaming = false) }
        awaitDocument("document.querySelector('#diagram svg') !== null")
    }

    @Test
    fun malformed_diagram_shows_readable_source() {
        compose.setContent { StreamingMarkdownText("```mermaid\nthis is not a diagram\n```", streaming = false) }
        awaitDocument("document.querySelector('#diagram pre')?.textContent === 'this is not a diagram'")
    }

    @Test
    fun changed_source_reloads_the_local_document() {
        val source = mutableStateOf("flowchart TD\nA[Original]")
        compose.setContent { MermaidBlock(source.value, onCopy = {}) }
        awaitDocument("document.querySelector('#diagram svg')?.textContent.includes('Original') === true")
        compose.runOnIdle { source.value = "flowchart TD\nB[Updated]" }
        awaitDocument("document.querySelector('#diagram svg')?.textContent.includes('Updated') === true")
    }

    private fun awaitDocument(expression: String) {
        val result = AtomicReference<String>()
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                onView(isAssignableFrom(WebView::class.java)).perform(object : ViewAction {
                    override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
                    override fun getDescription() = "Inspect rendered diagram"
                    override fun perform(controller: UiController, view: View) {
                        (view as WebView).evaluateJavascript(
                            "({passed: Boolean($expression), body: document.body?.innerText, renderer: typeof mermaid, ready: document.readyState})",
                            result::set,
                        )
                    }
                })
                result.get()?.contains("\"passed\":true") == true
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Diagram condition failed: $expression; document: ${result.get()}", error)
        }
    }
}
