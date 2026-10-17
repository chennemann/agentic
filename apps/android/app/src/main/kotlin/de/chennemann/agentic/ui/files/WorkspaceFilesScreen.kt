package de.chennemann.agentic.ui.files

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.chennemann.agentic.domain.orchestration.WorkspaceFilePreview
import de.chennemann.agentic.icons.Check
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.FilterList
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Refresh
import de.chennemann.agentic.streamingmarkdown.CodeTokenKind
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFilesScreen(
    state: WorkspaceFilesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onClosePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cwd = state.listing?.cwd
    var query by rememberSaveable { mutableStateOf("") }
    var showBuildOutput by rememberSaveable(cwd) { mutableStateOf(false) }
    var expansion by rememberSaveable(cwd, stateSaver = FileTreeExpansionSaver) { mutableStateOf(FileTreeExpansion()) }
    var menuOpen by remember { mutableStateOf(false) }
    val tree = remember(state.listing) { FileTree.from(state.listing?.entries.orEmpty()) }
    val rows = remember(tree, expansion, showBuildOutput, query) { tree.rows(expansion, showBuildOutput, query) }
    val listState = rememberLazyListState()
    BackHandler(enabled = state.selectedPath != null, onBack = onClosePreview)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.selectedPath?.substringAfterLast('/') ?: "Files",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = state.selectedPath?.let { it.substringBeforeLast('/', "").ifEmpty { "/" } } ?: cwd
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (state.selectedPath == null) onBack else onClosePreview) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    if (state.selectedPath == null) {
                        IconButton(onClick = onRefresh, enabled = !state.loading) {
                            Icon(Icons.Refresh, contentDescription = "Refresh files")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.FilterList, contentDescription = "Tree options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Show build output") },
                                    leadingIcon = {
                                        if (showBuildOutput) Icon(Icons.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        showBuildOutput = !showBuildOutput
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Collapse all") },
                                    leadingIcon = { Spacer(Modifier.size(24.dp)) },
                                    onClick = {
                                        expansion = FileTreeExpansion(productionExpanded = false)
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Expand production code") },
                                    leadingIcon = { Spacer(Modifier.size(24.dp)) },
                                    onClick = {
                                        expansion = FileTreeExpansion()
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.selectedPath != null) {
                WorkspaceFilePreviewContent(state)
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search files") },
                singleLine = true,
            )
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (state.listing?.truncated == true) {
                Text("File list is truncated.", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (state.loading && state.listing == null) {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            } else if (rows.isEmpty() && state.errorMessage == null) {
                Text(if (query.isBlank()) "No files" else "No matching files", modifier = Modifier.padding(24.dp))
            } else {
                val colors = fileTreeColors()
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(rows, key = { it.key }) { row ->
                        FileTreeRowItem(
                            row = row,
                            colors = colors,
                            onClick = {
                                if (row.isDirectory) expansion = expansion.toggle(row.key) else onOpenFile(row.key)
                            },
                        )
                    }
                }
            }
        }
    }
}

private val FileTreeExpansionSaver = listSaver<FileTreeExpansion, Any>(
    save = { listOf(it.productionExpanded) + it.toggled },
    restore = { FileTreeExpansion(it.first() as Boolean, it.drop(1).map { key -> key as String }.toSet()) },
)

private data class FileTreeColors(val test: Color, val buildOutput: Color, val auxiliary: Color, val production: Color)

@Composable
private fun fileTreeColors(): FileTreeColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return FileTreeColors(
        test = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        buildOutput = if (dark) Color(0xFFE0A85A) else Color(0xFFA35F00),
        auxiliary = scheme.onSurfaceVariant,
        production = scheme.onSurface,
    )
}

@Composable
private fun FileTreeRowItem(row: FileTreeRow, colors: FileTreeColors, onClick: () -> Unit) {
    val color = when (row.category) {
        FileTreeCategory.Production -> colors.production
        FileTreeCategory.Test -> colors.test
        FileTreeCategory.Auxiliary -> colors.auxiliary
        FileTreeCategory.BuildOutput -> colors.buildOutput
    }
    val buildOutput = row.category == FileTreeCategory.BuildOutput
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (buildOutput) Modifier.background(colors.buildOutput.copy(alpha = 0.08f)) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(start = (8 + row.depth * 14).dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (row.isDirectory) {
            Icon(
                Icons.ChevronDown,
                contentDescription = if (row.expanded) "Collapse" else "Expand",
                modifier = Modifier.size(18.dp).rotate(if (row.expanded) 0f else -90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(
            row.label,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = if (row.isDirectory) FontWeight.Medium else FontWeight.Normal,
            fontStyle = if (buildOutput) FontStyle.Italic else FontStyle.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.outputRoot) {
            Text("build output", style = MaterialTheme.typography.labelSmall, color = colors.buildOutput)
        }
    }
}

@Composable
private fun WorkspaceFilePreviewContent(state: WorkspaceFilesUiState) {
    Column(Modifier.fillMaxSize()) {
        state.previewErrorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        if (state.previewLoading && state.preview == null) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        when (val preview = state.preview) {
            is WorkspaceFilePreview.Image -> {
                val bitmap = remember(preview.dataUrl) {
                    runCatching {
                        val bytes = Base64.decode(preview.dataUrl.substringAfter(','), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bitmap == null) {
                    Text("This image format is not supported by this device.", modifier = Modifier.padding(24.dp))
                } else {
                    Image(
                        bitmap = bitmap,
                        contentDescription = preview.path.substringAfterLast('/'),
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                }
            }
            is WorkspaceFilePreview.Text -> {
                if (preview.truncated) {
                    Text(
                        "Showing the first 1 MiB of ${preview.byteLength} bytes.",
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (preview.markdown) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        StreamingMarkdownText(preview.contents)
                    }
                } else {
                    CodeView(preview.path, preview.contents)
                }
            }
            is WorkspaceFilePreview.Unsupported -> Text(preview.reason, modifier = Modifier.padding(24.dp))
            null -> Unit
        }
    }
}

/** Line-numbered, syntax-highlighted source with a fixed gutter and shared horizontal scrolling. */
@Composable
private fun CodeView(path: String, contents: String) {
    val scheme = MaterialTheme.colorScheme
    val lines by produceState<List<AnnotatedString>?>(null, path, contents, scheme) {
        val keyword = SpanStyle(color = scheme.primary, fontWeight = FontWeight.Medium)
        val string = SpanStyle(color = scheme.tertiary)
        val number = SpanStyle(color = scheme.secondary)
        val comment = SpanStyle(color = scheme.onSurfaceVariant.copy(alpha = 0.7f), fontStyle = FontStyle.Italic)
        value = withContext(Dispatchers.Default) {
            highlightCodeLines(path, contents).map { tokens ->
                buildAnnotatedString {
                    tokens.forEach { token ->
                        when (token.kind) {
                            CodeTokenKind.PLAIN -> append(token.value)
                            CodeTokenKind.KEYWORD -> withStyle(keyword) { append(token.value) }
                            CodeTokenKind.STRING -> withStyle(string) { append(token.value) }
                            CodeTokenKind.NUMBER -> withStyle(number) { append(token.value) }
                            CodeTokenKind.COMMENT -> withStyle(comment) { append(token.value) }
                        }
                    }
                }
            }
        }
    }
    val current = lines ?: run {
        Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        return
    }
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
    val measurer = rememberTextMeasurer()
    val charWidth = remember(textStyle, measurer) { measurer.measure("0".repeat(64), textStyle).size.width / 64f }
    val density = LocalDensity.current
    val maxChars = remember(current) { current.maxOfOrNull { it.length } ?: 0 }
    val gutterWidth = with(density) { (current.size.toString().length * charWidth).toDp() } + 20.dp
    val endPadding = with(density) { 16.dp.toPx() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var offset by remember(path) { mutableFloatStateOf(0f) }
    val maxOffset = (maxChars * charWidth + endPadding - (viewportWidth - with(density) { gutterWidth.toPx() })).coerceAtLeast(0f)
    val horizontal = rememberScrollableState { delta ->
        val previous = offset
        offset = (offset - delta).coerceIn(0f, maxOffset)
        previous - offset
    }
    SelectionContainer {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportWidth = it.width }
                .scrollable(horizontal, Orientation.Horizontal),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(current.size) { index ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        (index + 1).toString(),
                        modifier = Modifier.width(gutterWidth).padding(end = 12.dp),
                        style = textStyle,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f).clipToBounds()) {
                        Text(
                            current[index],
                            modifier = Modifier
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .graphicsLayer { translationX = -offset },
                            style = textStyle,
                            color = scheme.onSurface,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
