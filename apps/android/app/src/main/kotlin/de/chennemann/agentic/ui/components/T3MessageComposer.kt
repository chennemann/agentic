package de.chennemann.agentic.ui.components

import de.chennemann.agentic.domain.preferences.FollowUpBehavior
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.Brain
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.Flame
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Microphone
import de.chennemann.agentic.icons.Paperclip
import de.chennemann.agentic.icons.Send
import de.chennemann.agentic.icons.Stop
import de.chennemann.agentic.icons.TextAlignStart
import de.chennemann.agentic.icons.Tune
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
import de.chennemann.agentic.ui.chat.VoiceInputStatusUi
import android.graphics.BitmapFactory
import android.util.Base64

private val ComposerBackground = Color.Black
private val ComposerContent = Color.White
private val ComposerMuted = Color(0xFFB8B8B8)
private val ComposerDisabled = Color(0xFF686868)
private val ComposerSelected = Color(0xFF202020)

/**
 * The composer is the navigation hub of the chat screen. Top to bottom: the project › thread
 * context line, message input with bottom-aligned actions, run controls summary (model and
 * provider options), and the project quick-switch row framed by the drawer handle and new thread.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun T3MessageComposer(
    state: ComposerUiState,
    turnRunning: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    projectLabel: String? = null,
    threadTitle: String? = null,
    threadsOpen: Boolean = false,
    onOpenProjectPicker: () -> Unit = {
        onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
    },
    onOpenThreads: () -> Unit = {
        onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD))
    },
    inputFocusRequested: Boolean? = null,
    onInputFocusedManually: () -> Unit = {},
) {
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var commandMenuDismissed by remember { mutableStateOf(false) }
    val inputInteractionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val requestMicrophonePermission = rememberMicrophonePermissionRequest(
        onGranted = { onEvent(ChatUiEvent.VoiceInputPressed) },
        onDenied = { onEvent(ChatUiEvent.MicrophonePermissionDenied) },
    )
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) onEvent(ChatUiEvent.ImagesSelected(uris.map(Any::toString)))
    }
    val selectedModel = state.providerModels.firstOrNull {
        it.id == state.selectedProviderModelId
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
    val hasMessage = state.draft.isNotBlank() || state.imageAttachments.isNotEmpty()
    val showStop = turnRunning && !hasMessage
    val voiceControlVisible = state.voiceInputAvailable &&
        (state.draft.isBlank() && state.imageAttachments.isEmpty() || state.voiceInputStatus != VoiceInputStatusUi.IDLE)

    VoiceRecordingLifecycleEffect(
        status = state.voiceInputStatus,
        onRecordingCancelled = { onEvent(ChatUiEvent.VoiceInputCancelled) },
    )
    BackHandler(enabled = controlsOpen) { controlsOpen = false }
    LaunchedEffect(inputInteractionSource) {
        inputInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                controlsOpen = false
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
            ContextLine(
                projectLabel = projectLabel,
                threadTitle = threadTitle,
                open = threadsOpen,
                onToggleThreads = onOpenThreads,
            )
            if (state.imageAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.imageAttachments, key = { it.id }) { attachment ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            attachment.previewDataUrl?.let { dataUrl -> AttachmentPreview(dataUrl, attachment.name) }
                            TextButton(onClick = { onEvent(ChatUiEvent.ImageRemoved(attachment.id)) }) {
                                Text("Remove", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                state.queuedMessages.forEach { message ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            if (message.paused) "Queued (paused)" else "Queued",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(message.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (message.error != null) {
                            Text(message.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onEvent(ChatUiEvent.QueuedMessageRemoved(message.id)) }) {
                                Text("Dismiss")
                            }
                        } else {
                            Row {
                                TextButton(onClick = { onEvent(ChatUiEvent.QueuedMessageSendRequested(message.id)) }) {
                                    Text("Send now")
                                }
                                TextButton(onClick = { onEvent(ChatUiEvent.QueuedMessageRemoved(message.id)) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                TextField(
                    value = state.draft,
                    onValueChange = {
                        commandMenuDismissed = false
                        onEvent(ChatUiEvent.DraftChanged(it))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .testTag(ComposerInputTestTag)
                        .onPreviewKeyEvent { keyEvent ->
                            val stroke = keyEvent.toHardwareKeyStroke(KeyboardFocus.COMPOSER)
                                ?: return@onPreviewKeyEvent false
                            val action = HardwareKeyboardBindings.map(
                                stroke,
                                canSend = state.enabled &&
                                    !state.sending &&
                                    (state.draft.isNotBlank() || state.imageAttachments.isNotEmpty()),
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

                }
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = state.enabled && !state.sending && state.imageAttachments.size < 8,
                    ) {
                        Icon(
                            imageVector = Icons.Paperclip,
                            contentDescription = "Attach images",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (turnRunning && hasMessage) {
                        IconButton(onClick = { onEvent(ChatUiEvent.TurnInterruptRequested) }, enabled = state.enabled) {
                            Icon(Icons.Stop, contentDescription = "Stop turn", modifier = Modifier.size(24.dp))
                        }
                    }
                    val actionEnabled = state.enabled && (showStop || !state.sending &&
                        if (voiceControlVisible) state.voiceInputStatus != VoiceInputStatusUi.TRANSCRIBING else hasMessage)
                    val steerDefault = state.followUpBehavior == FollowUpBehavior.STEER
                    val actionLabel = when {
                        showStop -> "Stop turn"
                        hasMessage && turnRunning -> if (steerDefault) "Steer message" else "Queue message"
                        hasMessage -> "Send message"
                        state.voiceInputStatus == VoiceInputStatusUi.RECORDING -> "Stop recording and transcribe"
                        else -> "Start voice input"
                    }
                    val actionColor = when {
                        !actionEnabled -> ComposerDisabled
                        state.voiceInputStatus == VoiceInputStatusUi.RECORDING -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = actionLabel }
                            .combinedClickable(
                                enabled = actionEnabled,
                                role = Role.Button,
                                onLongClickLabel = if (turnRunning && hasMessage) {
                                    if (steerDefault) "Queue message" else "Steer message"
                                } else null,
                                onLongClick = if (turnRunning && hasMessage) {
                                    { onEvent(ChatUiEvent.AlternateMessageSubmitted) }
                                } else null,
                                onClick = {
                                    when {
                                        showStop -> onEvent(ChatUiEvent.TurnInterruptRequested)
                                        !voiceControlVisible -> onEvent(ChatUiEvent.MessageSubmitted)
                                        state.voiceInputStatus == VoiceInputStatusUi.RECORDING ->
                                            onEvent(ChatUiEvent.VoiceInputPressed)
                                        else -> requestMicrophonePermission()
                                    }
                                },
                            ),
                    ) {
                        when {
                            showStop -> Icon(Icons.Stop, null, Modifier.size(28.dp), tint = actionColor)
                            state.sending || state.voiceInputStatus == VoiceInputStatusUi.TRANSCRIBING ->
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            voiceControlVisible -> Icon(
                                imageVector = if (state.voiceInputStatus == VoiceInputStatusUi.RECORDING) {
                                    Icons.Stop
                                } else Icons.Microphone,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = actionColor,
                            )
                            else -> Icon(Icons.Send, null, Modifier.size(24.dp), tint = actionColor)
                        }
                    }
                }
            }

            AnimatedContent(targetState = controlsOpen, label = "composer controls") { open ->
                if (open) {
                    RunControlStrip(
                        state = state,
                        selectedModel = selectedModel,
                        onEvent = onEvent,
                        onClose = { controlsOpen = false },
                    )
                } else {
                    ComposerSummary(
                        selectedModel = selectedModel,
                        providerOptions = state.providerOptions,
                        enabled = state.enabled,
                        onExpand = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            controlsOpen = true
                        },
                    )
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

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                IconButton(
                    onClick = onOpenProjectPicker,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-24).dp, y = (-1.5).dp),
                ) {
                    Icon(
                        imageVector = Icons.TextAlignStart,
                        contentDescription = "Open project picker",
                    )
                }
                IconButton(
                    onClick = { onEvent(ChatUiEvent.NewThreadRequested) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (-1.5).dp),
                ) {
                    Icon(Icons.Add, contentDescription = "New thread", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** `project › thread ▾`; tapping it toggles the thread panel that slides out of the composer. */
@Composable
private fun ContextLine(
    projectLabel: String?,
    threadTitle: String?,
    open: Boolean,
    onToggleThreads: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (open) 180f else 0f, label = "threads chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleThreads)
                .semantics { contentDescription = if (open) "Close threads" else "Open threads" }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = projectLabel ?: "Choose project",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (threadTitle != null) {
                Text("  ›  ", style = MaterialTheme.typography.labelMedium, color = ComposerMuted)
                Text(
                    text = threadTitle,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Icon(
                imageVector = Icons.ChevronDown,
                contentDescription = null,
                tint = ComposerMuted,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(chevronRotation),
            )
        }
    }
}

@Composable
private fun AttachmentPreview(dataUrl: String, name: String) {
    val bitmap = remember(dataUrl) {
        runCatching {
            val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            modifier = Modifier.size(88.dp),
        )
    }
}

const val ComposerInputTestTag = "composer-input"

@Composable
private fun ComposerSummary(
    selectedModel: ProviderModelOptionUi?,
    providerOptions: List<ProviderOptionUi>,
    enabled: Boolean,
    onExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onExpand)
            .semantics { contentDescription = "Open model controls" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryItem(
            icon = Icons.Brain,
            contentDescription = "Model ${selectedModel?.modelLabel ?: "not selected"}",
            label = selectedModel?.modelLabel ?: "Choose model",
        )
        providerOptions.forEach { option ->
            val valueLabel = when (option) {
                is ProviderOptionUi.Select -> option.selectedLabel()
                is ProviderOptionUi.Toggle -> if (option.selected) "On" else "Off"
            }
            SummaryItem(
                icon = option.icon(),
                contentDescription = "${option.label}: $valueLabel",
            )
        }
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

/**
 * One row of chips in place of the summary; each chip opens a dropdown anchored to itself so
 * nothing covers the conversation.
 */
@Composable
private fun RunControlStrip(
    state: ComposerUiState,
    selectedModel: ProviderModelOptionUi?,
    onEvent: (ChatUiEvent) -> Unit,
    onClose: () -> Unit,
) {
    val favorites = state.providerModels
        .filter(ProviderModelOptionUi::favorite)
        .sortedBy { it.favoriteOrder }
        .ifEmpty { listOfNotNull(selectedModel) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StripChip(
            icon = Icons.Brain,
            label = selectedModel?.modelLabel ?: "Model",
            contentDescription = "Model ${selectedModel?.modelLabel ?: "not selected"}",
            enabled = state.enabled && state.providerModels.isNotEmpty(),
        ) { close ->
            favorites.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.modelLabel) },
                    onClick = { close(); onEvent(ChatUiEvent.ProviderModelSelected(model.id)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("All models…") },
                onClick = { close(); onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL)) },
            )
        }
        state.providerOptions.forEach { option ->
            when (option) {
                is ProviderOptionUi.Select -> StripChip(
                    icon = option.icon(),
                    label = option.selectedLabel(),
                    contentDescription = "${option.label}: ${option.selectedLabel()}",
                    enabled = state.enabled,
                ) { close ->
                    option.values.forEach { value ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(value.displayLabel())
                                    value.description?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            onClick = { close(); onEvent(ChatUiEvent.ProviderSelectOptionSelected(option.id, value.id)) },
                        )
                    }
                }

                is ProviderOptionUi.Toggle -> Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ComposerSelected)
                        .clickable(enabled = state.enabled) {
                            onEvent(ChatUiEvent.ProviderBooleanOptionChanged(option.id, !option.selected))
                        }
                        .semantics { contentDescription = "${option.label}: ${if (option.selected) "On" else "Off"}" }
                        .padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(option.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Switch(
                        checked = option.selected,
                        onCheckedChange = null,
                        enabled = state.enabled,
                        modifier = Modifier.size(width = 36.dp, height = 20.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = ComposerMuted,
                            uncheckedTrackColor = ComposerBackground,
                            uncheckedBorderColor = ComposerMuted,
                        ),
                    )
                }
            }
        }
        if (state.workspaceChoices.isNotEmpty()) {
            val workspace = state.workspaceChoices.firstOrNull { it.id == state.selectedWorkspaceId }
            StripChip(
                icon = Icons.Tune,
                label = workspace?.label ?: "Workspace",
                contentDescription = "Workspace: ${workspace?.label ?: "not selected"}",
                enabled = state.enabled && !state.workspaceChoicesLoading,
                onClick = { onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.THREAD_WORKSPACE)) },
            ) {}
        }
        TextButton(onClick = onClose) { Text("Done", color = ComposerMuted) }
    }
}

@Composable
private fun StripChip(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(ComposerSelected)
                .clickable(enabled = enabled) { if (onClick != null) onClick() else open = true }
                .semantics { this.contentDescription = contentDescription }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (enabled) ComposerContent else ComposerDisabled)
            Icon(Icons.ChevronDown, contentDescription = null, modifier = Modifier.size(12.dp), tint = ComposerMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

private fun ProviderOptionUi.Select.selectedLabel(): String =
    values.firstOrNull { it.id == selectedValueId }?.displayLabel() ?: label

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
    if (label.trim().lowercase().contains("reason")) Icons.Flame else Icons.Tune

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
