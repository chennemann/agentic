package de.chennemann.agentic.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.chennemann.agentic.data.attachments.AnnotationColor
import de.chennemann.agentic.data.attachments.drawAnnotations
import de.chennemann.agentic.domain.attachments.AnnotationPoint
import de.chennemann.agentic.domain.attachments.ImageAnnotation
import de.chennemann.agentic.domain.attachments.MaxImageAnnotations
import de.chennemann.agentic.domain.attachments.annotationLabel
import de.chennemann.agentic.domain.attachments.annotationRect
import de.chennemann.agentic.domain.attachments.moved
import de.chennemann.agentic.domain.attachments.newImageAnnotation
import de.chennemann.agentic.domain.attachments.resized
import de.chennemann.agentic.domain.attachments.tapAnnotationBounds
import de.chennemann.agentic.domain.attachments.withBounds
import de.chennemann.agentic.icons.Close
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.MessageCircle
import de.chennemann.agentic.icons.Trash
import de.chennemann.agentic.icons.Undo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

enum class ImageViewerMode { ZOOM, ANNOTATE }

private val MarkColor = Color(AnnotationColor)
private val ViewerMuted = Color(0xFFB8B8B8)
const val ImageViewerTestTag = "image-viewer"
const val ImageViewerStageTestTag = "image-viewer-stage"

/**
 * Full-screen image preview. Zoom mode pans and pinches; annotate mode places numbered ellipses
 * with comments. The leading [readOnlyCount] marks are already flattened into the pixels: they
 * are listed but neither drawn again nor editable, and new marks continue their numbering.
 * Closing (button, back, or system dismiss) reports the full mark list.
 */
@Composable
fun ImageViewerDialog(
    dataUrl: String?,
    name: String,
    initialAnnotations: List<ImageAnnotation>,
    readOnlyCount: Int,
    initialMode: ImageViewerMode,
    annotationHint: String?,
    onClose: (List<ImageAnnotation>) -> Unit,
) {
    var marks by remember { mutableStateOf(initialAnnotations) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf(emptyList<List<ImageAnnotation>>()) }
    var mode by remember { mutableStateOf(initialMode) }
    val close = { onClose(marks) }
    val decoded by produceState<DecodedImage>(DecodedImage.Loading, dataUrl) {
        value = if (dataUrl == null) {
            DecodedImage.Loading
        } else {
            withContext(Dispatchers.Default) { decodeDataUrlBitmap(dataUrl, MaxPreviewDimension) }
                ?.let { DecodedImage.Ready(it.asImageBitmap()) }
                ?: DecodedImage.Failed
        }
    }
    val bitmap = (decoded as? DecodedImage.Ready)?.image
    val record = { before: List<ImageAnnotation> -> undo = (undo + listOf(before)).takeLast(50) }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = close)
        Surface(
            modifier = Modifier.fillMaxSize().testTag(ImageViewerTestTag),
            color = Color.Black,
            contentColor = Color.White,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) { Icon(Icons.Close, contentDescription = "Close image") }
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (mode == ImageViewerMode.ANNOTATE) {
                        IconButton(
                            onClick = {
                                marks = undo.last()
                                undo = undo.dropLast(1)
                                if (marks.none { it.id == selectedId }) selectedId = null
                            },
                            enabled = undo.isNotEmpty(),
                        ) { Icon(Icons.Undo, contentDescription = "Undo", modifier = Modifier.size(20.dp)) }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.padding(end = 8.dp)) {
                        ImageViewerMode.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = mode == entry,
                                onClick = { mode = entry },
                                enabled = bitmap != null,
                                shape = SegmentedButtonDefaults.itemShape(index, ImageViewerMode.entries.size),
                                icon = {},
                                colors = SegmentedButtonDefaults.colors(
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = Color.White,
                                ),
                                label = { Text(if (entry == ImageViewerMode.ZOOM) "Zoom" else "Annotate", maxLines = 1) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val state = decoded) {
                        DecodedImage.Loading -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                        DecodedImage.Failed -> Text("The image could not be shown.", color = ViewerMuted)
                        is DecodedImage.Ready -> if (mode == ImageViewerMode.ZOOM) {
                            ZoomStage(state.image, marks.drop(readOnlyCount), readOnlyCount)
                        } else {
                            AnnotateStage(
                                image = state.image,
                                marks = marks,
                                readOnlyCount = readOnlyCount,
                                selectedId = selectedId,
                                onSelect = { selectedId = it },
                                onPreview = { marks = it },
                                onCommit = { before, after ->
                                    marks = after
                                    if (before != after) record(before)
                                },
                            )
                        }
                    }
                }
                if (mode == ImageViewerMode.ANNOTATE) {
                    AnnotationComments(
                        marks = marks,
                        readOnlyCount = readOnlyCount,
                        selectedId = selectedId,
                        hint = annotationHint,
                        onSelect = { selectedId = it },
                        onCommentChange = { id, comment -> marks = marks.map { if (it.id == id) it.copy(comment = comment) else it } },
                        onDelete = { id ->
                            record(marks)
                            marks = marks.filterNot { it.id == id }
                            if (selectedId == id) selectedId = null
                        },
                    )
                }
            }
        }
    }
}

/** Display size of a [bitmapWidth] x [bitmapHeight] image fitted into the stage. */
@Composable
private fun FittedStage(
    image: ImageBitmap,
    content: @Composable (width: Float, height: Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val scale = minOf(maxW / image.width, maxH / image.height)
        val width = image.width * scale
        val height = image.height * scale
        Box(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {
            content(width, height)
        }
    }
}

@Composable
private fun ZoomStage(image: ImageBitmap, marks: List<ImageAnnotation>, numberOffset: Int) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag(ImageViewerStageTestTag)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    if (scale == 1f) offset = Offset.Zero
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            FittedStage(image) { _, _ ->
                Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                AnnotationOverlay(marks, numberOffset, selectedId = null)
            }
        }
    }
}

@Composable
private fun AnnotationOverlay(marks: List<ImageAnnotation>, numberOffset: Int, selectedId: String?) {
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { drawAnnotations(it.nativeCanvas, size.width, size.height, marks, numberOffset) }
        marks.firstOrNull { it.id == selectedId }?.let { mark ->
            val corner = Offset((mark.x + mark.width) * size.width, (mark.y + mark.height) * size.height)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = corner)
            drawCircle(MarkColor, radius = 9.dp.toPx(), center = corner, style = Stroke(3.dp.toPx()))
        }
    }
}

private sealed interface MarkTarget {
    val id: String

    data class Move(override val id: String) : MarkTarget
    data class Resize(override val id: String) : MarkTarget
}

@Composable
private fun AnnotateStage(
    image: ImageBitmap,
    marks: List<ImageAnnotation>,
    readOnlyCount: Int,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onPreview: (List<ImageAnnotation>) -> Unit,
    onCommit: (before: List<ImageAnnotation>, after: List<ImageAnnotation>) -> Unit,
) {
    val currentMarks by rememberUpdatedState(marks)
    val currentSelected by rememberUpdatedState(selectedId)
    FittedStage(image) { width, height ->
        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        AnnotationOverlay(marks.drop(readOnlyCount), readOnlyCount, selectedId)
        val cornerSlop = with(LocalDensity.current) { 28.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .testTag(ImageViewerStageTestTag)
                .semantics { contentDescription = "Annotation canvas" }
                .pointerInput(width, height, readOnlyCount) {
                    fun normalized(position: Offset) = AnnotationPoint(
                        (position.x / width).coerceIn(0f, 1f),
                        (position.y / height).coerceIn(0f, 1f),
                    )
                    fun hit(position: Offset): MarkTarget? {
                        val editable = currentMarks.drop(readOnlyCount).asReversed()
                        editable.firstOrNull { mark ->
                            val label = annotationLabel(mark, width, height)
                            hypot(position.x - label.x, position.y - label.y) <= label.radius * 1.5f
                        }?.let { return MarkTarget.Move(it.id) }
                        editable.firstOrNull { mark ->
                            hypot(position.x - (mark.x + mark.width) * width, position.y - (mark.y + mark.height) * height) <= cornerSlop
                        }?.let { return MarkTarget.Resize(it.id) }
                        val point = normalized(position)
                        return editable.firstOrNull { mark ->
                            val dx = (point.x - (mark.x + mark.width / 2)) / (mark.width / 2)
                            val dy = (point.y - (mark.y + mark.height / 2)) / (mark.height / 2)
                            dx * dx + dy * dy <= 1f
                        }?.let { MarkTarget.Move(it.id) }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val before = currentMarks
                        val start = normalized(down.position)
                        val target = hit(down.position)
                        val canAdd = before.size < MaxImageAnnotations
                        var drawn: ImageAnnotation? = null
                        var dragged = false
                        var latest = before
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!dragged && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                            if (!dragged) continue
                            change.consume()
                            val point = normalized(change.position)
                            latest = when (target) {
                                is MarkTarget.Move -> before.map {
                                    if (it.id == target.id) it.moved(AnnotationPoint(point.x - start.x, point.y - start.y)) else it
                                }
                                is MarkTarget.Resize -> before.map { if (it.id == target.id) it.resized(point) else it }
                                null -> if (!canAdd) {
                                    before
                                } else {
                                    val mark = (drawn ?: newImageAnnotation(annotationRect(start, point)))
                                        .withBounds(annotationRect(start, point))
                                    drawn = mark
                                    before + mark
                                }
                            }
                            onPreview(latest)
                        }
                        when {
                            !dragged && target != null -> onSelect(target.id)
                            !dragged && canAdd -> {
                                val mark = newImageAnnotation(tapAnnotationBounds(start))
                                onCommit(before, before + mark)
                                onSelect(mark.id)
                            }
                            !dragged -> onSelect(null)
                            else -> {
                                // The whole drag is one undo step.
                                onCommit(before, latest)
                                drawn?.let { onSelect(it.id) } ?: target?.let { onSelect(it.id) }
                            }
                        }
                        if (currentSelected != null && currentMarks.none { it.id == currentSelected }) onSelect(null)
                    }
                },
        )
    }
}

@Composable
private fun AnnotationComments(
    marks: List<ImageAnnotation>,
    readOnlyCount: Int,
    selectedId: String?,
    hint: String?,
    onSelect: (String) -> Unit,
    onCommentChange: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(selectedId) {
        val index = marks.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            if (index >= readOnlyCount) runCatching { focusRequesters[selectedId]?.requestFocus() }
        }
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF101010))) {
        if (marks.size == readOnlyCount) {
            Text(
                "Tap or drag on the image to circle something, then describe the change.",
                style = MaterialTheme.typography.bodySmall,
                color = ViewerMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            itemsIndexed(marks, key = { _, mark -> mark.id }) { index, mark ->
                val editable = index >= readOnlyCount
                val selected = mark.id == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) Color(0xFF202020) else Color.Transparent)
                        .clickable(enabled = editable) { onSelect(mark.id) }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(22.dp).background(if (editable) MarkColor else MarkColor.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    if (editable) {
                        val requester = remember { FocusRequester() }
                        DisposableEffect(mark.id) {
                            focusRequesters[mark.id] = requester
                            onDispose { focusRequesters.remove(mark.id) }
                        }
                        TextField(
                            value = mark.comment,
                            onValueChange = { onCommentChange(mark.id, it.take(8_000)) },
                            placeholder = { Text("Describe the change") },
                            modifier = Modifier.weight(1f).focusRequester(requester),
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedPlaceholderColor = ViewerMuted,
                                unfocusedPlaceholderColor = ViewerMuted,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MarkColor,
                            ),
                        )
                        IconButton(onClick = { onDelete(mark.id) }) {
                            Icon(Icons.Trash, contentDescription = "Delete annotation ${index + 1}", modifier = Modifier.size(18.dp), tint = ViewerMuted)
                        }
                    } else {
                        Text(
                            mark.comment.ifBlank { "No comment" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = ViewerMuted,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
        hint?.takeIf { marks.size > readOnlyCount }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = ViewerMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Compact badge for a thumbnail that carries annotations. */
@Composable
fun AnnotationCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(topStart = 6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics { contentDescription = "$count image annotations" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.MessageCircle, contentDescription = null, modifier = Modifier.size(11.dp), tint = Color.White)
        Text("$count", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private const val MaxPreviewDimension = 2560

private sealed interface DecodedImage {
    data object Loading : DecodedImage
    data object Failed : DecodedImage
    data class Ready(val image: ImageBitmap) : DecodedImage
}

/** Decodes a data URL, downsampling large images so they stay within [maxDimension]. */
fun decodeDataUrlBitmap(dataUrl: String, maxDimension: Int): Bitmap? = runCatching {
    val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
