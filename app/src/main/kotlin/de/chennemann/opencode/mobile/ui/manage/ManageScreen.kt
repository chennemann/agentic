package de.chennemann.opencode.mobile.ui.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.domain.session.ServerState
import de.chennemann.opencode.mobile.domain.session.SessionState
import de.chennemann.opencode.mobile.icons.Heart
import de.chennemann.opencode.mobile.icons.HeartOutline
import de.chennemann.opencode.mobile.icons.Icons
import de.chennemann.opencode.mobile.icons.Add
import de.chennemann.opencode.mobile.icons.ChevronDown
import de.chennemann.opencode.mobile.icons.ChevronUp
import de.chennemann.opencode.mobile.icons.FilterList
import de.chennemann.opencode.mobile.icons.Star
import de.chennemann.opencode.mobile.icons.StarOutline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ManageScreen(state: ManageUiState, onEvent: (ManageEvent) -> Unit) {
    val projects = state.favoriteProjects + state.otherProjects
    val selected = projects.firstOrNull {
        workspaceId(it.worktree) == workspaceId(state.selectedProject.orEmpty())
    }
    val selectedWorktree = state.selectedProject
    val selectedName = state.selectedProjectName ?: selectedWorktree
    val selectedFavorite = selected?.favorite == true
    val list = rememberLazyListState()
    LaunchedEffect(state.sessionScroll) {
        if (state.sessionScroll == 0L) return@LaunchedEffect
        list.animateScrollToItem(SessionsItemIndex)
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        state = list,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            Text(
                text = "Workspace Hub",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item("server") {
            ServerCard(state.url, state.discovered, state.status, state.message, onEvent)
        }
        item("projects") {
            ProjectListCard(state, onEvent)
        }
        item("sessions") {
            SessionCard(
                state = state,
                selectedName = selectedName,
                selectedWorktree = selectedWorktree,
                selectedFavorite = selectedFavorite,
                onEvent = onEvent,
            )
        }
        if (!state.message.isNullOrBlank()) {
            item("message") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = state.message.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        item("back") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onEvent(ManageEvent.LogsRequested) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Logs")
                }
                OutlinedButton(
                    onClick = { onEvent(ManageEvent.BackRequested) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun ProjectListCard(state: ManageUiState, onEvent: (ManageEvent) -> Unit) {
    var filterOpen by remember { mutableStateOf(state.projectQuery.isNotBlank()) }
    var pathOpen by remember { mutableStateOf(false) }
    var projectsExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue(state.projectQuery)) }
    var path by remember { mutableStateOf(TextFieldValue(state.projectPath)) }

    LaunchedEffect(state.projectQuery) {
        if (state.projectQuery.isBlank()) return@LaunchedEffect
        filterOpen = true
    }

    LaunchedEffect(state.projectQuery) {
        if (state.projectQuery == query.text) return@LaunchedEffect
        query = TextFieldValue(
            text = state.projectQuery,
            selection = TextRange(state.projectQuery.length),
        )
    }

    LaunchedEffect(state.projectPath) {
        if (state.projectPath == path.text) return@LaunchedEffect
        path = TextFieldValue(
            text = state.projectPath,
            selection = TextRange(state.projectPath.length),
        )
    }

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Projects", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { filterOpen = !filterOpen }) {
                        Icon(
                            imageVector = Icons.FilterList,
                            contentDescription = "Filter projects",
                            tint = if (filterOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { pathOpen = !pathOpen }) {
                        Icon(
                            imageVector = Icons.Add,
                            contentDescription = "Add project path",
                            tint = if (pathOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (filterOpen) {
                TextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onEvent(ManageEvent.ProjectQueryChanged(it.text))
                    },
                    label = { Text("Search projects") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (pathOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = path,
                        onValueChange = {
                            path = it
                            onEvent(ManageEvent.ProjectPathChanged(it.text))
                        },
                        label = { Text("Open project path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (state.projectPath != path.text) {
                                onEvent(ManageEvent.ProjectPathChanged(path.text))
                            }
                            onEvent(ManageEvent.LoadProjectRequested)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open path")
                    }
                }
            }

            if (state.favoriteProjects.isEmpty() && state.otherProjects.isEmpty()) {
                Text(
                    text = if (state.loadingProjects) "Loading projects..." else "No projects found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            if (state.favoriteProjects.isNotEmpty()) {
                state.favoriteProjects.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { project ->
                            ProjectCard(
                                project = project,
                                selected = workspaceId(state.selectedProject.orEmpty()) == workspaceId(project.worktree),
                                compact = true,
                                modifier = Modifier.weight(1f),
                                onFavoriteToggle = { onEvent(ManageEvent.ProjectFavoriteToggled(project.worktree)) },
                                onSelect = { onEvent(ManageEvent.ProjectSelected(project.worktree)) },
                            )
                        }
                        if (row.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            val removable = state.selectedProject?.takeIf { selected ->
                (state.favoriteProjects + state.otherProjects)
                    .any { workspaceId(it.worktree) == workspaceId(selected) }
            }

            if (state.otherProjects.isEmpty()) {
                if (removable != null) {
                    OutlinedButton(
                        onClick = { onEvent(ManageEvent.ProjectRemoved(removable)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remove selected project")
                    }
                }
                return@Column
            }

            val count = state.otherProjects.size
            val suffix = if (count == 1) "project" else "projects"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { projectsExpanded = !projectsExpanded }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (projectsExpanded) {
                        "Hide $count $suffix"
                    } else {
                        "$count $suffix hidden"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (projectsExpanded) Icons.ChevronUp else Icons.ChevronDown,
                    contentDescription = if (projectsExpanded) {
                        "Collapse other projects"
                    } else {
                        "Expand other projects"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!projectsExpanded) {
                if (removable != null) {
                    OutlinedButton(
                        onClick = { onEvent(ManageEvent.ProjectRemoved(removable)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Remove selected project")
                    }
                }
                return@Column
            }

            state.otherProjects.forEach {
                ProjectCard(
                    project = it,
                    selected = workspaceId(state.selectedProject.orEmpty()) == workspaceId(it.worktree),
                    compact = false,
                    modifier = Modifier.fillMaxWidth(),
                    onFavoriteToggle = { onEvent(ManageEvent.ProjectFavoriteToggled(it.worktree)) },
                    onSelect = { onEvent(ManageEvent.ProjectSelected(it.worktree)) },
                )
            }
            if (removable != null) {
                OutlinedButton(
                    onClick = { onEvent(ManageEvent.ProjectRemoved(removable)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove selected project")
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectState,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onFavoriteToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 8.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = if (compact) Arrangement.Center else Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = project.name,
                    style = if (compact) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        text = project.worktree,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onFavoriteToggle) {
                Icon(
                    imageVector = if (project.favorite) Icons.Star else Icons.StarOutline,
                    contentDescription = if (project.favorite) "Unfavorite project" else "Favorite project",
                    tint = if (project.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    state: ManageUiState,
    selectedName: String?,
    selectedWorktree: String?,
    selectedFavorite: Boolean,
    onEvent: (ManageEvent) -> Unit,
) {
    var workspaceMenu by remember { mutableStateOf(false) }
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Project Sessions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = selectedName ?: "Choose a project above to view its sessions",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selectedWorktree != null) {
                    IconButton(
                        onClick = { onEvent(ManageEvent.ProjectFavoriteToggled(selectedWorktree)) },
                    ) {
                        Icon(
                            imageVector = if (selectedFavorite) Icons.Heart else Icons.HeartOutline,
                            contentDescription = if (selectedFavorite) {
                                "Unfavorite selected project"
                            } else {
                                "Favorite selected project"
                            },
                            tint = if (selectedFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            if (selectedWorktree == null) {
                return@Column
            }
            if (state.loadingSessions) {
                Text(
                    text = "Loading sessions...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.sessionSections.isEmpty() && !state.loadingSessions) {
                Text(
                    text = "No sessions found for this project",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.sessionSections.forEach { section ->
                    Text(section.workspace.title, style = MaterialTheme.typography.labelLarge)
                    section.sessions.forEach { session ->
                        OutlinedButton(
                            onClick = { onEvent(ManageEvent.SessionRequested(session.id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = session.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = sessionSubtitle(session, selectedWorktree),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onEvent(ManageEvent.SessionRequested(null)) },
                    enabled = !state.loadingSessions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.loadingSessions) "Creating..." else "New session")
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { workspaceMenu = true },
                        enabled = !state.loadingSessions && state.workspaceOptions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Create in: ${state.selectedWorkspaceName ?: "Local"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = workspaceMenu,
                        onDismissRequest = { workspaceMenu = false },
                    ) {
                        state.workspaceOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    workspaceMenu = false
                                    onEvent(ManageEvent.WorkspaceSelected(option.directory))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sessionSubtitle(session: SessionState, selectedWorktree: String?): String {
    val updated = session.updatedAt?.let {
        val value = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        "Updated ${SessionFormatter.format(value)}"
    } ?: "Updated unknown"
    val workspace = if (selectedWorktree.isNullOrBlank()) {
        false
    } else {
        workspaceId(session.directory) != workspaceId(selectedWorktree)
    }
    val prefix = if (workspace) "Workspace session • " else ""
    return "$prefix$updated | ${session.version}"
}

private fun workspaceId(path: String): String {
    val value = path.trimEnd('/', '\\')
    if (value.isBlank()) return path
    return value
}

private val SessionFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")
private const val SessionsItemIndex = 3
