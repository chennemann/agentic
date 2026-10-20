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
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.positionChanged
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
import de.chennemann.agentic.icons.Pencil
import de.chennemann.agentic.icons.Trash
import de.chennemann.agentic.icons.Undo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val MarkColor = Color(AnnotationColor)
private val ViewerMuted = Color(0xFFB8B8B8)
const val ImageViewerTestTag = "image-viewer"
const val ImageViewerStageTestTag = "image-viewer-stage"

/**
 * One picture in the viewer. The leading [readOnlyCount] marks are already flattened into the
 * pixels: they are listed but neither drawn again nor editable, and new marks continue their
 * numbering.
 */
data class ViewerImage(
    val key: String,
    val dataUrl: String?,
    val name: String,
    val annotations: List<ImageAnnotation> = emptyList(),
    val readOnlyCount: Int = 0,
    val annotationHint: String? = null,
    val loadFailed: Boolean = false,
)

/**
 * Full-screen gallery over [images], starting at [initialIndex]. Swiping moves between images;
 * double-tap and pinch zoom. The overlay Annotate button switches the current image to placing
 * numbered ellipses with comments, where one finger draws and two fingers still zoom. Closing
 * (button, back, or system dismiss) reports every image's marks by [ViewerImage.key].
 */
@Composable
fun ImageViewerDialog(
    images: List<ViewerImage>,
    initialIndex: Int,
    onClose: (Map<String, List<ImageAnnotation>>) -> Unit,
    onImageRequested: (ViewerImage) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var marksByKey by remember { mutableStateOf(images.associate { it.key to it.annotations }) }
    var undoByKey by remember { mutableStateOf(emptyMap<String, List<List<ImageAnnotation>>>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var annotating by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    val current = images.getOrNull(pager.currentPage) ?: return
    val marks = marksByKey[current.key].orEmpty()
    val undo = undoByKey[current.key].orEmpty()
    val setMarks = { value: List<ImageAnnotation> -> marksByKey = marksByKey + (current.key to value) }
    val record = { before: List<ImageAnnotation> -> undoByKey = undoByKey + (current.key to (undo + listOf(before)).takeLast(50)) }
    val close = { onClose(marksByKey) }
    LaunchedEffect(pager.currentPage) {
        selectedId = null
        zoomed = false
        onImageRequested(current)
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler { if (annotating) annotating = false else close() }
        // Pulling an unzoomed image down drags it away; far or fast enough closes the viewer.
        var dismissDrag by remember { mutableFloatStateOf(0f) }
        val dismissDistance = with(LocalDensity.current) { DismissDistance.toPx() }
        val dismissProgress = (dismissDrag / (dismissDistance * 2)).coerceIn(0f, 1f)
        Surface(
            modifier = Modifier.fillMaxSize().testTag(ImageViewerTestTag),
            color = Color.Black.copy(alpha = 1f - dismissProgress * 0.7f),
            contentColor = Color.White,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) { Icon(Icons.Close, contentDescription = "Close image") }
                    Column(Modifier.weight(1f)) {
                        Text(current.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (images.size > 1) {
                            Text("${pager.currentPage + 1} of ${images.size}", style = MaterialTheme.typography.labelSmall, color = ViewerMuted)
                        }
                    }
                    if (annotating) {
                        IconButton(
                            onClick = {
                                setMarks(undo.last())
                                undoByKey = undoByKey + (current.key to undo.dropLast(1))
                                if (undo.last().none { it.id == selectedId }) selectedId = null
                            },
                            enabled = undo.isNotEmpty(),
                        ) { Icon(Icons.Undo, contentDescription = "Undo", modifier = Modifier.size(20.dp)) }
                        TextButton(onClick = { annotating = false }) { Text("Done", color = MarkColor) }
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .draggable(
                            state = rememberDraggableState { delta -> dismissDrag = (dismissDrag + delta).coerceAtLeast(0f) },
                            orientation = Orientation.Vertical,
                            enabled = !annotating && !zoomed,
                            onDragStopped = { velocity ->
                                if (dismissDrag > dismissDistance || velocity > DismissVelocity) {
                                    close()
                                } else {
                                    animate(dismissDrag, 0f) { value, _ -> dismissDrag = value }
                                }
                            },
                        )
                        .graphicsLayer {
                            translationY = dismissDrag
                            val shrink = 1f - dismissProgress * 0.15f
                            scaleX = shrink
                            scaleY = shrink
                        },
                ) {
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = !annotating && !zoomed,
                        key = { images[it].key },
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val image = images[page]
                        val isCurrent = page == pager.currentPage
                        ImagePage(
                            image = image,
                            active = isCurrent,
                            marks = marksByKey[image.key].orEmpty(),
                            annotating = annotating && isCurrent,
                            selectedId = selectedId.takeIf { isCurrent },
                            onSelect = { selectedId = it },
                            onPreview = setMarks,
                            onCommit = { before, after ->
                                setMarks(after)
                                if (before != after) record(before)
                            },
                            onZoomedChange = { if (isCurrent) zoomed = it },
                            onRetry = { onImageRequested(image) },
                        )
                    }
                    if (!annotating) {
                        Surface(
                            onClick = { annotating = true },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.7f),
                            contentColor = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp)
                                .semantics { contentDescription = "Annotate ${current.name}" },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Pencil, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Annotate", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                if (images.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { scope.launch { pager.scrollToPage((pager.currentPage - 1 + images.size) % images.size) } }) {
                            Text("Previous", color = Color.White)
                        }
                        TextButton(onClick = { scope.launch { pager.scrollToPage((pager.currentPage + 1) % images.size) } }) {
                            Text("Next", color = Color.White)
                        }
                    }
                }
                if (annotating) {
                    AnnotationComments(
                        marks = marks,
                        readOnlyCount = current.readOnlyCount,
                        selectedId = selectedId,
                        hint = current.annotationHint,
                        onSelect = { selectedId = it },
                        onCommentChange = { id, comment -> setMarks(marks.map { if (it.id == id) it.copy(comment = comment) else it }) },
                        onDelete = { id ->
                            record(marks)
                            setMarks(marks.filterNot { it.id == id })
                            if (selectedId == id) selectedId = null
                        },
                    )
                }
            }
        }
    }
}

/** One gallery page: decodes its image and zooms it, and in annotation mode draws marks on it. */
@Composable
private fun ImagePage(
    image: ViewerImage,
    active: Boolean,
    marks: List<ImageAnnotation>,
    annotating: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onPreview: (List<ImageAnnotation>) -> Unit,
    onCommit: (before: List<ImageAnnotation>, after: List<ImageAnnotation>) -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    if (image.loadFailed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onRetry) { Text("Retry image", color = Color.White) }
        }
        return
    }
    val decoded by produceState<DecodedImage>(DecodedImage.Loading, image.dataUrl) {
        value = image.dataUrl?.let { dataUrl ->
            withContext(Dispatchers.Default) { decodeDataUrlBitmap(dataUrl, MaxPreviewDimension) }
                ?.let { DecodedImage.Ready(it.asImageBitmap()) }
                ?: DecodedImage.Failed
        } ?: DecodedImage.Loading
    }
    when (val state = decoded) {
        DecodedImage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }
        DecodedImage.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("The image could not be shown.", color = ViewerMuted)
        }
        is DecodedImage.Ready -> ZoomableStage(active, annotating, onZoomedChange) {
            FittedStage(state.image) { width, height ->
                Image(state.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                AnnotationOverlay(marks.drop(image.readOnlyCount), image.readOnlyCount, selectedId.takeIf { annotating })
                if (annotating) {
                    AnnotationInput(width, height, marks, image.readOnlyCount, selectedId, onSelect, onPreview, onCommit)
                }
            }
        }
    }
}

/**
 * Pinch and double-tap zoom with panning. Viewing pans with one finger only while zoomed, so an
 * unzoomed swipe still turns the page; annotating leaves one finger to drawing and zooms with two.
 */
@Composable
private fun ZoomableStage(
    active: Boolean,
    annotating: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    val zoomed = scale > 1f
    LaunchedEffect(zoomed) { onZoomedChange(zoomed) }
    val currentAnnotating by rememberUpdatedState(annotating)
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag(ImageViewerStageTestTag)
            .then(
                if (annotating) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
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
                },
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        val handles = fingers > 1 || (!currentAnnotating && scale > 1f)
                        if (handles && event.changes.none { it.isConsumed }) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            val pan = event.calculatePan()
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = if (scale == 1f) {
                                Offset.Zero
                            } else {
                                Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
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
        ) { content() }
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

/**
 * Places, moves, and resizes marks on a [width] x [height] fitted image with one finger. It sits
 * inside the zoomed content, so positions are already image-local. A second finger hands the
 * gesture to zooming and drops the stroke in progress.
 */
@Composable
private fun AnnotationInput(
    width: Float,
    height: Float,
    marks: List<ImageAnnotation>,
    readOnlyCount: Int,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onPreview: (List<ImageAnnotation>) -> Unit,
    onCommit: (before: List<ImageAnnotation>, after: List<ImageAnnotation>) -> Unit,
) {
    val currentMarks by rememberUpdatedState(marks)
    val currentSelected by rememberUpdatedState(selectedId)
    val cornerSlop = with(LocalDensity.current) { 28.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
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
                            if (event.changes.count { it.pressed } > 1) {
                                if (dragged) onPreview(before)
                                while (awaitPointerEvent().changes.any { it.pressed }) Unit
                                return@awaitEachGesture
                            }
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
private val DismissDistance = 120.dp
private const val DismissVelocity = 2_000f

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
