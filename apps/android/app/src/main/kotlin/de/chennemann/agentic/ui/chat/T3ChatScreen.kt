package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import de.chennemann.agentic.ui.components.ChatActivityCard
import de.chennemann.agentic.ui.components.ConnectionStatusBanner
import de.chennemann.agentic.ui.components.T3MessageComposer
import de.chennemann.agentic.ui.components.ToolActivityGroup
import de.chennemann.agentic.ui.theme.MobileTheme
import de.chennemann.agentic.ui.theme.LocalCodeScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.Archive
import de.chennemann.agentic.icons.ArchiveRestore
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Rename
import de.chennemann.agentic.icons.Tune
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory
import android.util.Base64

@Composable
fun T3ChatScreen(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    composerDraft: StateFlow<String>? = null,
    modifier: Modifier = Modifier,
    onOpenFiles: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val expandedActivities = remember(state.threadId) { mutableStateMapOf<String, Boolean>() }
    var followLatest by remember(state.threadId) { mutableStateOf(true) }
    var inputFocusRequested by remember(state.threadId) { mutableStateOf<Boolean?>(null) }
    var inputFocusedManually by remember(state.threadId) { mutableStateOf(false) }
    var messageIdWhenKeyboardOpened by remember(state.threadId) { mutableStateOf<String?>(null) }
    var historyDragActive by remember(state.threadId) { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var consumedImeHeightPx by remember(state.threadId) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeHeightPx = imeInsets.getBottom(density)
    val composerHeight = with(density) { composerHeightPx.toDp() }
    val lastItemSignature = state.timeline.lastOrNull().contentSignature()
    val latestMessageId = state.timeline.latestMessageId()
    val historyScrollConnection = remember(state.threadId, latestMessageId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (
                    source == NestedScrollSource.UserInput &&
                    available.y > 0f &&
                    shouldDismissKeyboardOnHistoryScroll(
                        keyboardOpenedManually = inputFocusedManually,
                        messageArrivedSinceKeyboardOpened = messageIdWhenKeyboardOpened != latestMessageId,
                    )
                ) {
                    inputFocusedManually = false
                    inputFocusRequested = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(latestMessageId) {
        inputFocusedManually = false
        messageIdWhenKeyboardOpened = null
    }

    LaunchedEffect(listState, dragging) {
        snapshotFlow { dragging to listState.isAtEnd() }
            .distinctUntilChanged()
            .collect { (isDragging, isAtEnd) ->
                if (isDragging) {
                    historyDragActive = true
                } else if (historyDragActive) {
                    historyDragActive = false
                }
                if (isDragging && !isAtEnd) {
                    followLatest = false
                } else if (isAtEnd) {
                    followLatest = true
                }
            }
    }

    LaunchedEffect(
        state.threadId,
        state.timeline.size,
        lastItemSignature,
        expandedActivities.toMap(),
        composerHeightPx,
        followLatest,
    ) {
        if (followLatest) {
            listState.scrollToLatest()
        }
    }

    LaunchedEffect(imeHeightPx, followLatest) {
        if (followLatest && imeHeightPx > consumedImeHeightPx) {
            listState.scrollBy((imeHeightPx - consumedImeHeightPx).toFloat())
        }
        consumedImeHeightPx = imeHeightPx
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .onKeyEvent { keyEvent ->
                val stroke = keyEvent.toHardwareKeyStroke(KeyboardFocus.GLOBAL)
                    ?: return@onKeyEvent false
                val action = HardwareKeyboardBindings.map(
                    stroke,
                    canSend = false,
                    turnRunning = state.isTurnRunning,
                ) ?: return@onKeyEvent false
                action.toChatUiEvent()?.let(onEvent)
                true
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            ConnectionStatusBanner(
                connection = state.connection,
                onRetry = { onEvent(ChatUiEvent.ConnectionRetryRequested) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(historyScrollConnection)
                        .testTag(ChatTimelineTestTag),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 12.dp,
                        end = 16.dp,
                        bottom = 12.dp + composerHeight,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                if (state.timeline.isEmpty()) {
                    item("empty-chat") {
                        EmptyChatState(
                            hasProject = state.projectLabel != null,
                            onNewThread = { onEvent(ChatUiEvent.NewThreadRequested) },
                        )
                    }
                }

                items(
                    items = state.timeline,
                    key = { it.id },
                ) { item ->
                    when (item) {
                        is ChatTimelineItemUi.Message -> MessageItem(item.value)
                        is ChatTimelineItemUi.Activity -> {
                            ChatActivityCard(
                                activity = item.value,
                                expanded = expandedActivities[item.value.id] == true,
                                onEvent = {
                                    if (it is ChatUiEvent.ActivityExpansionChanged) {
                                        expandedActivities[it.activityId] = it.expanded
                                    } else {
                                        onEvent(it)
                                    }
                                },
                            )
                        }
                        is ChatTimelineItemUi.ToolGroup -> {
                            ToolActivityGroup(
                                groupId = item.id,
                                activities = item.activities,
                                expanded = expandedActivities[item.id] == true,
                                activityExpanded = { expandedActivities[it] == true },
                                onEvent = {
                                    if (it is ChatUiEvent.ActivityExpansionChanged) {
                                        expandedActivities[it.activityId] = it.expanded
                                    } else {
                                        onEvent(it)
                                    }
                                },
                            )
                        }
                    }
                }

                    item("bottom-spacer") {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                if (!followLatest) {
                    SmallFloatingActionButton(
                        onClick = {
                            followLatest = true
                            scope.launch {
                                listState.scrollToLatest()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                start = 16.dp,
                                top = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp + composerHeight,
                            )
                            .testTag(FollowLatestTestTag),
                    ) {
                        Text("↓")
                    }
                }

                OutlinedButton(
                    onClick = {
                        onEvent(ChatUiEvent.PickerRequested(ChatPickerUi.NAVIGATION))
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                        .zIndex(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Tune,
                        contentDescription = "Open navigation and settings",
                        modifier = Modifier.size(20.dp),
                    )
                }

                state.projectLabel
                    ?.takeIf { it.isNotBlank() }
                    ?.let { projectName ->
                        ProjectNameBubble(
                            projectName = projectName,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(
                                    start = 72.dp,
                                    top = 12.dp,
                                    end = if (state.latestTurnChanges.files.isEmpty()) 72.dp else 124.dp,
                                )
                                .zIndex(1f),
                        )
                    }

                if (state.latestTurnChanges.files.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            onEvent(
                                ChatUiEvent.PickerRequested(
                                    ChatPickerUi.LATEST_TURN_CHANGES,
                                ),
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 8.dp, top = 8.dp)
                            .zIndex(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text("Changes (${state.latestTurnChanges.files.size})")
                    }
                }

                state.threadId?.takeIf { state.canBrowseFiles }?.let { threadId ->
                    if (state.latestTurnChanges.files.isEmpty()) {
                        OutlinedButton(
                            onClick = { onOpenFiles(threadId) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp).zIndex(1f),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) { Text("Files") }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f),
        ) {
            IsolatedDraftComposer(
                state = state.composer,
                draft = composerDraft,
                turnRunning = state.isTurnRunning,
                sessionTermination = state.sessionTermination,
                onEvent = onEvent,
                modifier = Modifier.onSizeChanged { composerHeightPx = it.height },
                inputFocusRequested = inputFocusRequested,
                onInputFocusedManually = {
                    inputFocusedManually = true
                    messageIdWhenKeyboardOpened = latestMessageId
                    inputFocusRequested = true
                },
            )
        }
    }

    ChatSelectionSheets(
        state = state,
        onEvent = onEvent,
    )

    state.renameDialog?.let { rename ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.RenameThreadDismissed) },
            title = { Text("Rename thread") },
            text = {
                OutlinedTextField(
                    value = rename.draft,
                    onValueChange = { onEvent(ChatUiEvent.RenameThreadDraftChanged(it)) },
                    enabled = !rename.saving,
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.RenameThreadConfirmed) },
                    enabled = rename.draft.isNotBlank() && !rename.saving,
                ) {
                    Text(if (rename.saving) "Saving…" else "Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.RenameThreadDismissed) },
                    enabled = !rename.saving,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    state.environmentRemoval?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.EnvironmentRemovalDismissed) },
            title = { Text("Remove ${removal.label}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This removes only this environment's saved credential, cached " +
                            "conversations, pending commands, and preferences from this device. " +
                            "Other environments are not affected.",
                    )
                    removal.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.EnvironmentRemovalConfirmed) },
                    enabled = !removal.removing,
                ) {
                    Text(if (removal.removing) "Removing…" else "Remove environment")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.EnvironmentRemovalDismissed) },
                    enabled = !removal.removing,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    state.projectCreation?.let { creation ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.ProjectCreationDismissed) },
            title = { Text("Add project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter an absolute path on the server or a repository URL.")
                    OutlinedTextField(
                        value = creation.source,
                        onValueChange = { onEvent(ChatUiEvent.ProjectCreationSourceChanged(it)) },
                        enabled = !creation.creating,
                        label = { Text("Server path or repository URL") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { onEvent(ChatUiEvent.ProjectCreationBrowseRequested) },
                        enabled = creation.source.isNotBlank() && !creation.creating && !creation.browsing,
                    ) {
                        Text("Browse folders")
                    }
                    if (creation.browsing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    if (creation.browsePath != null && !creation.browsing) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            creation.parentPath?.let {
                                item("project-parent") {
                                    TextButton(
                                        onClick = { onEvent(ChatUiEvent.ProjectCreationParentOpened) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("..", modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            items(creation.destinations, key = { it.fullPath }) { destination ->
                                TextButton(
                                    onClick = {
                                        onEvent(ChatUiEvent.ProjectCreationDestinationOpened(destination.fullPath))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = destination.name,
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    creation.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.ProjectCreationConfirmed) },
                    enabled = creation.source.isNotBlank() && !creation.creating,
                ) {
                    Text(if (creation.creating) "Creating…" else "Create project")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.ProjectCreationDismissed) },
                    enabled = !creation.creating,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    state.projectRename?.let { rename ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.ProjectRenameDismissed) },
            title = { Text("Rename project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current name: ${rename.previousTitle}")
                    OutlinedTextField(
                        value = rename.title,
                        onValueChange = { onEvent(ChatUiEvent.ProjectRenameTitleChanged(it)) },
                        enabled = !rename.saving,
                        label = { Text("Project name") },
                        singleLine = true,
                    )
                    rename.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.ProjectRenameConfirmed) },
                    enabled = rename.title.isNotBlank() && !rename.saving,
                ) {
                    Text(if (rename.saving) "Saving…" else "Rename")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.ProjectRenameDismissed) },
                    enabled = !rename.saving,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    state.projectRemoval?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.ProjectRemovalDismissed) },
            title = { Text("Remove ${removal.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This removes the project and its conversations from this T3 environment. " +
                            "It does not delete the server workspace or repository files.",
                    )
                    removal.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.ProjectRemovalConfirmed) },
                    enabled = !removal.removing,
                ) {
                    Text(if (removal.removing) "Removing…" else "Remove project")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(ChatUiEvent.ProjectRemovalDismissed) },
                    enabled = !removal.removing,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.groqSettings.dialogVisible) {
        GroqSettingsDialog(
            state = state.groqSettings,
            onDismiss = { onEvent(ChatUiEvent.GroqSettingsDismissed) },
            onSave = { onEvent(ChatUiEvent.GroqApiKeySaved(it)) },
            onRemove = { onEvent(ChatUiEvent.GroqApiKeyRemoved) },
        )
    }
}

@Composable
private fun IsolatedDraftComposer(
    state: ComposerUiState,
    draft: StateFlow<String>?,
    turnRunning: Boolean,
    sessionTermination: SessionTerminationUi,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    inputFocusRequested: Boolean? = null,
    onInputFocusedManually: () -> Unit = {},
) {
    if (draft == null) {
        T3MessageComposer(
            state = state,
            turnRunning = turnRunning,
            onEvent = onEvent,
            modifier = modifier,
            sessionTermination = sessionTermination,
            inputFocusRequested = inputFocusRequested,
            onInputFocusedManually = onInputFocusedManually,
        )
    } else {
        val value by draft.collectAsStateWithLifecycle()
        T3MessageComposer(
            state = state.copy(draft = value),
            turnRunning = turnRunning,
            onEvent = onEvent,
            modifier = modifier,
            sessionTermination = sessionTermination,
            inputFocusRequested = inputFocusRequested,
            onInputFocusedManually = onInputFocusedManually,
        )
    }
}

@Composable
private fun ProjectNameBubble(
    projectName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        shadowElevation = 2.dp,
    ) {
        Text(
            text = projectName,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroqSettingsDialog(
    state: GroqSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var apiKey by remember(state.dialogVisible) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groq transcription") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.apiKeyConfigured) {
                        "Voice input is enabled. Enter a new key to replace the saved key."
                    } else {
                        "Add a Groq API key to enable microphone transcription in the composer."
                    },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    enabled = !state.saving,
                    label = { Text("Groq API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "The key is encrypted with Android Keystore and is only sent to Groq.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(apiKey) },
                enabled = apiKey.isNotBlank() && !state.saving,
            ) {
                Text(if (state.saving) "Saving…" else "Save key")
            }
        },
        dismissButton = {
            Row {
                if (state.apiKeyConfigured) {
                    TextButton(
                        onClick = onRemove,
                        enabled = !state.saving,
                    ) {
                        Text("Remove key")
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.saving,
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun MessageItem(message: ChatMessageUi) {
    when (message.author) {
        ChatMessageAuthorUi.USER -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MessageImages(message.attachments)
                    if (message.content.isNotBlank()) SelectionContainer {
                        Text(text = message.content)
                    }
                }
            }
        }

        ChatMessageAuthorUi.ASSISTANT -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MessageImages(message.attachments)
                SelectionContainer {
                    val codeScale = LocalCodeScale.current
                    val localDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(localDensity.density, localDensity.fontScale * codeScale),
                    ) { StreamingMarkdownText(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        inlineCode = SpanStyle(color = MaterialTheme.colorScheme.primary),
                    ) }
                }
                if (message.isStreaming) {
                    Text(
                        text = "Responding…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                message.supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ChatMessageAuthorUi.SYSTEM -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MessageImages(message.attachments)
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageImages(attachments: List<ChatImageAttachmentUi>) {
    if (attachments.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            val bitmap = remember(attachment.previewDataUrl) {
                attachment.previewDataUrl?.let { dataUrl ->
                    runCatching {
                        val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            if (bitmap == null) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("Loading ${attachment.name}…", modifier = Modifier.padding(12.dp))
                }
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.name,
                    modifier = Modifier.size(140.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyChatState(
    hasProject: Boolean,
    onNewThread: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (hasProject) "Start a new task" else "Choose a project to begin",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Conversation stays here while T3 reconnects or catches up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasProject) {
            OutlinedButton(onClick = onNewThread) {
                Icon(
                    imageVector = Icons.Add,
                    contentDescription = null,
                )
                Text("New thread")
            }
        }
    }
}

private fun ChatTimelineItemUi?.contentSignature(): Any? = when (this) {
    is ChatTimelineItemUi.Message -> Triple(value.id, value.content.length, value.isStreaming)
    is ChatTimelineItemUi.Activity -> when (val activity = value) {
        is ChatActivityUi.Tool -> Triple(activity.id, activity.status, activity.detail?.length)
        is ChatActivityUi.Unknown -> activity.id to activity.formattedDetail.length
        else -> activity.id
    }
    is ChatTimelineItemUi.ToolGroup -> {
        val last = activities.lastOrNull()
        Triple(id, activities.size, last?.let { it.id to it.status })
    }

    null -> null
}

private fun List<ChatTimelineItemUi>.latestMessageId(): String? =
    asReversed().firstNotNullOfOrNull { (it as? ChatTimelineItemUi.Message)?.value?.id }

private fun androidx.compose.foundation.lazy.LazyListState.isAtEnd(): Boolean {
    val layout = layoutInfo
    val total = layout.totalItemsCount
    if (total == 0) return true
    val last = layout.visibleItemsInfo.lastOrNull() ?: return false
    return last.index == total - 1 &&
        last.offset + last.size <= layout.viewportEndOffset + EndTolerancePx
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToLatest() {
    repeat(MaxEndVisibilityPasses) {
        val layout = layoutInfo
        val count = layout.totalItemsCount
        if (count == 0) return
        val endIndex = count - 1
        val endItem = layout.visibleItemsInfo.lastOrNull { it.index == endIndex }
        if (endItem == null) {
            scrollToItem(endIndex)
            delay(LayoutSettleDelayMillis)
            return@repeat
        }
        val overflow = endItem.offset + endItem.size - layout.viewportEndOffset
        if (overflow <= 0) return
        animateScrollBy(overflow.toFloat())
        delay(LayoutSettleDelayMillis)
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatStreamingAndActivitiesPreview() {
    MobileTheme {
        T3ChatScreen(
            state = previewChatState(),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyCachedChatPreview() {
    MobileTheme {
        T3ChatScreen(
            state = ChatUiState(
                title = "New thread",
                environmentLabel = "Home",
                projectLabel = "agentic",
                threadId = null,
                timeline = emptyList(),
                connection = ChatConnectionUi.Cached(),
                composer = ComposerUiState(enabled = false),
            ),
            onEvent = {},
        )
    }
}

private fun previewChatState(): ChatUiState = ChatUiState(
    title = "Compose migration",
    environmentLabel = "Studio",
    projectLabel = "agentic",
    threadId = "thread-1",
    connection = ChatConnectionUi.Reconnecting(),
    timeline = listOf(
        ChatTimelineItemUi.Message(
            ChatMessageUi(
                id = "user-1",
                author = ChatMessageAuthorUi.USER,
                content = "Keep the chat surface provider-neutral.",
            ),
        ),
        ChatTimelineItemUi.Activity(
            ChatActivityUi.Tool(
                id = "activity-1",
                summary = "Inspecting Android UI",
                subtitle = "3 files",
                status = ActivityStatusUi.COMPLETED,
                detail = "Read the existing composer and conversation feed.",
            ),
        ),
        ChatTimelineItemUi.Activity(
            ChatActivityUi.Unknown(
                id = "activity-2",
                summary = "Server activity",
                typeLabel = "future.activity",
                formattedDetail = """{"safeDetail":"Preserved for display"}""",
            ),
        ),
        ChatTimelineItemUi.Message(
            ChatMessageUi(
                id = "assistant-1",
                author = ChatMessageAuthorUi.ASSISTANT,
                content = "The new UI uses **server-provided labels** and generic activity cards.",
                isStreaming = true,
            ),
        ),
    ),
    composer = ComposerUiState(
        draft = "",
        selectedProviderModelId = "instance-a/model-a",
        providerModels = listOf(
            ProviderModelOptionUi(
                id = "instance-a/model-a",
                providerInstanceId = "instance-a",
                providerLabel = "Provider A",
                modelLabel = "Model A",
            ),
        ),
        selectedRuntimeModeId = "workspace",
        runtimeModes = listOf(RuntimeModeOptionUi("workspace", "Workspace")),
        slashCommands = listOf(SlashCommandUi("review", "Review the current changes")),
    ),
    isTurnRunning = true,
    canRenameThread = true,
    canArchiveThread = true,
)

private const val EndTolerancePx = 8
private const val MaxEndVisibilityPasses = 8
private const val LayoutSettleDelayMillis = 16L

internal const val ChatTimelineTestTag = "chat-timeline"
internal const val FollowLatestTestTag = "follow-latest"
