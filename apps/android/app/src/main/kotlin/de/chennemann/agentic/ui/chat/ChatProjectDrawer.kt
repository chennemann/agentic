package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Settings
import de.chennemann.agentic.icons.Star
import de.chennemann.agentic.icons.StarOutline
import de.chennemann.agentic.icons.Terminal

/**
 * Left drawer: environment and settings on top, projects with their threads in the middle,
 * thread tools (files, terminal, changes, usage) pinned at the bottom.
 */
@Composable
fun ChatProjectDrawer(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onClose: () -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onRunProjectScript: (String, ProjectScriptUi) -> Unit,
    onOpenUsage: () -> Unit,
) {
    ModalDrawerSheet {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.environmentLabel, style = MaterialTheme.typography.titleMedium)
                state.environmentPicker.environments
                    .firstOrNull { it.id == state.environmentPicker.selectedEnvironmentId }
                    ?.let { EnvironmentConnectionDot(it.connection, withLabel = true) }
            }
            IconButton(onClick = { onClose(); onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.NAVIGATION)) }) {
                Icon(Icons.Settings, contentDescription = "Settings", modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Box(Modifier.weight(1f)) {
            ProjectThreadList(
                state = state,
                onEvent = onEvent,
                onThreadChosen = { onClose(); onEvent(ChatUiEvent.ThreadSelected(it)) },
                onNewThread = { onClose(); onEvent(ChatUiEvent.NewThreadRequested) },
                onAddProject = { onClose(); onEvent(ChatUiEvent.ProjectCreationRequested) },
            )
        }
        state.threadId?.let { threadId ->
            HorizontalDivider()
            DrawerTools(state, threadId, onEvent, onClose, onOpenFiles, onOpenTerminal, onRunProjectScript, onOpenUsage)
        }
    }
}

@Composable
private fun ColumnScope.DrawerTools(
    state: ChatUiState,
    threadId: String,
    onEvent: (ChatUiEvent) -> Unit,
    onClose: () -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onRunProjectScript: (String, ProjectScriptUi) -> Unit,
    onOpenUsage: () -> Unit,
) {
    var terminalMenuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.canBrowseFiles) {
            TextButton(onClick = { onClose(); onOpenFiles(threadId) }) { Text("Files") }
        }
        if (state.canUseTerminal) {
            Box {
                TextButton(
                    onClick = {
                        if (state.projectScripts.isEmpty()) {
                            onClose()
                            onOpenTerminal(threadId)
                        } else {
                            terminalMenuOpen = true
                        }
                    },
                ) {
                    Icon(Icons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Terminal")
                }
                DropdownMenu(expanded = terminalMenuOpen, onDismissRequest = { terminalMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open terminal") },
                        onClick = { terminalMenuOpen = false; onClose(); onOpenTerminal(threadId) },
                    )
                    state.projectScripts.forEach { script ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (script.setup) "${script.name} (setup)" else script.name)
                                    Text(script.command, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = { terminalMenuOpen = false; onClose(); onRunProjectScript(threadId, script) },
                        )
                    }
                }
            }
        }
        if (state.latestTurnChanges.files.isNotEmpty()) {
            TextButton(onClick = { onClose(); onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.LATEST_TURN_CHANGES)) }) {
                Text("Changes (${state.latestTurnChanges.files.size})")
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onClose(); onOpenUsage() }) { Text("Usage") }
    }
}

/**
 * Projects with their threads. The expanded project is the server-side picker project so
 * `threadPicker.threads` lines up with it. Current project first, then favorites, then by open
 * thread count; projects without open threads hide behind "All projects".
 */
@Composable
fun ProjectThreadList(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onThreadChosen: (String) -> Unit,
    onNewThread: () -> Unit,
    onAddProject: () -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentProjectId = state.projectId
    val expandedId = state.threadPicker.selectedProjectId
    val projects = state.threadPicker.projects
        .filter { showAll || it.unsettledThreadCount > 0 || it.favorite || it.id == currentProjectId }
        .sortedWith(
            compareByDescending<ProjectPickerItemUi> { it.id == currentProjectId }
                .thenByDescending { it.favorite }
                .thenByDescending { it.unsettledThreadCount }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        items(projects, key = { it.id }) { project ->
            val expanded = project.id == expandedId
            ProjectRow(
                project = project,
                current = project.id == currentProjectId,
                expanded = expanded,
                onClick = { onEvent(ChatUiEvent.ProjectPaneExpanded(project.id)) },
                onFavorite = { onEvent(ChatUiEvent.ProjectFavoriteChanged(project.id, !project.favorite)) },
            )
            if (expanded) {
                Column(Modifier.padding(start = 24.dp, end = 4.dp, bottom = 8.dp)) {
                    ProjectThreads(state.threadPicker, onEvent, onThreadChosen, onNewThread)
                }
            }
        }
        item("footer") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Fewer projects" else "All projects") }
                TextButton(onClick = onAddProject) {
                    Icon(Icons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add project")
                }
            }
        }
    }
}

/** New thread, active threads, and a collapsed settled section for the picker project. */
@Composable
fun ProjectThreads(
    picker: ThreadPickerUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onThreadChosen: (String) -> Unit,
    onNewThread: () -> Unit,
) {
    NewThreadRow(onNewThread)
    if (picker.threads.isEmpty() && picker.settledThreads.isEmpty()) {
        Text(
            "No threads yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    picker.threads.forEach { thread ->
        ThreadRow(thread, thread.id == picker.selectedThreadId) { onThreadChosen(thread.id) }
    }
    if (picker.settledThreads.isNotEmpty()) {
        TextButton(onClick = { onEvent(ChatUiEvent.SettledThreadsVisibilityChanged(!picker.showSettled)) }) {
            Text("${if (picker.showSettled) "Hide" else "Show"} ${picker.settledThreads.size} settled")
        }
        if (picker.showSettled) {
            picker.settledThreads.forEach { thread ->
                ThreadRow(thread, thread.id == picker.selectedThreadId, settled = true) { onThreadChosen(thread.id) }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectPickerItemUi,
    current: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                project.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (project.unsettledThreadCount == 0) "No open threads" else "${project.unsettledThreadCount} open",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (project.favorite) Icons.Star else Icons.StarOutline,
                contentDescription = if (project.favorite) "Remove ${project.label} from favorites" else "Favorite ${project.label}",
                modifier = Modifier.size(18.dp),
                tint = if (project.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (expanded) Icons.ChevronUp else Icons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
        )
    }
}

@Composable
private fun NewThreadRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("New thread", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ThreadRow(thread: ThreadPickerItemUi, selected: Boolean, settled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(
                when {
                    thread.active -> MaterialTheme.colorScheme.primary
                    settled || thread.archived -> Color.Transparent
                    else -> MaterialTheme.colorScheme.outline
                },
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                thread.title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    settled || thread.archived -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            thread.snoozedUntil?.let {
                Text("Snoozed until $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (thread.archived) {
            Text("Archived", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EnvironmentConnectionDot(connection: EnvironmentConnectionIndicatorUi, withLabel: Boolean = false) {
    val color = when (connection) {
        EnvironmentConnectionIndicatorUi.LIVE -> MaterialTheme.colorScheme.primary
        EnvironmentConnectionIndicatorUi.CONNECTING, EnvironmentConnectionIndicatorUi.CACHED -> MaterialTheme.colorScheme.onSurfaceVariant
        EnvironmentConnectionIndicatorUi.OFFLINE, EnvironmentConnectionIndicatorUi.BLOCKED -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        if (withLabel) {
            Text(connection.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun EnvironmentConnectionIndicatorUi.label(): String = when (this) {
    EnvironmentConnectionIndicatorUi.LIVE -> "Live"
    EnvironmentConnectionIndicatorUi.CONNECTING -> "Connecting"
    EnvironmentConnectionIndicatorUi.CACHED -> "Cached"
    EnvironmentConnectionIndicatorUi.OFFLINE -> "Offline"
    EnvironmentConnectionIndicatorUi.BLOCKED -> "Blocked"
}
