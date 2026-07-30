package de.chennemann.agentic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.ui.chat.ChatPickerUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.chat.InteractionModeUi
import de.chennemann.agentic.ui.chat.ProjectQuickSwitchUi
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Send

private val ModeSwipeThreshold = 28.dp

@Composable
fun T3MessageComposer(
    state: ComposerUiState,
    turnRunning: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commandMenuDismissed by remember { mutableStateOf(false) }
    val commandQuery = state.draft
        .takeIf { it.startsWith("/") }
        ?.drop(1)
        ?.substringBefore(' ')
        .orEmpty()
    val matchingCommands = if (commandQuery.isBlank() && !state.draft.startsWith("/")) {
        emptyList()
    } else {
        state.slashCommands.filter { it.name.startsWith(commandQuery, ignoreCase = true) }
    }
    val selectedProviderModel = state.providerModels.firstOrNull {
        it.id == state.selectedProviderModelId
    }
    val selectedRuntimeMode = state.runtimeModes.firstOrNull {
        it.id == state.selectedRuntimeModeId
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InteractionModeUi.entries.forEach { mode ->
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (state.selectedInteractionMode == mode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        modifier = Modifier.selectable(
                            selected = state.selectedInteractionMode == mode,
                            enabled = state.enabled && !state.sending,
                            role = Role.Tab,
                            onClick = { onEvent(ChatUiEvent.InteractionModeSelected(mode)) },
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = {
                        onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL))
                    },
                    enabled = state.enabled && state.providerModels.isNotEmpty(),
                    label = {
                        Text(
                            text = selectedProviderModel?.let {
                                "${it.providerLabel} • ${it.modelLabel}"
                            } ?: "Provider / model",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                if (state.runtimeModes.isNotEmpty()) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.RUNTIME_MODE))
                        },
                        enabled = state.enabled,
                        label = {
                            Text(
                                text = selectedRuntimeMode?.label ?: "Runtime",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = state.draft,
                    onValueChange = {
                        commandMenuDismissed = false
                        onEvent(ChatUiEvent.DraftChanged(it))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 88.dp)
                        .pointerInput(
                            state.selectedInteractionMode,
                            state.enabled,
                            state.sending,
                        ) {
                            if (!state.enabled || state.sending) return@pointerInput
                            val threshold = ModeSwipeThreshold.toPx()
                            var delta = 0f
                            var changed = false
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    delta = 0f
                                    changed = false
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    if (changed) return@detectHorizontalDragGestures
                                    delta += dragAmount
                                    if (delta in -threshold..threshold) return@detectHorizontalDragGestures
                                    changed = true
                                    onEvent(
                                        ChatUiEvent.InteractionModeSelected(
                                            state.selectedInteractionMode.next(),
                                        ),
                                    )
                                },
                            )
                        },
                    enabled = state.enabled,
                    placeholder = { Text("Message") },
                    maxLines = 12,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )

                DropdownMenu(
                    expanded = matchingCommands.isNotEmpty() && !commandMenuDismissed,
                    onDismissRequest = { commandMenuDismissed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    matchingCommands.forEach { command ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("/${command.name}")
                                    command.description?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                commandMenuDismissed = true
                                onEvent(ChatUiEvent.SlashCommandSelected(command.name))
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (turnRunning) {
                        OutlinedButton(
                            onClick = { onEvent(ChatUiEvent.TurnInterruptRequested) },
                            enabled = state.enabled && !state.sending,
                            shape = CircleShape,
                        ) {
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { onEvent(ChatUiEvent.MessageSubmitted) },
                            enabled = state.enabled && state.draft.isNotBlank() && !state.sending,
                            shape = CircleShape,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            if (state.sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Send,
                                    contentDescription = "Send message",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.quickSwitchProjects.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 16.dp,
                        alignment = Alignment.CenterHorizontally,
                    ),
                ) {
                    items(
                        items = state.quickSwitchProjects,
                        key = { it.id },
                    ) { project ->
                        ProjectQuickSwitchButton(
                            project = project,
                            onClick = {
                                onEvent(ChatUiEvent.ProjectQuickSwitchRequested(project.id))
                            },
                            onLongClick = {
                                onEvent(ChatUiEvent.ProjectThreadsRequested(project.id))
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectQuickSwitchButton(
    project: ProjectQuickSwitchUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val indicatorSize = 44.dp + with(LocalDensity.current) { 1.toDp() }
    val container = if (project.active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (project.active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier.size(indicatorSize),
            contentAlignment = Alignment.Center,
        ) {
            if (project.processing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(indicatorSize),
                    strokeWidth = 2.dp,
                )
            } else if (project.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(indicatorSize)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ),
                )
            }
            Surface(
                shape = CircleShape,
                color = container,
                contentColor = content,
                modifier = Modifier
                    .size(36.dp)
                    .semantics {
                        contentDescription = buildString {
                            append("Project ${project.title}")
                            if (project.unreadCount > 0) {
                                append(", ${project.unreadCount} unread")
                            }
                        }
                    }
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    ),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(project.label)
                }
            }
        }
        Box(
            modifier = Modifier.height(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (project.attentionCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(minOf(project.attentionCount, 6)) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

private fun InteractionModeUi.next(): InteractionModeUi = when (this) {
    InteractionModeUi.DEFAULT -> InteractionModeUi.PLAN
    InteractionModeUi.PLAN -> InteractionModeUi.DEFAULT
}
