package de.chennemann.agentic.ui.components

import de.chennemann.agentic.domain.preferences.FollowUpBehavior
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import de.chennemann.agentic.icons.Lock
import de.chennemann.agentic.icons.Star
import de.chennemann.agentic.icons.StarOutline
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import de.chennemann.agentic.icons.Close
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
import de.chennemann.agentic.ui.chat.ChatImageAttachmentUi
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
    modelsOpen: Boolean = false,
    inputFocusRequested: Boolean? = null,
    onInputFocusedManually: () -> Unit = {},
    onOpenImage: (ChatImageAttachmentUi) -> Unit = {},
) {
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val requestMicrophonePermission = rememberMicrophonePermissionRequest(
        onGranted = { onEvent(ChatUiEvent.VoiceInputPressed) },
        onDenied = { onEvent(ChatUiEvent.MicrophonePermissionDenied) },
    )
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) onEvent(ChatUiEvent.ImagesSelected(uris.map(Any::toString)))
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onEvent(ChatUiEvent.FilesSelected(uris.map(Any::toString)))
    }
    val selectedModel = state.providerModels.firstOrNull {
        it.id == state.selectedProviderModelId
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
    BackHandler(enabled = modelsOpen) { onEvent(ChatUiEvent.PickerDismissed) }
    LaunchedEffect(modelsOpen) {
        if (modelsOpen) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            controlsOpen = true
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
                        attachment.previewDataUrl?.let { dataUrl ->
                            AttachmentPreview(
                                dataUrl = dataUrl,
                                name = attachment.name,
                                annotationCount = attachment.annotations.size,
                                onClick = { onOpenImage(attachment) },
                                onRemove = { onEvent(ChatUiEvent.ImageRemoved(attachment.id)) },
                            )
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
            if (state.suggestions.isNotEmpty() || state.suggestionsLoading || state.suggestionsError != null) {
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    state.suggestions.forEach { suggestion ->
                        Column(Modifier.fillMaxWidth().clickable {
                            onEvent(ChatUiEvent.ComposerSuggestionSelected(suggestion.id))
                        }.padding(vertical = 8.dp)) {
                            Text(suggestion.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            if (suggestion.detail.isNotBlank()) Text(suggestion.detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ComposerMuted)
                        }
                    }
                    if (state.suggestionsLoading) Text("Searching…", style = MaterialTheme.typography.bodySmall, color = ComposerMuted)
                    state.suggestionsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                androidx.compose.runtime.key(state.draftIdentity) {
                    NativeComposerEditor(
                        state = state,
                        turnRunning = turnRunning,
                        onEvent = { event ->
                            val token = (event as? ChatUiEvent.ComposerContextRequested)?.source?.let {
                                de.chennemann.agentic.domain.composer.composerTokens(it).singleOrNull()
                            }
                            val binding = token?.contextId?.let { id -> state.contextItems.firstOrNull { it.id == id }?.attachmentId }
                            val image = binding?.let { id -> state.imageAttachments.firstOrNull { it.id == id } }
                            if (image != null) onOpenImage(image) else onEvent(event)
                        },
                        onFocus = {
                            controlsOpen = false
                            if (modelsOpen) onEvent(ChatUiEvent.PickerDismissed)
                            onInputFocusedManually()
                        },
                        focusRequested = if (controlsOpen) false else inputFocusRequested,
                        modifier = Modifier.weight(1f).testTag(ComposerInputTestTag),
                    )
                }
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        var attachMenuOpen by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = {
                                if (state.fileAttachmentsAvailable) attachMenuOpen = true else imagePicker.launch("image/*")
                            },
                            enabled = state.enabled && !state.sending &&
                                (state.fileAttachmentsAvailable || state.imageAttachments.size < 8),
                        ) {
                            Icon(
                                imageVector = Icons.Paperclip,
                                contentDescription = if (state.fileAttachmentsAvailable) "Attach" else "Attach images",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Photos") },
                                enabled = state.imageAttachments.size < 8,
                                onClick = {
                                    attachMenuOpen = false
                                    imagePicker.launch("image/*")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Files") },
                                onClick = {
                                    attachMenuOpen = false
                                    filePicker.launch(arrayOf("*/*"))
                                },
                            )
                        }
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
                        modelsOpen = modelsOpen,
                        onEvent = onEvent,
                        onClose = {
                            controlsOpen = false
                            if (modelsOpen) onEvent(ChatUiEvent.PickerDismissed)
                        },
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
            AnimatedVisibility(
                visible = modelsOpen && controlsOpen,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                ModelPanel(
                    models = state.providerModels,
                    selectedId = state.selectedProviderModelId,
                    lockLabel = state.modelLockLabel,
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
    state.contextPreview?.let { preview ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.ComposerContextDismissed) },
            title = { Text(preview.title) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (preview.loading) CircularProgressIndicator(Modifier.size(24.dp))
                        Text(preview.text, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onEvent(ChatUiEvent.ComposerContextDismissed) }) { Text("Close") } },
        )
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
private fun AttachmentPreview(
    dataUrl: String,
    name: String,
    annotationCount: Int,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bitmap = remember(dataUrl) { decodeDataUrlBitmap(dataUrl, 512)?.asImageBitmap() }
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ComposerSelected)
            .clickable(onClickLabel = "Open image", onClick = onClick),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AnnotationCountBadge(annotationCount, Modifier.align(Alignment.BottomEnd))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .clickable(onClickLabel = "Remove $name", onClick = onRemove)
                .semantics { contentDescription = "Remove $name" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
        }
    }
}

const val ComposerInputTestTag = "composer-input"
const val ComposerModelPanelTestTag = "composer-model-panel"

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
        Row(
            modifier = Modifier.semantics {
                contentDescription = selectedModel?.let { "Model ${it.modelLabel} from ${it.providerLabel}" }
                    ?: "Model not selected"
            },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelMark(selectedModel)
            Text(
                text = selectedModel?.modelLabel ?: "Choose model",
                style = MaterialTheme.typography.labelMedium,
                color = ComposerContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        providerOptions.forEach { option ->
            val valueLabel = when (option) {
                is ProviderOptionUi.Select -> option.selectedLabel()
                is ProviderOptionUi.Toggle -> if (option.selected) "On" else "Off"
            }
            Icon(
                imageVector = option.icon(),
                contentDescription = "${option.label}: $valueLabel",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One row of chips in place of the summary. The model chip unfolds the model panel below the
 * strip; the other chips open a dropdown anchored to themselves so nothing covers the conversation.
 */
@Composable
private fun RunControlStrip(
    state: ComposerUiState,
    selectedModel: ProviderModelOptionUi?,
    modelsOpen: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    onClose: () -> Unit,
) {
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
            leading = { ModelMark(selectedModel, size = 14.dp) },
            label = selectedModel?.modelLabel ?: "Model",
            contentDescription = if (modelsOpen) {
                "Close models"
            } else {
                selectedModel?.let { "Model ${it.modelLabel} from ${it.providerLabel}" } ?: "Choose model"
            },
            enabled = state.enabled && state.providerModels.isNotEmpty(),
            expanded = modelsOpen,
            onClick = {
                onEvent(
                    if (modelsOpen) ChatUiEvent.PickerDismissed else ChatUiEvent.PickerRequested(ChatPickerUi.PROVIDER_MODEL),
                )
            },
        ) {}
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
    leading: (@Composable () -> Unit)? = null,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded || open) 180f else 0f, label = "chip chevron")
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
            leading?.invoke()
                ?: Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (enabled) ComposerContent else ComposerDisabled)
            Icon(
                Icons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(12.dp).rotate(chevronRotation),
                tint = ComposerMuted,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun ModelMark(model: ProviderModelOptionUi?, size: Dp = 16.dp) {
    if (model == null) {
        Icon(Icons.Brain, contentDescription = null, modifier = Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
    } else {
        ProviderMark(driver = model.providerDriver, label = model.providerLabel, size = size, tint = ComposerContent)
    }
}

/**
 * Every model the conversation may use, favorites first, unfolding under the run controls. A
 * started thread only lists models of the provider it is bound to.
 */
@Composable
private fun ModelPanel(
    models: List<ProviderModelOptionUi>,
    selectedId: String?,
    lockLabel: String?,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val favorites = models.filter(ProviderModelOptionUi::favorite).sortedBy { it.favoriteOrder }
    val others = models.filterNot(ProviderModelOptionUi::favorite)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .testTag(ComposerModelPanelTestTag),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        lockLabel?.let { provider ->
            item(key = "lock") {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = ComposerMuted)
                    Text(
                        text = "This thread stays on $provider",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComposerMuted,
                    )
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "favorites") { ModelSectionLabel("Favorites") }
            items(favorites, key = { "favorite:${it.id}" }) { model ->
                ModelPanelRow(model, selected = model.id == selectedId, onEvent = onEvent)
            }
            if (others.isNotEmpty()) item(key = "all") { ModelSectionLabel("All models") }
        }
        items(others, key = { "model:${it.id}" }) { model ->
            ModelPanelRow(model, selected = model.id == selectedId, onEvent = onEvent)
        }
    }
}

@Composable
private fun ModelSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ComposerMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ModelPanelRow(
    model: ProviderModelOptionUi,
    selected: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) ComposerSelected else Color.Transparent)
            .clickable { onEvent(ChatUiEvent.ProviderModelSelected(model.id)) }
            .semantics { contentDescription = "${model.modelLabel} from ${model.providerLabel}" }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderMark(driver = model.providerDriver, label = model.providerLabel, size = 18.dp, tint = ComposerContent)
        Column(Modifier.weight(1f)) {
            Text(
                text = model.modelLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else ComposerContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.providerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = ComposerMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = { onEvent(ChatUiEvent.ProviderModelFavoriteChanged(model.id, !model.favorite)) },
        ) {
            Icon(
                imageVector = if (model.favorite) Icons.Star else Icons.StarOutline,
                contentDescription = if (model.favorite) {
                    "Remove ${model.modelLabel} from favorites"
                } else {
                    "Add ${model.modelLabel} to favorites"
                },
                modifier = Modifier.size(18.dp),
                tint = if (model.favorite) MaterialTheme.colorScheme.primary else ComposerMuted,
            )
        }
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
