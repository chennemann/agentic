package de.chennemann.agentic.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.chennemann.agentic.domain.attachments.HtmlRenderPage
import de.chennemann.agentic.ui.chat.HtmlPageUi
import de.chennemann.agentic.ui.chat.ImageViewerItem
import de.chennemann.agentic.ui.chat.ImageViewerRequest
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

@Composable
internal fun HtmlRenderView(
    page: HtmlRenderPage,
    content: HtmlPageUi?,
    onLoad: () -> Unit,
    onOpenImages: (ImageViewerRequest) -> Unit,
) {
    LaunchedEffect(page.attachmentId) { onLoad() }
    var contentHeightPixels by rememberSaveable(page.attachmentId) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val webDensity = LocalContext.current.resources.displayMetrics.density
    var expanded by remember(page) { mutableStateOf(false) }
    if (expanded && content is HtmlPageUi.Loaded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                TextButton(onClick = { expanded = false }) { Text("Close ${page.title}") }
                HtmlDocumentView(content.document, page.title, Modifier.weight(1f).fillMaxWidth(), onOpenImages = onOpenImages)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Web content uses CSS pixels. Compose's density also includes the app's interface scale.
            val cssWidth = kotlin.math.ceil(constraints.maxWidth / webDensity.toDouble()).toInt()
            val heightPixels = contentHeightPixels ?: kotlin.math.ceil(page.frameHeight(cssWidth) * webDensity.toDouble()).toInt()
            val height = with(density) { heightPixels.toDp() }
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                when (content) {
                    is HtmlPageUi.Loaded -> HtmlDocumentView(
                        document = content.document,
                        title = page.title,
                        modifier = Modifier.fillMaxSize(),
                        inline = true,
                        onHeightPixels = { contentHeightPixels = it },
                        onOpenImages = onOpenImages,
                    )
                    HtmlPageUi.Failed -> TextButton(onClick = onLoad) { Text("Reload ${page.title}") }
                    else -> CircularProgressIndicator()
                }
            }
        }
        if (content is HtmlPageUi.Loaded) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.End)) { Text("Expand") }
        }
    }
}

@Composable
internal fun HtmlDocumentView(
    document: String,
    title: String,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    onHeightPixels: (Int) -> Unit = {},
    onOpenImages: (ImageViewerRequest) -> Unit,
) {
    val theme = htmlRenderTheme()
    val openImages by rememberUpdatedState(onOpenImages)
    val reportHeight by rememberUpdatedState(onHeightPixels)
    var failed by remember(document) { mutableStateOf(false) }
    var generation by remember(document) { mutableStateOf(0) }
    if (failed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            TextButton(onClick = { failed = false; generation++ }) { Text("Reload $title") }
        }
        return
    }
    key(document, generation) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                HtmlPageWebView(context, document, theme, inline) { view, message ->
                    val gallery = readHtmlGalleryRequest(message)
                    if (gallery != null) {
                        openImages(ImageViewerRequest(gallery.images.map {
                            if (it.source.startsWith("data:", ignoreCase = true)) ImageViewerItem.Conversation(it.source, it.name)
                            else ImageViewerItem.Remote(it.source, it.name)
                        }, gallery.index))
                    } else if (message.text("jsonrpc") == "2.0" && message.text("method") == "ui/notifications/size-changed") {
                        val params = message["params"] as? JsonObject
                        val height = (params?.get("height") as? JsonPrimitive)?.doubleOrNull
                        val width = (params?.get("width") as? JsonPrimitive)?.doubleOrNull
                        if (height != null && height.isFinite() && height > 0 && width != null && width.isFinite() && width > 0 && view.width > 0) {
                            reportHeight(kotlin.math.ceil(height * view.width / width).toInt().coerceAtLeast(1))
                        }
                    }
                }.apply {
                    contentDescription = title
                    onFailure = { failed = true }
                }
            },
            update = { it.setTheme(theme) },
            onRelease = { it.release() },
        )
    }
}

/** Only the top document receives the message port; subframes get no native interface. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private class HtmlPageWebView(
    context: Context,
    private val document: String,
    private var theme: String,
    private val inline: Boolean,
    private val onMessage: (WebView, JsonObject) -> Unit,
) : WebView(context) {
    private val origin = "https://${UUID.randomUUID()}.t3-html.invalid"
    private val pageUrl = "$origin/page.html"
    private var ports: Array<WebMessagePort>? = null
    private var ready = false
    var onFailure: () -> Unit = {}

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.useWideViewPort = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString().substringBefore('#')
                if (url == pageUrl && request.isForMainFrame) return WebResourceResponse(
                    "text/html", "UTF-8", 200, "OK",
                    mapOf("Content-Security-Policy" to "sandbox allow-scripts allow-forms allow-popups allow-downloads"),
                    ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)),
                )
                if (request.url.scheme?.lowercase() in setOf("https", "http", "data", "blob")) return null
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString().substringBefore('#') == pageUrl) return false
                if (request.isForMainFrame && request.hasGesture()) openLink(request.url)
                return request.isForMainFrame
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.substringBefore('#') != pageUrl || ready) return
                ready = true
                val channel = createWebMessageChannel()
                ports = channel
                channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                        val data = message?.data?.let(::parseHtmlMessage) ?: return
                        if (data.text("method") == "ui/open-link" && data.text("jsonrpc") == "2.0") {
                            (data["params"] as? JsonObject)?.text("url")?.let { openLink(Uri.parse(it)) }
                        } else onMessage(this@HtmlPageWebView, data)
                    }
                })
                evaluateJavascript("window.t3InlineRender=$inline;$HtmlHostBridge") {
                    // Sandboxed HTML has an opaque origin. This API always targets the main frame.
                    postWebMessage(WebMessage("t3-html-port", arrayOf(channel[1])), Uri.parse("*"))
                    postTheme()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                onFailure()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val popup = WebView(context)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openLink(request.url)
                        popup.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        loadUrl("$pageUrl#t3-theme=${Uri.encode(theme)}")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        // Inline pages grow to their content; vertical drags always belong to the conversation.
        parent?.requestDisallowInterceptTouchEvent(!inline && (canScrollVertically(-1) || canScrollVertically(1)))
        return handled
    }

    fun setTheme(value: String) {
        if (theme == value) return
        theme = value
        if (ready) postTheme()
    }

    private fun postTheme() {
        evaluateJavascript("window.postMessage({jsonrpc:'2.0',method:'ui/notifications/host-context-changed',params:{theme:($theme).appearance,styles:{variables:($theme).variables}}},'*');", null)
    }

    private fun openLink(uri: Uri) {
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun release() {
        ports?.forEach { runCatching { it.close() } }
        stopLoading()
        destroy()
    }
}

// The server supplies window.t3.openImages and its automatic <img> gallery behavior.
// This adapter provides the same mobile transport as T3's React Native client.
private val HtmlHostBridge = """
    (function(){
      window.addEventListener('message',function connect(e){
        if(e.data!=='t3-html-port'||!e.ports[0])return;
        window.removeEventListener('message',connect);
        var port=e.ports[0],last;
        window.ReactNativeWebView={postMessage:function(value){port.postMessage(value);}};
        if(window.t3InlineRender)document.documentElement.style.setProperty('overflow-y','hidden','important');
        function report(){var r=document.documentElement,w=window.visualViewport?window.visualViewport.width:window.innerWidth;
          var h=Math.ceil(r.scrollHeight>r.clientHeight?r.scrollHeight:r.getBoundingClientRect().height),size=h+','+w;
          if(size!==last){last=size;port.postMessage(JSON.stringify({jsonrpc:'2.0',method:'ui/notifications/size-changed',params:{height:h,width:w}}));}}
        var scheduled=false;function schedule(){if(scheduled)return;scheduled=true;requestAnimationFrame(function(){scheduled=false;report();});}
        if(window.ResizeObserver){var observer=new ResizeObserver(schedule);observer.observe(document.documentElement);if(document.body)observer.observe(document.body);}
        if(window.MutationObserver)new MutationObserver(schedule).observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});
        document.addEventListener('load',schedule,true);
        if(document.fonts)document.fonts.ready.then(schedule);
        window.addEventListener('resize',schedule);report();
        document.addEventListener('click',function(e){
          var link=e.composedPath().find(function(t){return t&&t.matches&&t.matches('a[href]');});
          if(!link||!e.isTrusted)return;var url=new URL(link.href,document.baseURI);
          if(!/^https?:$/.test(url.protocol)||url.href.split('#')[0]===location.href.split('#')[0])return;
          e.preventDefault();port.postMessage(JSON.stringify({jsonrpc:'2.0',method:'ui/open-link',params:{url:url.href}}));
        },true);
      });
    })();
""".trimIndent()

@Composable
private fun htmlRenderTheme(): String {
    val colors = MaterialTheme.colorScheme
    fun Color.css() = "#%06x".format(toArgb() and 0xffffff)
    val variables = mapOf(
        "background" to colors.background, "foreground" to colors.onBackground,
        "muted" to colors.surfaceVariant, "muted-foreground" to colors.onSurfaceVariant,
        "card" to colors.surfaceContainer, "card-foreground" to colors.onSurface,
        "popover" to colors.surfaceContainerHigh, "popover-foreground" to colors.onSurface,
        "secondary" to colors.secondaryContainer, "secondary-foreground" to colors.onSecondaryContainer,
        "border" to colors.outlineVariant, "input" to colors.outlineVariant, "ring" to colors.primary,
        "primary" to colors.primary, "primary-foreground" to colors.onPrimary,
        "accent" to colors.primary, "accent-foreground" to colors.primary,
        "accent-surface" to colors.primaryContainer, "accent-surface-foreground" to colors.onPrimaryContainer,
        "destructive" to colors.error, "destructive-foreground" to colors.onError, "destructive-surface" to colors.errorContainer,
        "warning" to Color(0xFFCA8A04), "warning-foreground" to colors.onSurface, "warning-surface" to colors.surfaceContainer,
        "success" to Color(0xFF16A34A), "success-foreground" to colors.onSurface,
        "info" to colors.tertiary, "info-foreground" to colors.onTertiary,
        "code-background" to colors.surfaceContainer, "code-foreground" to colors.onSurface,
        "chart-1" to colors.primary, "chart-2" to colors.tertiary, "chart-3" to Color(0xFF16A34A),
        "chart-4" to Color(0xFFCA8A04), "chart-5" to colors.error, "chart-6" to colors.secondary,
    )
    return buildJsonObject {
        put("appearance", if (colors.background.luminance() < 0.5f) "dark" else "light")
        put("variables", buildJsonObject {
            variables.forEach { (name, color) -> put("--$name", color.css()) }
            put("--radius", "0.625rem"); put("--font-sans", "system-ui, sans-serif"); put("--font-mono", "monospace")
        })
    }.toString()
}
