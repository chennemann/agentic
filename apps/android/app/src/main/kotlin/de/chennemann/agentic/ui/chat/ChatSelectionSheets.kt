package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChatSelectionSheets(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    when (state.activePicker) {
        ChatPickerUi.ENVIRONMENT -> {
            PickerSheet(
                title = "Environment",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.PairEnvironmentRequested) },
                ) {
                    Text("Pair another environment")
                }
                state.environmentPicker.environments.forEach { environment ->
                    PickerRow(
                        label = environment.label,
                        supportingText = environment.supportingText,
                        selected = environment.id == state.environmentPicker.selectedEnvironmentId,
                        trailingText = environment.connection.label(),
                        onClick = { onEvent(ChatUiEvent.EnvironmentSelected(environment.id)) },
                    )
                }
            }
        }

        ChatPickerUi.PROJECT_THREAD -> {
            PickerSheet(
                title = "Project and thread",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                Text("Project", style = MaterialTheme.typography.labelLarge)
                state.threadPicker.projects.forEach { project ->
                    PickerRow(
                        label = project.label,
                        supportingText = project.supportingText,
                        selected = project.id == state.threadPicker.selectedProjectId,
                        onClick = { onEvent(ChatUiEvent.ProjectSelected(project.id)) },
                    )
                }
                Text(
                    text = if (state.threadPicker.showArchived) "Threads, including archived" else "Threads",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
                state.threadPicker.threads.forEach { thread ->
                    PickerRow(
                        label = thread.title,
                        supportingText = thread.supportingText,
                        selected = thread.id == state.threadPicker.selectedThreadId,
                        trailingText = if (thread.archived) "Archived" else null,
                        onClick = { onEvent(ChatUiEvent.ThreadSelected(thread.id)) },
                    )
                }
            }
        }

        ChatPickerUi.PROVIDER_MODEL -> {
            PickerSheet(
                title = "Provider and model",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                state.composer.providerModels.forEach { option ->
                    PickerRow(
                        label = option.modelLabel,
                        supportingText = option.supportingText ?: option.providerLabel,
                        selected = option.id == state.composer.selectedProviderModelId,
                        onClick = { onEvent(ChatUiEvent.ProviderModelSelected(option.id)) },
                    )
                }
            }
        }

        ChatPickerUi.RUNTIME_MODE -> {
            PickerSheet(
                title = "Runtime mode",
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            ) {
                state.composer.runtimeModes.forEach { option ->
                    PickerRow(
                        label = option.label,
                        supportingText = option.description,
                        selected = option.id == state.composer.selectedRuntimeModeId,
                        onClick = { onEvent(ChatUiEvent.RuntimeModeSelected(option.id)) },
                    )
                }
            }
        }

        null -> Unit
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
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
