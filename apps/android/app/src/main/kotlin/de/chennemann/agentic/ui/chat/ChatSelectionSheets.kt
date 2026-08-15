package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import de.chennemann.agentic.domain.preferences.ThemePreference

@Composable
fun ChatSelectionSheets(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    when (state.activePicker) {
        ChatPickerUi.NAVIGATION -> {
            PickerSheet(
                title = "Navigation and settings",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                item("environment-heading") {
                    Text("Environment", style = MaterialTheme.typography.labelLarge)
                }
                item("environment") {
                    PickerRow(
                        label = state.environmentLabel,
                        supportingText = "Change active environment",
                        selected = false,
                        trailingText = state.environmentPicker.environments
                            .firstOrNull { it.id == state.environmentPicker.selectedEnvironmentId }
                            ?.connection
                            ?.label(),
                        onClick = {
                            onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.ENVIRONMENT))
                        },
                    )
                }
                item("manage-environments") {
                    TextButton(onClick = { onEvent(ChatUiEvent.PairEnvironmentRequested) }) {
                        Text("Manage environments")
                    }
                }
                state.environmentPicker.selectedEnvironmentId?.let { environmentId ->
                    item("inspect-cache") {
                        TextButton(onClick = { onEvent(ChatUiEvent.CacheInspectionRequested(environmentId)) }) { Text("Storage and cache") }
                    }
                }
                if (state.durableWork.isNotEmpty()) {
                    item("durable-work") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                item("conversation-heading") {
                    Text(
                        "Conversation",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item("conversation") {
                    PickerRow(
                        label = state.projectLabel ?: "Choose project",
                        supportingText = state.title,
                        selected = state.threadId != null,
                        onClick = {
                            onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
                        },
                    )
                }
                item("conversation-actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { onEvent(ChatUiEvent.NewThreadRequested) }) {
                            Text("New thread")
                        }
                        if (state.canRenameThread) {
                            TextButton(onClick = { onEvent(ChatUiEvent.RenameThreadRequested) }) {
                                Text("Rename")
                            }
                        }
                        if (state.isThreadArchived) {
                            TextButton(onClick = { onEvent(ChatUiEvent.UnarchiveThreadRequested) }) {
                                Text("Unarchive")
                            }
                        } else if (state.canArchiveThread) {
                            TextButton(onClick = { onEvent(ChatUiEvent.ArchiveThreadRequested) }) {
                                Text("Archive")
                            }
                        }
                        if (state.threadSnooze.supported) {
                            if (state.threadSnooze.isSnoozed) {
                                TextButton(
                                    enabled = state.threadSnooze.canWake,
                                    onClick = { onEvent(ChatUiEvent.ThreadWakeRequested) },
                                ) {
                                    Text(if (state.threadSnooze.actionInProgress) "Waking…" else "Wake")
                                }
                            } else {
                                TextButton(
                                    enabled = state.threadSnooze.canSnooze,
                                    onClick = {
                                        onEvent(
                                            ChatUiEvent.ThreadSnoozeRequested(
                                                Instant.now().plus(1, ChronoUnit.HOURS).toString(),
                                            ),
                                        )
                                    },
                                ) {
                                    Text(if (state.threadSnooze.actionInProgress) "Snoozing…" else "Snooze 1h")
                                }
                            }
                        }
                        if (state.canDeleteThread) {
                            TextButton(onClick = { onEvent(ChatUiEvent.DeleteThreadRequested) }) {
                                Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (state.threadSnooze.isSnoozed) {
                    item("snooze-status") {
                        Text(
                            text = "Snoozed until ${state.threadSnooze.snoozedUntil}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (state.threadSnooze.wakeReason != null) {
                    item("wake-status") {
                        Text(
                            text = "Woke because of ${state.threadSnooze.wakeReason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                state.threadSnooze.errorMessage?.let { error ->
                    item("snooze-error") {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
                item("models-heading") {
                    Text(
                        "Models",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item("keyboard-shortcuts") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Hardware keyboard", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Ctrl/⌘+Enter send · Shift+Enter newline · Esc stop · Ctrl/⌘+N new task · Ctrl/⌘+K navigation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item("appearance") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Appearance", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ThemePreference.entries) { theme ->
                                FilterChip(
                                    selected = state.interfaceSettings.theme == theme,
                                    onClick = { onEvent(ChatUiEvent.ThemeSelected(theme)) },
                                    label = { Text(theme.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                )
                            }
                        }
                        Text("Interface size", style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(0.9f, 1f, 1.15f)) { scale ->
                                FilterChip(state.interfaceSettings.interfaceScale == scale, { onEvent(ChatUiEvent.InterfaceScaleSelected(scale)) }, { Text("${(scale * 100).toInt()}%") })
                            }
                        }
                        Text("Code and Markdown size", style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(0.9f, 1f, 1.15f)) { scale ->
                                FilterChip(state.interfaceSettings.codeScale == scale, { onEvent(ChatUiEvent.CodeScaleSelected(scale)) }, { Text("${(scale * 100).toInt()}%") })
                            }
                        }
                    }
                }
                item("models") {
                    PickerRow(
                        label = "Models and favorites",
                        supportingText = "Choose models and manage composer shortcuts",
                        selected = false,
                        onClick = {
                            onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL))
                        },
                    )
                }
                item("voice-input-heading") {
                    Text(
                        "Voice input",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item("voice-input") {
                    PickerRow(
                        label = "Groq transcription",
                        supportingText = if (state.groqSettings.apiKeyConfigured) {
                            "API key configured"
                        } else {
                            "Set up an API key to enable the microphone"
                        },
                        selected = state.groqSettings.apiKeyConfigured,
                        onClick = { onEvent(ChatUiEvent.GroqSettingsRequested) },
                    )
                }
            }
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
            PickerSheet(
                title = "Project and thread",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
                expanded = true,
            ) {
                item("project-heading") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Project", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { onEvent(ChatUiEvent.ProjectCreationRequested) }) {
                            Text("Add project")
                        }
                    }
                }
                item("project-carousel") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = state.threadPicker.projects,
                            key = { "project:${it.id}" },
                        ) { project ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FilterChip(
                                    selected = project.id == state.threadPicker.selectedProjectId,
                                    onClick = { onEvent(ChatUiEvent.ProjectSelected(project.id)) },
                                    label = {
                                        Text(
                                            text = project.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                                TextButton(onClick = { onEvent(ChatUiEvent.ProjectRenameRequested(project.id)) }) {
                                    Text("Rename")
                                }
                                TextButton(onClick = { onEvent(ChatUiEvent.ProjectRemovalRequested(project.id)) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                state.threadPicker.projects
                    .firstOrNull { it.id == state.threadPicker.selectedProjectId }
                    ?.supportingText
                    ?.let { workspaceRoot ->
                        item("selected-project-path") {
                            Text(
                                text = workspaceRoot,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                if (state.threadPicker.projects.isEmpty()) {
                    item("no-projects") {
                        Text(
                            text = "No projects available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item("thread-heading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (state.threadPicker.showArchived) {
                                "Active threads, including archived"
                            } else {
                                "Active threads"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        TextButton(onClick = { onEvent(ChatUiEvent.NewThreadRequested) }) {
                            Icon(
                                imageVector = Icons.Add,
                                contentDescription = null,
                            )
                            Text("New thread")
                        }
                    }
                }
                item("archived-filter") {
                    FilterChip(
                        selected = state.threadPicker.showArchived,
                        onClick = {
                            onEvent(
                                ChatUiEvent.ArchivedThreadsVisibilityChanged(
                                    visible = !state.threadPicker.showArchived,
                                ),
                            )
                        },
                        label = { Text("Show archived") },
                    )
                }
                if (state.threadPicker.threads.isEmpty()) {
                    item("no-threads") {
                        Text(
                            text = "No active threads in this project",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(
                    items = state.threadPicker.threads,
                    key = { "thread:${it.id}" },
                ) { thread ->
                    ThreadPickerRow(
                        thread = thread,
                        selected = thread.id == state.threadPicker.selectedThreadId,
                        onClick = { onEvent(ChatUiEvent.ThreadSelected(thread.id)) },
                        onLifecycleClick = {
                            onEvent(
                                if (thread.archived) {
                                    ChatUiEvent.PickerThreadUnarchiveRequested(thread.id)
                                } else {
                                    ChatUiEvent.ThreadSettleRequested(thread.id)
                                },
                            )
                        },
                    )
                }
                item("settled-thread-heading") {
                    TextButton(
                        onClick = {
                            onEvent(
                                ChatUiEvent.SettledThreadsVisibilityChanged(
                                    visible = !state.threadPicker.showSettled,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Settled threads (${state.threadPicker.settledThreads.size})")
                            Text(if (state.threadPicker.showSettled) "Hide" else "Show")
                        }
                    }
                }
                if (state.threadPicker.showSettled && state.threadPicker.settledThreads.isEmpty()) {
                    item("no-settled-threads") {
                        Text(
                            text = "No settled threads in this project",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (state.threadPicker.showSettled) {
                    items(
                        items = state.threadPicker.settledThreads,
                        key = { "settled-thread:${it.id}" },
                    ) { thread ->
                        ThreadPickerRow(
                            thread = thread,
                            selected = thread.id == state.threadPicker.selectedThreadId,
                            onClick = { onEvent(ChatUiEvent.ThreadSelected(thread.id)) },
                            onLifecycleClick = {
                                onEvent(ChatUiEvent.ThreadUnsettleRequested(thread.id))
                            },
                            settled = true,
                        )
                    }
                }
            }
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

private fun EnvironmentConnectionIndicatorUi.label(): String = when (this) {
    EnvironmentConnectionIndicatorUi.LIVE -> "Live"
    EnvironmentConnectionIndicatorUi.CONNECTING -> "Connecting"
    EnvironmentConnectionIndicatorUi.CACHED -> "Cached"
    EnvironmentConnectionIndicatorUi.OFFLINE -> "Offline"
    EnvironmentConnectionIndicatorUi.BLOCKED -> "Blocked"
}

private fun fileCountLabel(count: Int): String = "$count changed ${if (count == 1) "file" else "files"}"
