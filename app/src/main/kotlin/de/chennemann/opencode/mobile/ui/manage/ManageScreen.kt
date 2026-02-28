package de.chennemann.opencode.mobile.ui.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.opencode.mobile.domain.session.ProjectState
import de.chennemann.opencode.mobile.icons.Icons
import de.chennemann.opencode.mobile.icons.Add
import de.chennemann.opencode.mobile.icons.ChevronDown
import de.chennemann.opencode.mobile.icons.ChevronUp
import de.chennemann.opencode.mobile.icons.FilterList
import de.chennemann.opencode.mobile.icons.Star
import de.chennemann.opencode.mobile.icons.StarOutline

@Composable
fun ManageScreen(state: ManageUiState, onEvent: (ManageEvent) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
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
    var filterOpen by remember { mutableStateOf(false) }
    var pathOpen by remember { mutableStateOf(false) }
    var projectsExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var path by remember { mutableStateOf(TextFieldValue(state.selectedProject.orEmpty())) }
    val projects = filterProjects(state.projects, query.text)
    val favoriteProjects = projects.filter { it.favorite }

    LaunchedEffect(state.selectedProject) {
        val selected = state.selectedProject.orEmpty()
        if (selected == path.text) return@LaunchedEffect
        path = TextFieldValue(
            text = selected,
            selection = TextRange(selected.length),
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
                        },
                        label = { Text("Open project path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onEvent(ManageEvent.LoadProjectRequested(path.text))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open path")
                    }
                }
            }

            if (projects.isEmpty()) {
                Text(
                    text = if (state.loadingProjects) "Loading projects..." else "No projects found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            if (favoriteProjects.isNotEmpty()) {
                favoriteProjects.chunked(2).forEach { row ->
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
                                onFavoriteToggle = { onEvent(ManageEvent.ProjectFavoriteToggled(project.id)) },
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
                projects.any { workspaceId(it.worktree) == workspaceId(selected) }
            }

            val count = projects.size
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
                        "Collapse projects"
                    } else {
                        "Expand projects"
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

            projects.forEach {
                ProjectCard(
                    project = it,
                    selected = workspaceId(state.selectedProject.orEmpty()) == workspaceId(it.worktree),
                    compact = false,
                    modifier = Modifier.fillMaxWidth(),
                    onFavoriteToggle = { onEvent(ManageEvent.ProjectFavoriteToggled(it.id)) },
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

private fun workspaceId(path: String): String {
    val value = path.trimEnd('/', '\\')
    if (value.isBlank()) return path
    return value
}

private fun filterProjects(projects: List<ProjectState>, query: String): List<ProjectState> {
    val value = query.trim().lowercase()
    if (value.isBlank()) return projects
    return projects.filter {
        it.name.lowercase().contains(value) || it.worktree.lowercase().contains(value)
    }
}
