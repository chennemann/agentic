package de.chennemann.agentic.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * Experimental full-screen project pane based on the selected favorites-focused prototype.
 * Filter and expansion state remain local while project favorites are persisted by the caller.
 */
@Composable
fun ExperimentalSlideOutProjectPicker(
    progress: Float,
    state: ThreadPickerUiState,
    onDismiss: () -> Unit,
    onProjectExpanded: (String) -> Unit,
    onProjectFavoriteChanged: (projectId: String, favorite: Boolean) -> Unit,
    onThreadSelected: (String) -> Unit,
    onNewThread: () -> Unit,
    onAddProject: (String) -> Unit,
    onSettleThread: (String) -> Unit,
    onUnsettleThread: (String) -> Unit,
) {
    var expandedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllProjects by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val favorites = state.projects.filter(ProjectPickerItemUi::favorite).mapTo(mutableSetOf()) { it.id }
    val projects = state.projects
        .filter { project ->
            val matchesSearch = project.matches(search)
            when {
                search.isNotEmpty() -> matchesSearch
                showAllProjects -> true
                else -> project.unsettledThreadCount > 0
            }
        }
        .sortedWith(
            compareBy<ProjectPickerItemUi> { it.id in favorites }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )

    BackHandler(enabled = progress > 0f, onBack = onDismiss)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = -(1f - progress) * size.width },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(top = 12.dp)) {
            ProjectPaneHeader(onDismiss)
            ProjectList(
                modifier = Modifier.weight(1f),
                projects = projects,
                state = state,
                expandedProjectId = expandedProjectId,
                favorites = favorites,
                search = search,
                onToggleExpanded = { projectId ->
                    expandedProjectId = if (expandedProjectId == projectId) null else projectId
                    if (expandedProjectId != null) onProjectExpanded(projectId)
                },
                onToggleFavorite = { projectId ->
                    onProjectFavoriteChanged(projectId, projectId !in favorites)
                },
                onThreadSelected = onThreadSelected,
                onNewThread = onNewThread,
                onAddProject = onAddProject,
                onSettleThread = onSettleThread,
                onUnsettleThread = onUnsettleThread,
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                FilterChip(
                    selected = showAllProjects,
                    onClick = { showAllProjects = !showAllProjects },
                    label = {
                        Text(if (showAllProjects) "Showing all projects" else "Show projects without open threads")
                    },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search projects and threads") },
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun ProjectPaneHeader(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Projects", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Favorites are kept within thumb reach",
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
    expandedProjectId: String?,
    favorites: Set<String>,
    search: String,
    onToggleExpanded: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onThreadSelected: (String) -> Unit,
    onNewThread: () -> Unit,
    onAddProject: (String) -> Unit,
    onSettleThread: (String) -> Unit,
    onUnsettleThread: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (projects.isEmpty()) {
            item("empty") {
                EmptyProjects(search = search, onAddProject = onAddProject)
            }
        }
        items(projects, key = { it.id }) { project ->
            val expanded = project.id == expandedProjectId
            ProjectRow(
                project = project,
                expanded = expanded,
                favorite = project.id in favorites,
                onToggleExpanded = { onToggleExpanded(project.id) },
                onToggleFavorite = { onToggleFavorite(project.id) },
            )
            if (expanded) {
                ProjectThreads(
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
private fun EmptyProjects(search: String, onAddProject: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (search.isEmpty()) "No projects have open threads" else "No project matches “$search”",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (search.isNotEmpty()) {
            TextButton(onClick = { onAddProject(search) }) {
                Icon(Icons.Add, contentDescription = null)
                Text("Add project", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectPickerItemUi,
    expanded: Boolean,
    favorite: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        onClick = onToggleExpanded,
        color = if (favorite) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
    ) {
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
                Text(
                    buildString {
                        append("${project.unsettledThreadCount} open")
                        project.supportingText?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun ProjectThreads(
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

private fun ProjectPickerItemUi.matches(query: String): Boolean =
    query.isEmpty() || label.contains(query, true) || supportingText.orEmpty().contains(query, true) ||
        searchableThreadTitles.any { it.contains(query, true) }

private fun ThreadPickerItemUi.matches(query: String): Boolean =
    query.isEmpty() || title.contains(query, true) || supportingText.orEmpty().contains(query, true)
