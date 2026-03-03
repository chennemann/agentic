package de.chennemann.agentic.ui.manage

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pavi2410.appupdater.AppUpdater
import com.pavi2410.appupdater.UpdateState
import com.pavi2410.appupdater.github
import de.chennemann.agentic.BuildConfig
import de.chennemann.agentic.di.DefaultDispatcherProvider
import de.chennemann.agentic.domain.session.ProjectState
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.FilterList
import de.chennemann.agentic.icons.Refresh
import de.chennemann.agentic.icons.Star
import de.chennemann.agentic.icons.StarOutline
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ManageScreen(state: ManageUiState, onEvent: (ManageEvent) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            Text(
                text = "Workspace Hub",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item("server") {
            ServerCard(state.url, state.status, onEvent)
        }
        item("updates") {
            UpdateSectionCard()
        }
        item("projects") {
            ProjectListCard(state, onEvent)
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
private fun UpdateSectionCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dispatchers = remember { DefaultDispatcherProvider() }
    val updater by produceState<AppUpdater?>(
        initialValue = null,
        key1 = context.applicationContext,
    ) {
        value = withContext(dispatchers.io) {
            AppUpdater.github(
                context = context.applicationContext,
                owner = BuildConfig.UPDATE_REPO_OWNER,
                repo = BuildConfig.UPDATE_REPO_NAME,
            )
        }
    }

    DisposableEffect(updater) {
        onDispose {
            updater?.close()
        }
    }

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("App Updates", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Checks releases from ${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val activeUpdater = updater
            if (activeUpdater == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Preparing update checker...",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Text(
                text = "Current version: ${activeUpdater.currentVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val updateState by activeUpdater.state.collectAsState()
            when (val state = updateState) {
                is UpdateState.Idle -> {
                    Text(
                        text = "Tap below to check for updates.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { activeUpdater.checkForUpdate() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Check for updates")
                    }
                }
                is UpdateState.Checking -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Checking...", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.UpToDate -> {
                    Text(
                        text = "You're up to date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { activeUpdater.checkForUpdate() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Check again")
                    }
                }
                is UpdateState.UpdateAvailable -> {
                    Text(
                        text = "${activeUpdater.currentVersion} -> ${state.release.version}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = { scope.launch { activeUpdater.downloadUpdate() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download update (${formatUpdateSize(state.asset.size)})")
                    }
                }
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(
                        progress = state.progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading ${formatUpdateSize(state.bytesDownloaded)} / ${formatUpdateSize(state.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Text(
                        text = "Download complete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = { activeUpdater.installUpdate() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Install update")
                    }
                }
                is UpdateState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { activeUpdater.checkForUpdate() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

private fun formatUpdateSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        bytes > 0 -> "$bytes B"
        else -> "0 B"
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
                    IconButton(
                        onClick = { onEvent(ManageEvent.ProjectsRefreshRequested) },
                        enabled = !state.loadingProjects,
                    ) {
                        Icon(
                            imageVector = Icons.Refresh,
                            contentDescription = if (state.loadingProjects) {
                                "Refreshing projects"
                            } else {
                                "Refresh projects"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

            if (state.loadingProjects && projects.isNotEmpty()) {
                Text(
                    text = "Refreshing projects...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
