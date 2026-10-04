package de.chennemann.agentic.streamingmarkdown

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import java.util.Base64

private const val MermaidOrigin = "https://diagram.agentic.invalid/"

/** A fitted preview with a separate viewer for zooming and panning. */
@Composable
internal fun MermaidBlock(source: String, onCopy: (String) -> Unit) {
    var showSource by remember(source) { mutableStateOf(false) }
    var expanded by remember(source) { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column {
        Row {
            TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "Diagram" else "Source") }
            TextButton(onClick = { onCopy(source) }) { Text("Copy diagram source") }
        }
        if (!showSource) {
            TextButton(onClick = { expanded = true }) { Text("Expand diagram") }
        }
        if (showSource) {
            Text(source)
        } else {
            DiagramView(source, dark, interactive = false, modifier = Modifier.fillMaxWidth().height(320.dp))
        }
    }
    if (expanded) {
        ExpandedDiagram(source, dark, onDismiss = { expanded = false })
    }
}

@Composable
private fun ExpandedDiagram(source: String, dark: Boolean, onDismiss: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TextButton(onClick = onDismiss) { Text("Close diagram") }
                Row {
                    TextButton(onClick = { webView?.evaluateJavascript("window.diagramZoomBy?.(1.25)", null) }, enabled = webView != null) { Text("Zoom in") }
                    TextButton(onClick = { webView?.evaluateJavascript("window.diagramZoomBy?.(0.8)", null) }, enabled = webView != null) { Text("Zoom out") }
                    TextButton(onClick = { webView?.evaluateJavascript("window.diagramFit?.()", null) }, enabled = webView != null) { Text("Fit") }
                }
                DiagramView(
                    source, dark, interactive = true,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onReady = { webView = it },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagramView(
    source: String,
    dark: Boolean,
    interactive: Boolean,
    modifier: Modifier,
    onReady: (WebView) -> Unit = {},
) {
    val document = remember(source, dark, interactive) { mermaidDocument(source, dark, interactive) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            object : WebView(context) {
                private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        if (width > 0 && height > 0) {
                            evaluateJavascript("window.diagramZoomBy?.(${detector.scaleFactor},${detector.focusX / width},${detector.focusY / height})", null)
                        }
                        return true
                    }
                })
                private val panDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent) = true
                    override fun onScroll(start: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                        if (!scaleDetector.isInProgress && current.pointerCount == 1 && width > 0 && height > 0) {
                            evaluateJavascript("window.diagramPanBy?.(${-distanceX / width},${-distanceY / height})", null)
                        }
                        return true
                    }
                })
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (!interactive) return false
                    scaleDetector.onTouchEvent(event)
                    panDetector.onTouchEvent(event)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
                override fun performClick(): Boolean = super.performClick()
            }.apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.useWideViewPort = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
            }
        },
        update = { view ->
            if (view.tag != document) {
                view.tag = document
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    override fun onPageFinished(view: WebView, url: String) {
                        onReady(view)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        return when (request.url.toString()) {
                            "${MermaidOrigin}index.html" -> WebResourceResponse(
                                "text/html", "UTF-8", ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)),
                            )
                            "${MermaidOrigin}mermaid.min.js" -> WebResourceResponse(
                                "application/javascript", "UTF-8", view.context.assets.open("mermaid/mermaid.min.js"),
                            )
                            else -> WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }
                    }
                }
                view.loadUrl("${MermaidOrigin}index.html")
            }
        },
        onRelease = { view -> view.stopLoading(); view.destroy() },
    )
}

internal fun mermaidDocument(source: String, dark: Boolean, interactive: Boolean = false): String {
    val encoded = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
    val theme = if (dark) "dark" else "default"
    val foreground = if (dark) "#eeeeee" else "#222222"
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://diagram.agentic.invalid; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; font-src 'none'; frame-src 'none'">
        <style>
        html,body{margin:0;width:100%;height:100%;overflow:hidden;color:$foreground;font-family:sans-serif}
        #diagram{box-sizing:border-box;padding:8px;width:100%;height:100%;display:flex;align-items:center;justify-content:center;overflow:hidden}
        #diagram svg{display:block;width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important}
        #diagram.error{display:block;overflow:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere}
        </style>
        <script src="mermaid.min.js"></script></head><body><div id="diagram" aria-label="Mermaid diagram">Rendering diagram…</div>
        <script>
        (async function(){
          const target=document.getElementById('diagram');
          const source=new TextDecoder().decode(Uint8Array.from(atob('$encoded'),c=>c.charCodeAt(0)));
          try {
            mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'$theme',maxTextSize:50000,maxEdges:500,suppressErrorRendering:true,
              secure:['secure','securityLevel','startOnLoad','maxTextSize','maxEdges','suppressErrorRendering']});
            const result=await mermaid.render('graph',source);
            target.innerHTML=result.svg;
            const svg=target.querySelector('svg');
            svg?.setAttribute('preserveAspectRatio','xMidYMid meet');
            if (svg && ${if (interactive) "true" else "false"}) {
              let scale=1,x=0,y=0;
              svg.style.transformOrigin='0 0';
              svg.style.touchAction='none';
              function apply(){
                x=Math.max(svg.clientWidth*(1-scale),Math.min(0,x));
                y=Math.max(svg.clientHeight*(1-scale),Math.min(0,y));
                svg.style.transform='translate('+x+'px,'+y+'px) scale('+scale+')';
              }
              function zoom(next,cx=svg.clientWidth/2,cy=svg.clientHeight/2){
                next=Math.max(1,Math.min(8,next));
                const ratio=next/scale;
                x=cx-(cx-x)*ratio;y=cy-(cy-y)*ratio;scale=next;apply();
              }
              window.diagramZoomBy=(factor,cx=0.5,cy=0.5)=>zoom(scale*factor,cx*svg.clientWidth,cy*svg.clientHeight);
              window.diagramPanBy=(dx,dy)=>{x+=dx*svg.clientWidth;y+=dy*svg.clientHeight;apply();};
              window.diagramFit=()=>{scale=1;x=0;y=0;apply();};
              new ResizeObserver(()=>apply()).observe(target);
              apply();
            }
          } catch(error) {
            target.classList.add('error');
            target.textContent='Unable to render this diagram. Source:';
            const pre=document.createElement('pre');pre.textContent=source;target.appendChild(pre);
          }
        })();
        </script></body></html>
    """.trimIndent()
}
