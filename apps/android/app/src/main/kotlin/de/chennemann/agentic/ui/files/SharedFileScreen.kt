package de.chennemann.agentic.ui.files

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.webkit.WebView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.chennemann.agentic.domain.attachments.DelimitedTable
import de.chennemann.agentic.domain.attachments.SharedFile
import de.chennemann.agentic.domain.attachments.SharedFileKind
import de.chennemann.agentic.domain.attachments.SharedText
import de.chennemann.agentic.domain.attachments.formatByteSize
import de.chennemann.agentic.icons.ExternalLink
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Share
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows any file a conversation shares, or hands it to another app when it cannot be shown here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedFileScreen(
    state: SharedFileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val file = state.file
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.source?.name ?: "File", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        file?.let {
                            Text(formatByteSize(it.sizeBytes), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                },
                actions = {
                    if (file != null) {
                        IconButton(onClick = { context.openSharedFileWith(file) }) {
                            Icon(Icons.ExternalLink, contentDescription = "Open with")
                        }
                        IconButton(onClick = { context.shareSharedFile(file) }) {
                            Icon(Icons.Share, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.errorMessage != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                file == null || state.content == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> SharedFileBody(file, state.content)
            }
        }
    }
}

@Composable
private fun SharedFileBody(file: SharedFile, content: SharedFileContent) {
    when (content) {
        SharedFileContent.Media -> when (file.kind) {
            SharedFileKind.Image -> ZoomableImage(file)
            SharedFileKind.Svg -> SvgView(file.file)
            SharedFileKind.Pdf -> PdfDocumentView(file)
            SharedFileKind.Audio -> {
                var active by remember { mutableStateOf(false) }
                SharedAudioPlayer(
                    file = file.file,
                    active = active,
                    onActiveChange = { active = it },
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
            SharedFileKind.Video -> VideoFileView(file)
            else -> UnsupportedFile(file, "This file can't be previewed here. Open it in another app or share it.")
        }
        is SharedFileContent.Text -> TextFile(file, content.text) {
            CodeView(file.source.name, content.text.contents, null, null)
        }
        is SharedFileContent.Markdown -> TextFile(file, content.text) {
            SelectionContainer {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    StreamingMarkdownText(content.text.contents, streaming = false)
                }
            }
        }
        is SharedFileContent.Table -> DelimitedTableView(content.table)
        is SharedFileContent.Unsupported -> UnsupportedFile(file, content.reason)
    }
}

@Composable
private fun TextFile(file: SharedFile, text: SharedText, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (text.truncated) {
            Text(
                "Showing the first 1 MiB of ${formatByteSize(text.byteLength.coerceAtLeast(file.sizeBytes))}.",
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        body()
    }
}

@Composable
private fun UnsupportedFile(file: SharedFile, reason: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(file.source.name, style = MaterialTheme.typography.titleMedium)
        Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { context.openSharedFileWith(file) }) { Text("Open with") }
            OutlinedButton(onClick = { context.shareSharedFile(file) }) { Text("Share") }
        }
    }
}

@Composable
private fun DelimitedTableView(table: DelimitedTable) {
    val columns = table.rows.maxOfOrNull { it.size } ?: 0
    Column(Modifier.fillMaxSize()) {
        if (table.truncated) {
            Text(
                "Table limited to the first 100 rows and 30 columns. Open it in another app for the rest.",
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SelectionContainer {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                table.rows.forEachIndexed { rowIndex, row ->
                    Row(if (rowIndex == 0) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier) {
                        (0 until columns).forEach { column ->
                            Text(
                                row.getOrElse(column) { "" },
                                modifier = Modifier
                                    .width(160.dp)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/** Pinch to zoom and pan with two fingers, or one finger while zoomed when [singleFingerPan]; double tap toggles. */
private fun Modifier.pinchToZoom(state: ZoomState, singleFingerPan: Boolean, panVertically: Boolean): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointers = event.changes.count { it.pressed }
                if (pointers >= 2 || (singleFingerPan && pointers == 1 && state.scale > 1f)) {
                    val scale = (state.scale * event.calculateZoom()).coerceIn(1f, 5f)
                    state.scale = scale
                    state.offset = clampOffset(state.offset + event.calculatePan(), size, scale, panVertically)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state) {
        detectTapGestures(
            onDoubleTap = {
                state.scale = if (state.scale > 1f) 1f else 2.5f
                state.offset = Offset.Zero
            },
        )
    }

private fun clampOffset(offset: Offset, size: IntSize, scale: Float, panVertically: Boolean): Offset {
    val maxX = size.width * (scale - 1) / 2
    val maxY = if (panVertically) size.height * (scale - 1) / 2 else 0f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private fun Modifier.zoomLayer(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}

private fun decodeImage(file: File, maxDimension: Int = 4096): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val largest = maxOf(info.size.width, info.size.height)
        if (largest > maxDimension) decoder.setTargetSampleSize((largest + maxDimension - 1) / maxDimension)
    }.asImageBitmap()
}.getOrNull()

private sealed interface Decoded<out T> {
    data object Loading : Decoded<Nothing>

    data object Failed : Decoded<Nothing>

    data class Ready<T>(val value: T) : Decoded<T>
}

@Composable
private fun ZoomableImage(file: SharedFile) {
    val image by produceState<Decoded<ImageBitmap>>(Decoded.Loading, file.file) {
        value = withContext(Dispatchers.IO) { decodeImage(file.file) }?.let { Decoded.Ready(it) } ?: Decoded.Failed
    }
    val zoom = remember(file.file) { ZoomState() }
    when (val current = image) {
        Decoded.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        Decoded.Failed -> UnsupportedFile(file, "This image format can't be shown on this device.")
        is Decoded.Ready -> Box(Modifier.fillMaxSize().clipToBounds().pinchToZoom(zoom, singleFingerPan = true, panVertically = true)) {
            Image(
                bitmap = current.value,
                contentDescription = file.source.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomLayer(zoom),
            )
        }
    }
}

@Composable
private fun SvgView(file: File) {
    val html by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            // An SVG drawn through <img> runs no scripts and loads nothing else.
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body style=\"margin:0;background:#fff;display:flex;align-items:center;justify-content:center;min-height:100vh\">" +
                "<img alt=\"\" style=\"max-width:100%;height:auto\" src=\"data:image/svg+xml;base64,$encoded\"></body></html>"
        }
    }
    val content = html ?: return Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
            }
        },
        update = { view ->
            if (view.tag != content) {
                view.tag = content
                view.loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private class PdfDocument(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (cause: Exception) {
        descriptor.close()
        throw cause
    }
    private var closed = false
    val pageAspects: List<Float> = (0 until renderer.pageCount).map { index ->
        renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    /** PdfRenderer renders one page at a time, so rendering and closing share the document's lock. */
    @Synchronized
    fun render(index: Int, width: Int): ImageBitmap? {
        if (closed) return null
        return renderer.openPage(index).use { page ->
            val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 8192)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.asImageBitmap()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        descriptor.close()
    }
}

@Composable
private fun PdfDocumentView(file: SharedFile) {
    val document by produceState<Decoded<PdfDocument>>(Decoded.Loading, file.file) {
        val opened = withContext(Dispatchers.IO) { runCatching { PdfDocument(file.file) }.getOrNull() }
        value = opened?.let { Decoded.Ready(it) } ?: Decoded.Failed
        awaitDispose { opened?.close() }
    }
    val current = when (val loaded = document) {
        Decoded.Loading -> return Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        Decoded.Failed -> return UnsupportedFile(file, "This PDF can't be opened here. It may be protected or damaged.")
        is Decoded.Ready -> loaded.value
    }
    val zoom = remember(file.file) { ZoomState() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pinchToZoom(zoom, singleFingerPan = false, panVertically = false),
    ) {
        // Rendered at twice the screen width so pages stay sharp when zoomed in.
        val renderWidth = (constraints.maxWidth * 2).coerceIn(1, 2400)
        LazyColumn(
            modifier = Modifier.fillMaxSize().zoomLayer(zoom),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(current.pageAspects.size) { index ->
                val page by produceState<ImageBitmap?>(null, current, index, renderWidth) {
                    value = withContext(Dispatchers.IO) { runCatching { current.render(index, renderWidth) }.getOrNull() }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(current.pageAspects[index].coerceIn(0.1f, 10f))
                        .background(Color.White),
                ) {
                    page?.let {
                        Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoFileView(file: SharedFile) {
    var failed by remember(file.file) { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) view?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (failed) return UnsupportedFile(file, "This video format can't be played on this device.")
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    setVideoPath(file.file.path)
                    view = this
                }
            },
            onRelease = {
                it.stopPlayback()
                view = null
            },
        )
    }
}
