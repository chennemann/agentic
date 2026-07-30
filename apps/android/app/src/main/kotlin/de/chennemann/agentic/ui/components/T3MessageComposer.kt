package de.chennemann.agentic.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Send
import de.chennemann.agentic.ui.chat.ChatPickerUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.chat.InteractionModeUi
import de.chennemann.agentic.ui.chat.ProjectQuickSwitchUi
import de.chennemann.agentic.ui.chat.ProviderModelOptionUi
import de.chennemann.agentic.ui.chat.ProviderOptionUi
import kotlin.math.roundToInt

private val ModeSwipeThreshold = 28.dp

@Composable
fun T3MessageComposer(
    state: ComposerUiState,
    turnRunning: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var commandMenuDismissed by remember { mutableStateOf(false) }
    val selectedModel = state.providerModels.firstOrNull {
        it.id == state.selectedProviderModelId
    }
    val selectedRuntimeMode = state.runtimeModes.firstOrNull {
        it.id == state.selectedRuntimeModeId
    }
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

    BackHandler(enabled = expanded) { expanded = false }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Column {
            Text(
                text = if (state.selectedInteractionMode == InteractionModeUi.PLAN) {
                    "Build · Plan"
                } else {
                    "Build"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(
                        enabled = state.enabled && !state.sending,
                        onClick = {
                            onEvent(
                                ChatUiEvent.InteractionModeSelected(
                                    state.selectedInteractionMode.next(),
                                ),
                            )
                        },
                    )
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 2.dp),
            )

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
                                    if (delta in -threshold..threshold) {
                                        return@detectHorizontalDragGestures
                                    }
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

            if (!expanded) {
                ComposerSummary(
                    selectedModel = selectedModel,
                    providerOptions = state.providerOptions,
                    accessLabel = selectedRuntimeMode?.label ?: "Access",
                    enabled = state.enabled,
                    onExpand = { expanded = true },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                ExpandedControls(
                    state = state,
                    selectedModel = selectedModel,
                    onEvent = onEvent,
                    onCollapse = { expanded = false },
                )
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

@Composable
private fun ComposerSummary(
    selectedModel: ProviderModelOptionUi?,
    providerOptions: List<ProviderOptionUi>,
    accessLabel: String,
    enabled: Boolean,
    onExpand: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onExpand)
            .semantics { contentDescription = "Open model, reasoning, and access controls" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = selectedModel?.modelLabel ?: "Choose model",
            style = MaterialTheme.typography.labelMedium,
        )
        providerOptions.forEach { option ->
            Text(
                text = when (option) {
                    is ProviderOptionUi.Select -> option.values
                        .firstOrNull { it.id == option.selectedValueId }
                        ?.label
                        ?: option.label

                    is ProviderOptionUi.Toggle -> "${option.label}: ${if (option.selected) "On" else "Off"}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = accessLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandedControls(
    state: ComposerUiState,
    selectedModel: ProviderModelOptionUi?,
    onEvent: (ChatUiEvent) -> Unit,
    onCollapse: () -> Unit,
) {
    val favorites = state.providerModels
        .filter(ProviderModelOptionUi::favorite)
        .sortedBy { it.favoriteOrder }
    val shortcuts = favorites.ifEmpty { listOfNotNull(selectedModel) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCollapse)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Model selection", style = MaterialTheme.typography.labelLarge)
            Text("Collapse", style = MaterialTheme.typography.labelMedium)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shortcuts.forEach { model ->
                FilterChip(
                    selected = model.id == state.selectedProviderModelId,
                    onClick = { onEvent(ChatUiEvent.ProviderModelSelected(model.id)) },
                    enabled = state.enabled,
                    label = {
                        Text(
                            text = model.modelLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        TextButton(
            onClick = {
                onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL))
            },
            enabled = state.providerModels.isNotEmpty(),
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("More models")
        }

        state.providerOptions.forEach { option ->
            HorizontalDivider()
            when (option) {
                is ProviderOptionUi.Select -> SelectProviderOption(option, state.enabled, onEvent)
                is ProviderOptionUi.Toggle -> ToggleProviderOption(option, state.enabled, onEvent)
            }
        }

        HorizontalDivider()
        Text("Access", style = MaterialTheme.typography.labelLarge)
        state.runtimeModes.forEach { mode ->
            val selected = mode.id == state.selectedRuntimeModeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        enabled = state.enabled,
                        role = Role.RadioButton,
                        onClick = { onEvent(ChatUiEvent.RuntimeModeSelected(mode.id)) },
                    )
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = state.enabled,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(mode.label)
                    mode.description?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectProviderOption(
    option: ProviderOptionUi.Select,
    enabled: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val selectedIndex = option.values
        .indexOfFirst { it.id == option.selectedValueId }
        .coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(option.label, style = MaterialTheme.typography.labelLarge)
        option.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (option.values.size == 1) {
            Text(option.values.single().label)
        } else {
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = {
                    val index = it.roundToInt().coerceIn(option.values.indices)
                    onEvent(
                        ChatUiEvent.ProviderSelectOptionSelected(
                            optionId = option.id,
                            valueId = option.values[index].id,
                        ),
                    )
                },
                enabled = enabled,
                valueRange = 0f..option.values.lastIndex.toFloat(),
                steps = (option.values.size - 2).coerceAtLeast(0),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                option.values.forEachIndexed { index, value ->
                    Text(
                        text = value.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == selectedIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleProviderOption(
    option: ProviderOptionUi.Toggle,
    enabled: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.labelLarge)
            option.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = option.selected,
            onCheckedChange = {
                onEvent(ChatUiEvent.ProviderBooleanOptionChanged(option.id, it))
            },
            enabled = enabled,
        )
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
