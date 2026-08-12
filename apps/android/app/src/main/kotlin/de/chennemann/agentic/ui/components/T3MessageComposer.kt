package de.chennemann.agentic.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Brain
import de.chennemann.agentic.icons.Flame
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Lock
import de.chennemann.agentic.icons.Microphone
import de.chennemann.agentic.icons.Pencil
import de.chennemann.agentic.icons.Send
import de.chennemann.agentic.icons.Sparkles
import de.chennemann.agentic.icons.Stop
import de.chennemann.agentic.icons.Tune
import de.chennemann.agentic.icons.Unlock
import de.chennemann.agentic.ui.chat.ChatPickerUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.HardwareKeyboardBindings
import de.chennemann.agentic.ui.chat.KeyboardAction
import de.chennemann.agentic.ui.chat.KeyboardFocus
import de.chennemann.agentic.ui.chat.toChatUiEvent
import de.chennemann.agentic.ui.chat.toHardwareKeyStroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import de.chennemann.agentic.ui.chat.ComposerUiState
import de.chennemann.agentic.ui.chat.ProjectQuickSwitchUi
import de.chennemann.agentic.ui.chat.ProviderModelOptionUi
import de.chennemann.agentic.ui.chat.ProviderOptionUi
import de.chennemann.agentic.ui.chat.ProviderOptionValueUi
import de.chennemann.agentic.ui.chat.RuntimeModeOptionUi
import de.chennemann.agentic.ui.chat.SessionTerminationUi
import de.chennemann.agentic.ui.chat.VoiceInputStatusUi
import kotlin.math.roundToInt

private val ComposerBackground = Color.Black
private val ComposerContent = Color.White
private val ComposerMuted = Color(0xFFB8B8B8)
private val ComposerDisabled = Color(0xFF686868)
private val ComposerSelected = Color(0xFF202020)
private val SectionContentIndent = 14.dp

@Composable
fun T3MessageComposer(
    state: ComposerUiState,
    turnRunning: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    sessionTermination: SessionTerminationUi = SessionTerminationUi(),
    inputFocusRequested: Boolean? = null,
    onInputFocusedManually: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var commandMenuDismissed by remember { mutableStateOf(false) }
    val inputInteractionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val requestMicrophonePermission = rememberMicrophonePermissionRequest(
        onGranted = { onEvent(ChatUiEvent.VoiceInputPressed) },
        onDenied = { onEvent(ChatUiEvent.MicrophonePermissionDenied) },
    )
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
    val visibleProviderOptions = state.providerOptions.filterNot(ProviderOptionUi::isServiceMode)
    val voiceControlVisible = state.voiceInputAvailable &&
        (state.draft.isBlank() || state.voiceInputStatus != VoiceInputStatusUi.IDLE)

    VoiceRecordingLifecycleEffect(
        status = state.voiceInputStatus,
        onRecordingCancelled = { onEvent(ChatUiEvent.VoiceInputCancelled) },
    )
    BackHandler(enabled = expanded) { expanded = false }
    LaunchedEffect(inputInteractionSource) {
        inputInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                expanded = false
                onInputFocusedManually()
            }
        }
    }
    LaunchedEffect(inputFocusRequested, state.enabled) {
        when {
            inputFocusRequested == true && state.enabled -> {
                inputFocusRequester.requestFocus()
                keyboardController?.show()
            }

            inputFocusRequested == false -> {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ComposerBackground,
        contentColor = ComposerContent,
    ) {
        Column {
            Text(
                text = "Build",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 2.dp),
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
                        .focusRequester(inputFocusRequester)
                        .testTag(ComposerInputTestTag)
                        .onPreviewKeyEvent { keyEvent ->
                            val stroke = keyEvent.toHardwareKeyStroke(KeyboardFocus.COMPOSER)
                                ?: return@onPreviewKeyEvent false
                            val action = HardwareKeyboardBindings.map(
                                stroke,
                                canSend = state.enabled && !state.sending && state.draft.isNotBlank(),
                                turnRunning = turnRunning,
                            ) ?: return@onPreviewKeyEvent false
                            if (action == KeyboardAction.NEWLINE) return@onPreviewKeyEvent false
                            action.toChatUiEvent()?.let(onEvent)
                            true
                        },
                    enabled = state.enabled &&
                        state.voiceInputStatus == VoiceInputStatusUi.IDLE,
                    placeholder = {
                        Text(
                            when (state.voiceInputStatus) {
                                VoiceInputStatusUi.RECORDING -> "Recording…"
                                VoiceInputStatusUi.TRANSCRIBING -> "Transcribing…"
                                VoiceInputStatusUi.IDLE -> "Message"
                            },
                        )
                    },
                    interactionSource = inputInteractionSource,
                    maxLines = 12,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedTextColor = ComposerContent,
                        unfocusedTextColor = ComposerContent,
                        disabledTextColor = ComposerDisabled,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedPlaceholderColor = ComposerMuted,
                        unfocusedPlaceholderColor = ComposerMuted,
                        disabledPlaceholderColor = ComposerDisabled,
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
                    if (state.draft.isNotBlank() && !state.sending) {
                        TextButton(onClick = { onEvent(ChatUiEvent.DraftDiscardRequested) }) {
                            Text("Discard")
                        }
                    }
                    if (
                        sessionTermination.canTerminate ||
                        sessionTermination.terminating ||
                        sessionTermination.errorMessage != null
                    ) {
                        OutlinedButton(
                            onClick = { onEvent(ChatUiEvent.SessionTerminationRequested) },
                            enabled = sessionTermination.canTerminate && !sessionTermination.terminating,
                        ) {
                            Text(
                                when {
                                    sessionTermination.terminating -> "Terminating…"
                                    sessionTermination.errorMessage != null -> "Retry session stop"
                                    else -> "Terminate session"
                                },
                            )
                        }
                    }
                    if (turnRunning) {
                        OutlinedButton(
                            onClick = { onEvent(ChatUiEvent.TurnInterruptRequested) },
                            enabled = state.enabled && !state.sending,
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                                disabledContentColor = ComposerDisabled,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        ) {
                            Text("Stop")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (!voiceControlVisible) {
                                    onEvent(ChatUiEvent.MessageSubmitted)
                                } else if (
                                    state.voiceInputStatus == VoiceInputStatusUi.RECORDING
                                ) {
                                    onEvent(ChatUiEvent.VoiceInputPressed)
                                } else {
                                    requestMicrophonePermission()
                                }
                            },
                            enabled = state.enabled &&
                                !state.sending &&
                                if (voiceControlVisible) {
                                    state.voiceInputStatus != VoiceInputStatusUi.TRANSCRIBING
                                } else {
                                    state.draft.isNotBlank()
                                },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (
                                    state.voiceInputStatus == VoiceInputStatusUi.RECORDING
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                disabledContentColor = ComposerDisabled,
                            ),
                        ) {
                            if (
                                state.sending ||
                                state.voiceInputStatus == VoiceInputStatusUi.TRANSCRIBING
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else if (voiceControlVisible) {
                                Icon(
                                    imageVector = if (
                                        state.voiceInputStatus == VoiceInputStatusUi.RECORDING
                                    ) {
                                        Icons.Stop
                                    } else {
                                        Icons.Microphone
                                    },
                                    contentDescription = if (
                                        state.voiceInputStatus == VoiceInputStatusUi.RECORDING
                                    ) {
                                        "Stop recording and transcribe"
                                    } else {
                                        "Start voice input"
                                    },
                                    modifier = Modifier.size(28.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Send,
                                    contentDescription = "Send message",
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
                sessionTermination.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (sessionTermination.terminal) {
                    Text("Session stopped", color = ComposerMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!expanded) {
                ComposerSummary(
                    selectedModel = selectedModel,
                    providerOptions = visibleProviderOptions,
                    selectedRuntimeMode = selectedRuntimeMode,
                    enabled = state.enabled,
                    onExpand = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        expanded = true
                    },
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
                    providerOptions = visibleProviderOptions,
                    onEvent = onEvent,
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

const val ComposerInputTestTag = "composer-input"

@Composable
private fun ComposerSummary(
    selectedModel: ProviderModelOptionUi?,
    providerOptions: List<ProviderOptionUi>,
    selectedRuntimeMode: RuntimeModeOptionUi?,
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
        SummaryItem(
            icon = Icons.Brain,
            contentDescription = "Model ${selectedModel?.modelLabel ?: "not selected"}",
            label = selectedModel?.modelLabel ?: "Choose model",
        )
        providerOptions.forEach { option ->
            val valueLabel = when (option) {
                is ProviderOptionUi.Select -> option.values
                    .firstOrNull { it.id == option.selectedValueId }
                    ?.displayLabel()
                    ?: option.label

                is ProviderOptionUi.Toggle -> if (option.selected) "On" else "Off"
            }
            SummaryItem(
                icon = option.icon(),
                contentDescription = "${option.label}: $valueLabel",
            )
        }
        SummaryItem(
            icon = selectedRuntimeMode?.icon() ?: Icons.Lock,
            contentDescription = "Access: ${selectedRuntimeMode?.label ?: "not selected"}",
        )
    }
}

@Composable
private fun SummaryItem(
    icon: ImageVector,
    contentDescription: String,
    label: String? = null,
) {
    Row(
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = ComposerContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExpandedControls(
    state: ComposerUiState,
    selectedModel: ProviderModelOptionUi?,
    providerOptions: List<ProviderOptionUi>,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val favorites = state.providerModels
        .filter(ProviderModelOptionUi::favorite)
        .sortedBy { it.favoriteOrder }
    val shortcuts = favorites.ifEmpty { listOfNotNull(selectedModel) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ModelSelectionSection(
            shortcuts = shortcuts,
            selectedModelId = state.selectedProviderModelId,
            enabled = state.enabled,
            hasModels = state.providerModels.isNotEmpty(),
            onEvent = onEvent,
        )

        providerOptions.forEach { option ->
            when (option) {
                is ProviderOptionUi.Select -> SelectProviderOption(option, state.enabled, onEvent)
                is ProviderOptionUi.Toggle -> ToggleProviderOption(option, state.enabled, onEvent)
            }
        }

        AccessSection(state = state, onEvent = onEvent)
    }
}

@Composable
private fun ModelSelectionSection(
    shortcuts: List<ProviderModelOptionUi>,
    selectedModelId: String?,
    enabled: Boolean,
    hasModels: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column {
        SectionHeading(icon = Icons.Brain, label = "Model Selection")
        Column(
            modifier = Modifier.padding(start = SectionContentIndent, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shortcuts.forEach { model ->
                    val selected = model.id == selectedModelId
                    OutlinedButton(
                        onClick = { onEvent(ChatUiEvent.ProviderModelSelected(model.id)) },
                        enabled = enabled,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                ComposerContent
                            },
                            disabledContentColor = ComposerDisabled,
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                ComposerMuted
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Brain,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = model.modelLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            TextButton(
                onClick = {
                    onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL))
                },
                enabled = hasModels,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = ComposerDisabled,
                ),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("More models")
            }
        }
    }
}

@Composable
private fun AccessSection(
    state: ComposerUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column {
        SectionHeading(icon = Icons.Lock, label = "Access")
        Column(
            modifier = Modifier.padding(start = SectionContentIndent, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.runtimeModes.forEach { mode ->
                val selected = mode.id == state.selectedRuntimeModeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .selectable(
                            selected = selected,
                            enabled = state.enabled,
                            role = Role.RadioButton,
                            onClick = { onEvent(ChatUiEvent.RuntimeModeSelected(mode.id)) },
                        )
                        .semantics { contentDescription = "Access mode ${mode.label}" }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = mode.icon(),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(22.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            ComposerMuted
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.label,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                ComposerMuted
                            },
                        )
                        mode.description?.takeIf { selected }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                    alpha = 0.8f,
                                ),
                            )
                        }
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
    Column {
        SectionHeading(icon = option.icon(), label = option.label)
        Column(
            modifier = Modifier.padding(start = SectionContentIndent, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            option.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ComposerMuted,
                )
            }
            if (option.values.size == 1) {
                Text(
                    text = option.values.single().displayLabel(),
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = ComposerDisabled,
                        activeTickColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveTickColor = ComposerMuted,
                        disabledThumbColor = ComposerDisabled,
                        disabledActiveTrackColor = ComposerDisabled,
                        disabledInactiveTrackColor = ComposerSelected,
                    ),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    option.values.forEachIndexed { index, value ->
                        Text(
                            text = value.displayLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == selectedIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                ComposerMuted
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
}

@Composable
private fun ToggleProviderOption(
    option: ProviderOptionUi.Toggle,
    enabled: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column {
        SectionHeading(icon = option.icon(), label = option.label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SectionContentIndent, top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            option.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ComposerMuted,
                    modifier = Modifier.weight(1f),
                )
            }
            Switch(
                checked = option.selected,
                onCheckedChange = {
                    onEvent(ChatUiEvent.ProviderBooleanOptionChanged(option.id, it))
                },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = ComposerMuted,
                    uncheckedTrackColor = ComposerSelected,
                    uncheckedBorderColor = ComposerMuted,
                    disabledCheckedThumbColor = ComposerDisabled,
                    disabledCheckedTrackColor = ComposerSelected,
                    disabledUncheckedThumbColor = ComposerDisabled,
                    disabledUncheckedTrackColor = ComposerSelected,
                    disabledUncheckedBorderColor = ComposerDisabled,
                ),
            )
        }
    }
}

@Composable
private fun SectionHeading(
    icon: ImageVector,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = ComposerContent,
        )
    }
}

private fun ProviderOptionValueUi.displayLabel(): String =
    if (
        id.filter(Char::isLetterOrDigit).equals("xhigh", ignoreCase = true) ||
        label.trim().equals("extra high", ignoreCase = true)
    ) {
        "xhigh"
    } else {
        label
    }

private fun ProviderOptionUi.icon(): ImageVector =
    if (normalizedLabel().contains("reason")) Icons.Flame else Icons.Tune

private fun RuntimeModeOptionUi.icon(): ImageVector = when (id) {
    "approval-required" -> Icons.Lock
    "auto-accept-edits" -> Icons.Pencil
    "auto" -> Icons.Sparkles
    "full-access" -> Icons.Unlock
    else -> Icons.Lock
}

private fun ProviderOptionUi.isServiceMode(): Boolean {
    val normalizedId = id.filter(Char::isLetterOrDigit).lowercase()
    if (normalizedId in ServiceModeOptionIds) return true
    if (normalizedLabel() in ServiceModeLabels) return true
    return this is ProviderOptionUi.Select &&
        values.mapTo(linkedSetOf()) { it.id.lowercase() } == ServiceModeValues
}

private fun ProviderOptionUi.normalizedLabel(): String = label.trim().lowercase()

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
        ComposerSelected
    }
    val content = if (project.active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        ComposerContent
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
                    color = MaterialTheme.colorScheme.primary,
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

private val ServiceModeOptionIds = setOf("fastmode", "servicemode", "servicetier")
private val ServiceModeLabels = setOf("fast mode", "service mode", "service tier")
private val ServiceModeValues = setOf("default", "fast")
