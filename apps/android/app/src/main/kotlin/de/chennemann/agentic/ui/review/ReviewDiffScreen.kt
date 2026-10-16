package de.chennemann.agentic.ui.review

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import expo.modules.t3reviewdiff.T3ReviewDiffView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewDiffScreen(state: ReviewDiffUiState, onBack: () -> Unit, onRefresh: () -> Unit, onSelect: (String) -> Unit) {
    var sectionMenu by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable(state.selectedId) { mutableStateOf(emptyList<String>()) }
    var viewed by rememberSaveable(state.selectedId) { mutableStateOf(emptyList<String>()) }
    var nativeView by remember { mutableStateOf<T3ReviewDiffView?>(null) }
    val binding = remember { ReviewDiffBinding() }
    val appearance = rememberReviewAppearance(wrap)
    var payload by remember(state.document, appearance.tokenColors) { mutableStateOf<ReviewPayload?>(null) }
    LaunchedEffect(state.document, appearance.tokenColors) {
        payload = withContext(Dispatchers.Default) { reviewPayload(state.document, appearance.tokenColors) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Changes")
                        Text(
                            state.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("review-diff-page")) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    TextButton(onClick = {
                        sectionMenu = true
                    }, enabled = state.sections.isNotEmpty() && !state.loading) {
                        Text(state.sections.firstOrNull { it.id == state.selectedId }?.label ?: "Latest turn")
                    }
                    DropdownMenu(expanded = sectionMenu, onDismissRequest = {
                        sectionMenu = false
                    }, modifier = Modifier.heightIn(max = 360.dp)) {
                        state.sections.forEach { section ->
                            DropdownMenuItem(text = { Text(section.label) }, onClick = {
                                sectionMenu = false
                                onSelect(section.id)
                            })
                        }
                    }
                }
                Box {
                    TextButton(onClick = {
                        fileMenu = true
                    }, enabled = state.document.files.isNotEmpty()) { Text("Files (${state.document.files.size})") }
                    DropdownMenu(expanded = fileMenu, onDismissRequest = {
                        fileMenu = false
                    }, modifier = Modifier.heightIn(max = 360.dp)) {
                        state.document.files.forEach { file ->
                            DropdownMenuItem(text = {
                                Text(file.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }, onClick = {
                                fileMenu = false
                                nativeView?.scrollToFile(file.id, true)
                            })
                        }
                    }
                }
                FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text("Wrap") })
                Text(
                    "+${state.document.additions}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "\u2212${state.document.deletions}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            state.document.notice?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            when {
                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRefresh) { Text("Try again") }
                    }
                }
                state.document.files.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.sections.isEmpty()) "No completed turn changes yet." else "No changes in this range."
                    )
                }
                else -> payload?.let { ready ->
                    AndroidView(
                        factory = { context -> T3ReviewDiffView(context).also { nativeView = it } },
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("review-diff-surface"),
                        onRelease = { view ->
                            view.cleanup()
                            binding.release()
                            if (nativeView ===
                                view
                            ) {
                                nativeView = null
                            }
                        },
                        update = { view ->
                            view.onToggleFile = { event ->
                                (event["fileId"] as? String)?.let { id ->
                                    collapsed =
                                        if (id in collapsed) collapsed - id else collapsed + id
                                }
                            }
                            view.onToggleViewedFile = { event ->
                                (event["fileId"] as? String)?.let { id ->
                                    viewed =
                                        if (id in viewed) viewed - id else viewed + id
                                }
                            }
                            binding.bind(
                                view,
                                "${state.selectedId}:${state.revision}",
                                appearance,
                                ready,
                                collapsed,
                                viewed
                            )
                        }
                    )
                }
            }
        }
    }
}
