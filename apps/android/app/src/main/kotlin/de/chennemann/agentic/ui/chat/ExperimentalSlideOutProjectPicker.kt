package de.chennemann.agentic.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.ArchiveRestore
import de.chennemann.agentic.icons.Check
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Star
import de.chennemann.agentic.icons.StarOutline

/**
 * PROTOTYPE: three slide-out project-picker layouts hosted on the real chat screen.
 * Favorites and expanded-project state deliberately remain in memory.
 */
@Composable
fun ExperimentalSlideOutProjectPicker(
    visible: Boolean,
    state: ThreadPickerUiState,
    onDismiss: () -> Unit,
    onProjectExpanded: (String) -> Unit,
    onThreadSelected: (String) -> Unit,
    onNewThread: () -> Unit,
    onSettleThread: (String) -> Unit,
    onUnsettleThread: (String) -> Unit,
) {
    var variant by remember { mutableStateOf(ProjectPickerPrototypeVariant.ACCORDION) }
    var expandedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var favorites by rememberSaveable { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val projects = state.projects
        .filter { project ->
            search.isEmpty() || project.label.contains(search, true) ||
                project.supportingText.orEmpty().contains(search, true) ||
                (project.id == expandedProjectId && state.allThreads().any { it.matches(search) })
        }
        .sortedWith(
            compareByDescending<ProjectPickerItemUi> { it.id in favorites }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )

    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.56f)).clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it }),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(top = 12.dp)) {
                Header(variant, onDismiss)
                ProjectList(
                    modifier = Modifier.weight(1f),
                    projects = projects,
                    state = state,
                    variant = variant,
                    expandedProjectId = expandedProjectId,
                    favorites = favorites,
                    search = search,
                    onToggleExpanded = { projectId ->
                        expandedProjectId = if (expandedProjectId == projectId) null else projectId
                        if (expandedProjectId != null) onProjectExpanded(projectId)
                    },
                    onToggleFavorite = { projectId ->
                        favorites = if (projectId in favorites) favorites - projectId else favorites + projectId
                    },
                    onThreadSelected = onThreadSelected,
                    onNewThread = onNewThread,
                    onSettleThread = onSettleThread,
                    onUnsettleThread = onUnsettleThread,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Search projects and threads") },
                    singleLine = true,
                )
                VariantSwitcher(variant) { variant = it }
            }
        }
    }
}

@Composable
private fun Header(variant: ProjectPickerPrototypeVariant, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Projects", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Prototype · ${variant.label}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun ProjectList(
    modifier: Modifier,
    projects: List<ProjectPickerItemUi>,
    state: ThreadPickerUiState,
    variant: ProjectPickerPrototypeVariant,
    expandedProjectId: String?,
    favorites: Set<String>,
    search: String,
    onToggleExpanded: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onThreadSelected: (String) -> Unit,
    onNewThread: () -> Unit,
    onSettleThread: (String) -> Unit,
    onUnsettleThread: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (variant == ProjectPickerPrototypeVariant.CARDS) 10.dp else 2.dp),
    ) {
        if (projects.isEmpty()) {
            item("empty") {
                Text(
                    if (search.isEmpty()) "No projects available" else "No matching projects or loaded threads",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(projects, key = { it.id }) { project ->
            val expanded = project.id == expandedProjectId
            ProjectRow(
                project = project,
                variant = variant,
                expanded = expanded,
                favorite = project.id in favorites,
                onToggleExpanded = { onToggleExpanded(project.id) },
                onToggleFavorite = { onToggleFavorite(project.id) },
            )
            if (expanded) {
                Threads(
                    state = state,
                    search = search,
                    onThreadSelected = onThreadSelected,
                    onNewThread = onNewThread,
                    onSettleThread = onSettleThread,
                    onUnsettleThread = onUnsettleThread,
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectPickerItemUi,
    variant: ProjectPickerPrototypeVariant,
    expanded: Boolean,
    favorite: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val color = when (variant) {
        ProjectPickerPrototypeVariant.ACCORDION -> Color.Transparent
        ProjectPickerPrototypeVariant.CARDS -> MaterialTheme.colorScheme.surfaceVariant
        ProjectPickerPrototypeVariant.FAVORITES_FIRST -> {
            if (favorite) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        }
    }
    Surface(onClick = onToggleExpanded, color = color, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.ChevronUp else Icons.ChevronDown,
                contentDescription = if (expanded) "Collapse ${project.label}" else "Expand ${project.label}",
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(project.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                project.supportingText?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Star else Icons.StarOutline,
                    contentDescription = if (favorite) "Remove ${project.label} from favorites" else "Favorite ${project.label}",
                )
            }
        }
    }
}

@Composable
private fun Threads(
    state: ThreadPickerUiState,
    search: String,
    onThreadSelected: (String) -> Unit,
    onNewThread: () -> Unit,
    onSettleThread: (String) -> Unit,
    onUnsettleThread: (String) -> Unit,
) {
    val active = state.threads.filter { it.matches(search) }
    val settled = state.settledThreads.filter { it.matches(search) }
    Column(Modifier.padding(start = 24.dp, bottom = 8.dp)) {
        TextButton(onClick = onNewThread, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Add, contentDescription = null)
            Text("New thread", modifier = Modifier.padding(start = 8.dp).weight(1f))
        }
        if (active.isEmpty() && settled.isEmpty()) {
            Text(
                if (search.isEmpty()) "No threads in this project" else "No matching threads",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        active.forEach { thread ->
            ThreadRow(thread, thread.id == state.selectedThreadId, false, { onThreadSelected(thread.id) }) {
                onSettleThread(thread.id)
            }
        }
        if (settled.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Settled",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settled.forEach { thread ->
                ThreadRow(thread, thread.id == state.selectedThreadId, true, { onThreadSelected(thread.id) }) {
                    onUnsettleThread(thread.id)
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadPickerItemUi,
    selected: Boolean,
    settled: Boolean,
    onSelected: () -> Unit,
    onLifecycle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onSelected)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(thread.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            thread.supportingText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onLifecycle) {
            Icon(
                if (settled) Icons.ArchiveRestore else Icons.Check,
                contentDescription = if (settled) "Un-settle ${thread.title}" else "Settle ${thread.title}",
            )
        }
    }
}

@Composable
private fun VariantSwitcher(
    variant: ProjectPickerPrototypeVariant,
    onVariantChanged: (ProjectPickerPrototypeVariant) -> Unit,
) {
    val variants = ProjectPickerPrototypeVariant.entries
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onVariantChanged(variants[(variant.ordinal - 1 + variants.size) % variants.size]) }) {
            Text("←")
        }
        Text("${variant.name.first()} · ${variant.label}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onVariantChanged(variants[(variant.ordinal + 1) % variants.size]) }) {
            Text("→")
        }
    }
}

private fun ThreadPickerUiState.allThreads(): List<ThreadPickerItemUi> = threads + settledThreads

private fun ThreadPickerItemUi.matches(query: String): Boolean =
    query.isEmpty() || title.contains(query, true) || supportingText.orEmpty().contains(query, true)

private enum class ProjectPickerPrototypeVariant(val label: String) {
    ACCORDION("Accordion explorer"),
    CARDS("Project cards"),
    FAVORITES_FIRST("Favorites focus"),
}
