package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.preferences.FollowUpBehavior
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.ArchiveRestore
import de.chennemann.agentic.icons.Check
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Star
import de.chennemann.agentic.icons.StarOutline
import java.time.Instant
import java.time.temporal.ChronoUnit
import de.chennemann.agentic.domain.preferences.AccentPreference
import de.chennemann.agentic.domain.preferences.ThemePreference
import de.chennemann.agentic.ui.theme.previewColor

@Composable
fun ChatSelectionSheets(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onOpenUpdater: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
) {
    when (state.activePicker) {
        ChatPickerUi.NAVIGATION -> {
            SettingsSheet(
                state = state,
                onEvent = onEvent,
                onOpenUpdater = onOpenUpdater,
                onOpenUsage = onOpenUsage,
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            )
        }

        ChatPickerUi.ENVIRONMENT -> {
            PickerSheet(
                title = "Environment",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                item("pair-environment") {
                    TextButton(
                        onClick = { onEvent(ChatUiEvent.PairEnvironmentRequested) },
                    ) {
                        Text("Pair another environment")
                    }
                }
                items(
                    items = state.environmentPicker.environments,
                    key = { "environment:${it.id}" },
                ) { environment ->
                    Column {
                        PickerRow(
                            label = environment.label,
                            supportingText = environment.supportingText,
                            selected = environment.id == state.environmentPicker.selectedEnvironmentId,
                            trailingText = environment.connection.label(),
                            onClick = { onEvent(ChatUiEvent.EnvironmentSelected(environment.id)) },
                        )
                        environment.diagnosticText?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (environment.canReconnect) {
                            TextButton(
                                onClick = { onEvent(ChatUiEvent.ConnectionRetryRequested) },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Reconnect ${environment.label}")
                            }
                        }
                        TextButton(
                            onClick = { onEvent(ChatUiEvent.EnvironmentRemovalRequested(environment.id)) },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Remove ${environment.label}")
                        }
                    }
                }
            }
        }

        ChatPickerUi.PROJECT_THREAD -> {
            ProjectThreadsSheet(
                state = state,
                onEvent = onEvent,
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            )
        }

        ChatPickerUi.PROVIDER_MODEL -> {
            PickerSheet(
                title = "Provider and model",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                items(
                    items = state.composer.providerModels,
                    key = { "provider-model:${it.id}" },
                ) { option ->
                    ModelPickerRow(
                        option = option,
                        selected = option.id == state.composer.selectedProviderModelId,
                        onSelect = { onEvent(ChatUiEvent.ProviderModelSelected(option.id)) },
                        onFavoriteChanged = {
                            onEvent(
                                ChatUiEvent.ProviderModelFavoriteChanged(
                                    id = option.id,
                                    favorite = it,
                                ),
                            )
                        },
                    )
                }
            }
        }

        ChatPickerUi.RUNTIME_MODE -> {
            PickerSheet(
                title = "Runtime mode",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                items(
                    items = state.composer.runtimeModes,
                    key = { "runtime-mode:${it.id}" },
                ) { option ->
                    PickerRow(
                        label = option.label,
                        supportingText = option.description,
                        selected = option.id == state.composer.selectedRuntimeModeId,
                        onClick = { onEvent(ChatUiEvent.RuntimeModeSelected(option.id)) },
                    )
                }
            }
        }

        ChatPickerUi.THREAD_WORKSPACE -> {
            PickerSheet(
                title = "Thread workspace",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                if (state.composer.workspaceChoicesLoading) {
                    item { Text("Loading workspace choicesâ€¦") }
                }
                items(
                    items = state.composer.workspaceChoices,
                    key = { it.id },
                ) { option ->
                    PickerRow(
                        label = option.label,
                        supportingText = option.description,
                        selected = option.id == state.composer.selectedWorkspaceId,
                        onClick = { onEvent(ChatUiEvent.ThreadWorkspaceSelected(option.id)) },
                    )
                }
            }
        }

        ChatPickerUi.LATEST_TURN_CHANGES -> {
            PickerSheet(
                title = "Latest turn changes",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                item("changes-summary") {
                    Text(
                        text = fileCountLabel(state.latestTurnChanges.files.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(
                    items = state.latestTurnChanges.files,
                    key = { "changed-file:${it.path}" },
                ) { file ->
                    ChangedFileRow(file)
                }
            }
        }

        null -> Unit
    }
    state.threadDeletion?.let { deletion ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.DeleteThreadDismissed) },
            title = { Text("Permanently delete ${deletion.title}?") },
            text = {
                Text(
                    when {
                        deletion.checking -> "Checking for pending work…"
                        deletion.blockers.isNotEmpty() ->
                            "Deletion is blocked: ${deletion.blockers.joinToString()}. Resolve it before retrying."
                        deletion.errorMessage != null -> deletion.errorMessage
                        else -> "This cannot be undone. The thread will remain open until the server confirms deletion."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = deletion.canConfirm,
                    onClick = { onEvent(ChatUiEvent.DeleteThreadConfirmed) },
                ) {
                    Text(if (deletion.deleting) "Deleting…" else "Delete permanently")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deletion.deleting,
                    onClick = { onEvent(ChatUiEvent.DeleteThreadDismissed) },
                ) { Text("Cancel") }
            },
        )
    }
    state.sharedTextImport?.let { shared ->
        AlertDialog(
            onDismissRequest = { Unit },
            title = { Text("Review shared text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shared.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Text("Choose environment", style = MaterialTheme.typography.labelLarge)
                    shared.environments.forEach { item ->
                        TextButton(
                            onClick = { onEvent(ChatUiEvent.SharedImportEnvironmentSelected(item.id)) },
                        ) { Text(if (shared.environmentId == item.id) "✓ ${item.label}" else item.label) }
                    }
                    Text("Choose project", style = MaterialTheme.typography.labelLarge)
                    shared.projects.forEach { item ->
                        TextButton(
                            enabled = shared.environmentId != null,
                            onClick = { onEvent(ChatUiEvent.SharedImportProjectSelected(item.id)) },
                        ) { Text(if (shared.projectId == item.id) "✓ ${item.label}" else item.label) }
                    }
                    shared.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = shared.canImport,
                    onClick = { onEvent(ChatUiEvent.SharedImportConfirmed) },
                ) { Text(if (shared.importing) "Importing…" else "Create draft") }
            },
            dismissButton = {
                TextButton(
                    enabled = !shared.importing,
                    onClick = { onEvent(ChatUiEvent.SharedImportDiscarded) },
                ) { Text("Discard") }
            },
        )
    }
    state.sharedTextImportError?.let { error ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.SharedImportErrorDismissed) },
            title = { Text("Could not import shared content") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.SharedImportErrorDismissed) }) { Text("OK") }
            },
        )
    }
    state.shortcutError?.let { error ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.ShortcutErrorDismissed) },
            title = { Text("Shortcut unavailable") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.ShortcutErrorDismissed) }) { Text("OK") }
            },
        )
    }
    state.cacheClearance?.let { cache ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.CacheClearDismissed) },
            title = { Text("Clear environment cache?") },
            text = { Column { cache.categories.forEach { Text(it) }; Text("Clearable: ${cache.clearableBytes} bytes"); cache.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (cache.success) Text("Cache cleared; resynchronizing.") } },
            confirmButton = { TextButton(enabled = !cache.clearing && cache.clearableBytes > 0, onClick = { onEvent(ChatUiEvent.CacheClearConfirmed) }) { Text(if (cache.clearing) "Working…" else "Clear cache") } },
            dismissButton = { TextButton(enabled = !cache.clearing, onClick = { onEvent(ChatUiEvent.CacheClearDismissed) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChangedFileRow(file: ChangedFileUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.kind
                    .ifBlank { "modified" }
                    .replace('-', ' ')
                    .replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (file.additions > 0 || file.deletions > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "+${file.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "−${file.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    option: ProviderModelOptionUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onFavoriteChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.modelLabel,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = option.supportingText ?: option.providerLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onFavoriteChanged(!option.favorite) }) {
            Icon(
                imageVector = if (option.favorite) Icons.Star else Icons.StarOutline,
                contentDescription = if (option.favorite) {
                    "Remove ${option.modelLabel} from favorites"
                } else {
                    "Add ${option.modelLabel} to favorites"
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    expanded: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = if (expanded) {
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("picker-title") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun ThreadPickerRow(
    thread: ThreadPickerItemUi,
    selected: Boolean,
    onClick: () -> Unit,
    onLifecycleClick: () -> Unit,
    settled: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = thread.title,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            thread.supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onLifecycleClick) {
            Icon(
                imageVector = if (thread.archived || settled) Icons.ArchiveRestore else Icons.Check,
                contentDescription = when {
                    thread.archived -> "Unarchive ${thread.title}"
                    settled -> "Un-settle ${thread.title}"
                    else -> "Settle ${thread.title}"
                },
            )
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    supportingText: String?,
    selected: Boolean,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(if (selected) "Selected" else "Select") },
        )
    }
}

/** Threads of the picker project, with thread and project actions behind a single menu. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProjectThreadsSheet(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = state.threadPicker
    val project = picker.projects.firstOrNull { it.id == picker.selectedProjectId }
    val isCurrentProject = project != null && project.id == state.projectId
    var menuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(project?.label ?: "Threads", style = MaterialTheme.typography.titleLarge)
                project?.supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (project != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Text("⋯", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val close = { menuOpen = false }
                        if (isCurrentProject && state.threadId != null) {
                            ThreadActionMenuItems(state, onEvent, onDone = { close(); onDismiss() })
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(if (picker.showArchived) "Hide archived threads" else "Show archived threads") },
                            onClick = { close(); onEvent(ChatUiEvent.ArchivedThreadsVisibilityChanged(!picker.showArchived)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename project") },
                            onClick = { close(); onEvent(ChatUiEvent.ProjectRenameRequested(project.id)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove project", color = MaterialTheme.colorScheme.error) },
                            onClick = { close(); onEvent(ChatUiEvent.ProjectRemovalRequested(project.id)) },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
        ) {
            ProjectThreads(
                picker = picker,
                onEvent = onEvent,
                onThreadChosen = { onEvent(ChatUiEvent.ThreadSelected(it)) },
                onNewThread = { onEvent(ChatUiEvent.NewThreadRequested) },
            )
        }
    }
}

/** Actions on the current thread. Callers decide where the menu is anchored. */
@Composable
fun ThreadActionMenuItems(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onDone: () -> Unit,
) {
    fun run(block: () -> Unit) {
        onDone()
        block()
    }
    if (state.canRenameThread) {
        DropdownMenuItem(text = { Text("Rename thread") }, onClick = { run { onEvent(ChatUiEvent.RenameThreadRequested) } })
    }
    if (state.isThreadArchived) {
        DropdownMenuItem(text = { Text("Unarchive") }, onClick = { run { onEvent(ChatUiEvent.UnarchiveThreadRequested) } })
    } else if (state.canArchiveThread) {
        DropdownMenuItem(text = { Text("Archive") }, onClick = { run { onEvent(ChatUiEvent.ArchiveThreadRequested) } })
    }
    if (state.threadSnooze.supported) {
        if (state.threadSnooze.isSnoozed) {
            DropdownMenuItem(
                text = { Text(if (state.threadSnooze.actionInProgress) "Waking…" else "Wake") },
                enabled = state.threadSnooze.canWake,
                onClick = { run { onEvent(ChatUiEvent.ThreadWakeRequested) } },
            )
        } else {
            DropdownMenuItem(
                text = { Text(if (state.threadSnooze.actionInProgress) "Snoozing…" else "Snooze 1h") },
                enabled = state.threadSnooze.canSnooze,
                onClick = {
                    run { onEvent(ChatUiEvent.ThreadSnoozeRequested(Instant.now().plus(1, ChronoUnit.HOURS).toString())) }
                },
            )
        }
    }
    if (state.canDeleteThread) {
        DropdownMenuItem(
            text = { Text("Delete permanently", color = MaterialTheme.colorScheme.error) },
            onClick = { run { onEvent(ChatUiEvent.DeleteThreadRequested) } },
        )
    }
}

/** App-level settings only; thread and project actions live with the thread list. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsSheet(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onOpenUpdater: () -> Unit,
    onOpenUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val environment = state.environmentPicker.environments
        .firstOrNull { it.id == state.environmentPicker.selectedEnvironmentId }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item { Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp)) }

            item { SettingsSectionLabel("Environment") }
            item {
                SettingsRow(
                    title = state.environmentLabel,
                    subtitle = environment?.supportingText,
                    trailing = { environment?.let { EnvironmentConnectionDot(it.connection, withLabel = true) } },
                    onClick = { onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.ENVIRONMENT)) },
                )
            }
            item { SettingsRow("Pair or manage environments", onClick = { onEvent(ChatUiEvent.PairEnvironmentRequested) }) }

            item { SettingsSectionLabel("Appearance") }
            item {
                SettingsChipRow(ThemePreference.entries, state.interfaceSettings.theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
                    onEvent(ChatUiEvent.ThemeSelected(it))
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    items(AccentPreference.entries) { accent ->
                        FilterChip(
                            selected = state.interfaceSettings.accent == accent,
                            onClick = { onEvent(ChatUiEvent.AccentSelected(accent)) },
                            label = { Text(accent.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(accent.previewColor()),
                                )
                            },
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Interface size", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    SettingsChipRow(listOf(0.9f, 1f, 1.15f), state.interfaceSettings.interfaceScale, { "${(it * 100).toInt()}%" }) {
                        onEvent(ChatUiEvent.InterfaceScaleSelected(it))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Code and Markdown size", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    SettingsChipRow(listOf(0.9f, 1f, 1.15f), state.interfaceSettings.codeScale, { "${(it * 100).toInt()}%" }) {
                        onEvent(ChatUiEvent.CodeScaleSelected(it))
                    }
                }
            }

            item { SettingsSectionLabel("Composer") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send while running", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Long-press send for the other action",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsChipRow(FollowUpBehavior.entries, state.interfaceSettings.followUpBehavior, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
                        onEvent(ChatUiEvent.FollowUpBehaviorSelected(it))
                    }
                }
            }
            item {
                SettingsRow(
                    title = "Voice input",
                    subtitle = if (state.groqSettings.apiKeyConfigured) "Groq API key configured" else "Add a Groq API key to enable the microphone",
                    onClick = { onEvent(ChatUiEvent.GroqSettingsRequested) },
                )
            }
            item {
                SettingsRow(
                    title = "Models and favorites",
                    subtitle = "Choose models and manage composer shortcuts",
                    onClick = { onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL)) },
                )
            }

            item { SettingsSectionLabel("More") }
            item { SettingsRow("Usage", onClick = onOpenUsage) }
            state.environmentPicker.selectedEnvironmentId?.let { id ->
                item { SettingsRow("Storage and cache", onClick = { onEvent(ChatUiEvent.CacheInspectionRequested(id)) }) }
            }
            StorUpdaterSettingsEntry(onClick = onOpenUpdater)
            if (state.durableWork.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Text("Pending sync", style = MaterialTheme.typography.labelLarge)
                        state.durableWork.forEach { work ->
                            Text(
                                buildString {
                                    append(work.label)
                                    work.threadId?.let { append(" · thread ").append(it) }
                                    append(if (work.failed) " · failed" else " · pending")
                                    if (work.attemptCount > 0) append(" · attempts ").append(work.attemptCount)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            work.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        if (state.durableWork.any(DurableWorkUi::awaitsReplay)) {
                            TextButton(onClick = { onEvent(ChatUiEvent.DurableWorkRetryRequested) }) {
                                Text("Reconnect and retry")
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Hardware keyboard: Ctrl/⌘+Enter send · Shift+Enter newline · Esc stop · Ctrl/⌘+N new thread · Ctrl/⌘+K settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun <T> SettingsChipRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}

private fun fileCountLabel(count: Int): String = "$count changed ${if (count == 1) "file" else "files"}"
