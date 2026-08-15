package de.chennemann.agentic.ui.files

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.domain.orchestration.WorkspaceFilePreview
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Refresh
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText

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
    var query by remember { mutableStateOf("") }
    var collapsed by remember(state.listing?.cwd) { mutableStateOf(emptySet<String>()) }
    val entries = state.listing?.entries.orEmpty().filter { entry ->
        val matches = query.isBlank() || entry.path.contains(query, ignoreCase = true)
        val hidden = collapsed.any { parent -> entry.path.startsWith("$parent/") }
        matches && (!hidden || query.isNotBlank())
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.selectedPath?.substringAfterLast('/') ?: "Files")
                        state.listing?.cwd?.let {
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
            } else if (entries.isEmpty() && state.errorMessage == null) {
                Text(if (query.isBlank()) "No files" else "No matching files", modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        val depth = entry.path.count { it == '/' }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (entry.isDirectory) {
                                    collapsed = if (entry.path in collapsed) collapsed - entry.path else collapsed + entry.path
                                } else {
                                    onOpenFile(entry.path)
                                }
                            }.padding(start = (16 + depth * 16).dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(if (entry.isDirectory) if (entry.path in collapsed) "▸" else "▾" else "·")
                            Text(entry.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (preview.markdown) {
                        StreamingMarkdownText(preview.contents)
                    } else {
                        Text(preview.contents, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            is WorkspaceFilePreview.Unsupported -> Text(preview.reason, modifier = Modifier.padding(24.dp))
            null -> Unit
        }
    }
}
