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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import de.chennemann.agentic.icons.Undo
import de.chennemann.agentic.icons.Check
import de.chennemann.agentic.icons.Settings
import de.chennemann.agentic.icons.Star
import de.chennemann.agentic.icons.StarOutline
import de.chennemann.agentic.icons.Terminal
import de.chennemann.agentic.ui.search.ThreadSearchEntry

/** Full-page project selector, to the right of the conversation. */
@Composable
fun ChatProjectsPage(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onClose: () -> Unit,
    onOpenSearch: () -> Unit = {},
    focusSearch: Boolean = false,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) focusRequester.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.environmentLabel, style = MaterialTheme.typography.titleMedium)
                state.environmentPicker.environments
                    .firstOrNull { it.id == state.environmentPicker.selectedEnvironmentId }
                    ?.let { EnvironmentConnectionDot(it.connection, withLabel = true) }
            }
            IconButton(onClick = { onClose(); onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.NAVIGATION)) }) {
                Icon(Icons.Settings, contentDescription = "Settings")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search projects") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focusRequester),
        )
        ThreadSearchEntry(onClick = { onClose(); onOpenSearch() })
        Box(Modifier.weight(1f)) {
            ProjectThreadList(
                state, onEvent,
                onThreadChosen = { onClose(); onEvent(ChatUiEvent.ThreadSelected(it)) },
                onNewThread = { onClose(); onEvent(ChatUiEvent.NewThreadRequested) },
                onAddProject = { onClose(); onEvent(ChatUiEvent.ProjectCreationRequested) },
                query = query,
            )
        }
    }
}

/** Project tools occupy the full page to the left of the conversation. */
@Composable
fun ChatProjectToolsPage(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onClose: () -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onRunProjectScript: (String, ProjectScriptUi) -> Unit,
    onOpenUsage: () -> Unit,
    onOpenChanges: (String) -> Unit = {},
    onOpenPullRequests: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(state.projectLabel ?: "Project tools", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onClose(); onOpenPullRequests() }) { Text("Pull requests") }
        state.threadId?.let { threadId ->
            DrawerTools(state, threadId, onEvent, onClose, onOpenFiles, onOpenTerminal, onRunProjectScript, onOpenUsage, onOpenChanges)
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
    onOpenChanges: (String) -> Unit = {},
) {
    var terminalMenuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        if (state.latestTurnChanges.turnId != null) {
            TextButton(onClick = { onClose(); onOpenChanges(threadId) }) {
                Text("Changes (${state.latestTurnChanges.files.size})")
            }
        }
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
    query: String = "",
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentProjectId = state.projectId
    val expandedId = state.threadPicker.selectedProjectId
    val projects = state.threadPicker.projects
        .filter { it.label.contains(query, ignoreCase = true) }
        .filter { query.isNotBlank() || showAll || it.unsettledThreadCount > 0 || it.favorite || it.id == currentProjectId }
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
            color = LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    val (activeThreads, workingThreads) = picker.threads.splitWorkingSection()
    activeThreads.forEach { thread ->
        key(thread.id) { ThreadWithSubagents(thread, picker, onThreadChosen) }
    }
    if (workingThreads.isNotEmpty()) {
        WorkingSection(workingThreads, picker, onThreadChosen)
    }
    if (picker.settledThreads.isNotEmpty()) {
        TextButton(onClick = { onEvent(ChatUiEvent.SettledThreadsVisibilityChanged(!picker.showSettled)) }) {
            Text("${if (picker.showSettled) "Hide" else "Show"} ${picker.settledThreads.size} settled")
        }
        if (picker.showSettled) {
            picker.settledThreads.forEach { thread ->
                key(thread.id) { ThreadWithSubagents(thread, picker, onThreadChosen, settled = true) }
            }
        }
    }
}

/** Expansion belongs to the drawer, independently of thread selection. */
@Composable
private fun ThreadWithSubagents(
    thread: ThreadPickerItemUi,
    picker: ThreadPickerUiState,
    onThreadChosen: (String) -> Unit,
    settled: Boolean = false,
) {
    val children = picker.subagents[thread.id].orEmpty()
    var expanded by rememberSaveable(thread.id) { mutableStateOf(false) }
    val childSelected = children.any { it.id == picker.selectedThreadId }
    LaunchedEffect(childSelected) { if (childSelected) expanded = true }
    ThreadRow(thread, thread.id == picker.selectedThreadId, settled = settled) { onThreadChosen(thread.id) }
    if (children.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) Icons.ChevronUp else Icons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            val working = children.count { it.active }
            val label = if (children.size == 1) "sub-agent" else "sub-agents"
            Text("${children.size} $label" + if (working > 0) " · $working working" else "")
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp)) {
                children.forEach { child ->
                    ThreadRow(child, child.id == picker.selectedThreadId) { onThreadChosen(child.id) }
                }
            }
        }
    }
}

/** Collapsed like the T3 clients' Working section, but opens itself for the selected thread. */
@Composable
private fun WorkingSection(
    threads: List<ThreadPickerItemUi>,
    picker: ThreadPickerUiState,
    onThreadChosen: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedInside = threads.any { thread ->
        thread.id == picker.selectedThreadId || picker.subagents[thread.id].orEmpty().any { it.id == picker.selectedThreadId }
    }
    LaunchedEffect(selectedInside) { if (selectedInside) expanded = true }
    TextButton(onClick = { expanded = !expanded }) {
        Icon(
            if (expanded) Icons.ChevronUp else Icons.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text("Working · ${threads.size}")
    }
    if (expanded) {
        threads.forEach { thread ->
            key(thread.id) { ThreadWithSubagents(thread, picker, onThreadChosen) }
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

/** Fixed-height row so callers can size a list from the number of threads. */
internal val ThreadRowHeight = 40.dp

@Composable
internal fun ThreadRow(
    thread: ThreadPickerItemUi,
    selected: Boolean,
    settled: Boolean = false,
    onLifecycle: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ThreadRowHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (onLifecycle != null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(
                when {
                    thread.active -> MaterialTheme.colorScheme.primary
                    settled || thread.archived -> Color.Transparent
                    else -> LocalContentColor.current.copy(alpha = 0.4f)
                },
            ),
        )
        Text(
            thread.title,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                settled || thread.archived -> LocalContentColor.current.copy(alpha = 0.6f)
                else -> LocalContentColor.current
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val activity = thread.activity?.takeUnless { settled || thread.archived || thread.needsAttention || thread.snoozedUntil != null }
        val now = rememberTickingNow(activity?.since != null)
        val badge = when {
            thread.needsAttention -> "Needs input"
            thread.archived -> "Archived"
            thread.snoozedUntil != null -> "Snoozed"
            activity != null -> activity.label(now)
            else -> null
        }
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    thread.needsAttention -> MaterialTheme.colorScheme.error
                    activity?.kind == ThreadActivityKind.WORKING && !selected -> MaterialTheme.colorScheme.primary
                    else -> LocalContentColor.current.copy(alpha = 0.6f)
                },
            )
        }
        onLifecycle?.let {
            IconButton(onClick = it, modifier = Modifier.size(ThreadRowHeight)) {
                Icon(
                    if (settled) Icons.Undo else Icons.Check,
                    contentDescription = if (settled) "Un-settle ${thread.title}" else "Settle ${thread.title}",
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
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
