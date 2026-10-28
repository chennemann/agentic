package de.chennemann.agentic.ui.components

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.webkit.WebView
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import de.chennemann.agentic.domain.attachments.HtmlRenderPage
import de.chennemann.agentic.ui.chat.HtmlPageUi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.chennemann.agentic.ui.chat.ImageViewerRequest
import de.chennemann.agentic.ui.chat.ImageViewerItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlin.math.abs

class HtmlRenderViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixture get() = instrumentation.context.assets.open("html-render-gallery.html").bufferedReader().use { it.readText() }

    @Test fun referenceGalleryFitsAtCompactInterfaceScale() = assertReferenceGalleryFits(0.9f)
    @Test fun referenceGalleryFitsAtDefaultInterfaceScale() = assertReferenceGalleryFits(1f)
    @Test fun referenceGalleryFitsAtLargeInterfaceScale() = assertReferenceGalleryFits(1.15f)

    private fun assertReferenceGalleryFits(scale: Float) {
        val bitmap = Bitmap.createBitmap(1416, 919, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val document = instrumentation.context.assets.open("html-render-responsive-gallery.html").bufferedReader().use { it.readText() }
            .replace("__TEST_IMAGE__", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .replace("</body>", "<script>window.fixtureReady=true;</script></body>")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                de.chennemann.agentic.ui.theme.MobileTheme(darkTheme = true) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        item { HtmlRenderView(HtmlRenderPage("reference.html", "Gallery", 720), HtmlPageUi.Loaded(document), {}, {}) }
                        item { Text("Following assistant message") }
                    }
                }
            }
        }
        awaitBridge()
        try {
            awaitInlineHeight()
            compose.waitUntil(10_000) {
                val bottom = evaluate("document.querySelector('.hint').getBoundingClientRect().bottom")?.toFloatOrNull()
                bottom != null && webViewHeightInCssPixels() + 1 >= bottom
            }
            var documentBottom = 0
            instrumentation.runOnMainSync {
                val view = requireNotNull(findWebView(compose.activity.window.decorView))
                val position = IntArray(2)
                view.getLocationInWindow(position)
                documentBottom = position[1] + view.height
            }
            assertTrue("Expand must have its own row below the document", compose.onNodeWithText("Expand").fetchSemanticsNode().boundsInWindow.top >= documentBottom)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "gallery-$scale.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } catch (cause: Throwable) {
            throw AssertionError("Scale $scale: native height=${webViewHeightInCssPixels()}, document bounds=" +
                evaluate("JSON.stringify({height:document.documentElement.getBoundingClientRect().height,footer:document.querySelector('footer').getBoundingClientRect().bottom,hint:document.querySelector('.hint').getBoundingClientRect().bottom,ratio:window.devicePixelRatio})"), cause)
        }
    }

    @Test fun inlinePagesGrowShrinkAndScrollWithTheConversation() {
        val document = fixture.replace("</body>", "<div id='tall-content' style='height:4800px'></div></body>")
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("html-chat")) {
                    item {
                        HtmlRenderView(HtmlRenderPage("tall.html", "Gallery", 240), HtmlPageUi.Loaded(document), {}, {})
                    }
                    item { Text("After the visualization") }
                }
            }
        }
        awaitBridge()
        awaitInlineHeight()
        assertTrue(requireNotNull(evaluate("document.documentElement.getBoundingClientRect().height")).toFloat() > 4800)
        evaluate("document.getElementById('tall-content').style.height='6000px'")
        awaitInlineHeight()
        assertTrue(webViewHeightInCssPixels() > 6000)
        evaluate("document.getElementById('tall-content').style.height='2800px'")
        awaitInlineHeight()
        assertTrue(webViewHeightInCssPixels() in 2800f..4000f)
        compose.onNodeWithTag("html-chat").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(list.firstVisibleItemScrollOffset > 0 || list.firstVisibleItemIndex > 0) }
        assertEquals("0", evaluate("window.scrollY"))
        // Height changes must not discard the page's scripts or its gallery helper.
        assertEquals("true", evaluate("Boolean(window.fixtureReady && window.t3.openImages)"))
    }

    private fun awaitInlineHeight() {
        compose.waitUntil(10_000) {
            val height = evaluate("document.documentElement.getBoundingClientRect().height")?.toFloatOrNull()
            height != null && abs(height - webViewHeightInCssPixels()) < 2f
        }
    }

    private fun webViewHeightInCssPixels(): Float {
        var height = 0f
        instrumentation.runOnMainSync {
            findWebView(compose.activity.window.decorView)?.let { height = it.height / it.resources.displayMetrics.density }
        }
        return height
    }

    @Test fun publishedBootstrapOpensHiddenSlidesAndScopesOutDecoration() {
        val request = AtomicReference<ImageViewerRequest>()
        compose.setContent { MaterialTheme { HtmlDocumentView(fixture, "Gallery", Modifier.fillMaxSize(), onOpenImages = { request.set(it) }) } }
        awaitBridge()
        // Dispatch at the DOM node to exercise the real server-injected click listener.
        tapElement("selected")
        compose.waitUntil(10_000) { request.get() != null }
        assertEquals(1, request.get().index)
        assertEquals(listOf("First.png", "Second.png", "Hidden.png"), request.get().items.map { (it as ImageViewerItem.Conversation).name })
    }

    @Test fun tappingPublishedImageOpensViewerAndSavesAnnotationsToReply() {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val document = fixture.replace(
            Regex("(?<=base64,)[A-Za-z0-9+/=]+"),
            Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
        var request by mutableStateOf<ImageViewerRequest?>(null)
        val events = mutableListOf<de.chennemann.agentic.ui.chat.ChatUiEvent>()
        val state = de.chennemann.agentic.ui.chat.ChatUiState(
            "Thread", "Environment", null, "thread", emptyList(),
            connection = de.chennemann.agentic.ui.chat.ChatConnectionUi.Live,
            composer = de.chennemann.agentic.ui.chat.ComposerUiState(),
        )
        compose.setContent {
            MaterialTheme {
                HtmlDocumentView(document, "Gallery", Modifier.fillMaxSize(), onOpenImages = { request = it })
                request?.let { gallery ->
                    de.chennemann.agentic.ui.chat.ImageViewerHost(gallery, state, events::add) { request = null }
                }
            }
        }
        awaitBridge()
        tapElement("selected")
        compose.onNodeWithContentDescription("Annotate Second.png").performClick()
        compose.onNodeWithContentDescription("Annotation canvas").performClick()
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.runOnIdle {
            val feedback = events.filterIsInstance<de.chennemann.agentic.ui.chat.ChatUiEvent.ConversationImageAnnotated>().single()
            assertEquals("Second.png", feedback.name)
            assertEquals(1, feedback.annotations.size)
            assertEquals(null, request)
        }
    }

    @Test fun explicitGalleryHandsOffEveryImageAndRejectsInvalidRequests() {
        val request = AtomicReference<ImageViewerRequest>()
        compose.setContent { MaterialTheme { HtmlDocumentView(fixture, "Gallery", Modifier.fillMaxSize(), onOpenImages = { request.set(it) }) } }
        awaitBridge()
        tapElement("explicit")
        compose.waitUntil(10_000) { request.get() != null }
        val opened = request.get()
        assertEquals(1, opened.index)
        assertEquals(3, opened.items.size)
        evaluate("window.ReactNativeWebView.postMessage(JSON.stringify({jsonrpc:'2.0',method:'t3/notifications/open-image',params:{images:[{src:'file:///private.png',name:'bad'}],index:0}}))")
        compose.waitForIdle()
        assertEquals(opened, request.get())
    }

    @Test fun themeChangesKeepTheDocumentAndScriptStateAlive() {
        var dark by mutableStateOf(false)
        compose.setContent {
            de.chennemann.agentic.ui.theme.MobileTheme(darkTheme = dark) {
                HtmlDocumentView(fixture, "Gallery", Modifier.fillMaxSize(), onOpenImages = {})
            }
        }
        awaitBridge()
        evaluate("window.savedGalleryPosition=2")
        compose.runOnIdle { dark = true }
        compose.waitUntil(10_000) { evaluate("document.documentElement.style.colorScheme || getComputedStyle(document.documentElement).colorScheme") == "\"dark\"" }
        assertEquals("2", evaluate("window.savedGalleryPosition"))
    }

    private fun awaitBridge() {
        compose.waitUntil(15_000) { evaluate("Boolean(window.ReactNativeWebView && window.t3 && window.fixtureReady)") == "true" }
    }

    private fun tapElement(id: String) {
        val point = Json.parseToJsonElement(requireNotNull(evaluate("(function(){var r=document.getElementById('$id').getBoundingClientRect();return [(r.left+r.width/2)/window.innerWidth,(r.top+r.height/2)/window.innerHeight];})()"))).jsonArray
        val position = IntArray(2)
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val view = requireNotNull(findWebView(compose.activity.window.decorView))
            view.getLocationOnScreen(position)
            x = position[0] + point[0].jsonPrimitive.float * view.width
            y = position[1] + point[1].jsonPrimitive.float * view.height
        }
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
        MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
    }

    private fun evaluate(script: String): String? {
        val result = AtomicReference<String>()
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val webView = findWebView(compose.activity.window.decorView)
            if (webView == null) complete.countDown() else webView.evaluateJavascript(script) { result.set(it); complete.countDown() }
        }
        assertTrue("JavaScript evaluation timed out", complete.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
