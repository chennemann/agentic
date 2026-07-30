package de.chennemann.agentic.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import de.chennemann.agentic.ui.components.ChatActivityCard
import de.chennemann.agentic.ui.components.ConnectionStatusBanner
import de.chennemann.agentic.ui.components.T3MessageComposer
import de.chennemann.agentic.ui.components.ToolActivityGroup
import de.chennemann.agentic.ui.theme.MobileTheme
import de.chennemann.agentic.icons.Add
import de.chennemann.agentic.icons.Archive
import de.chennemann.agentic.icons.ArchiveRestore
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Rename
import de.chennemann.agentic.icons.Tune
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun T3ChatScreen(
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val expandedActivities = remember(state.threadId) { mutableStateMapOf<String, Boolean>() }
    var followLatest by remember(state.threadId) { mutableStateOf(true) }
    val lastItemSignature = state.timeline.lastOrNull().contentSignature()

    LaunchedEffect(listState, dragging) {
        snapshotFlow { dragging to listState.isAtEnd() }
            .distinctUntilChanged()
            .collect { (isDragging, isAtEnd) ->
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
        followLatest,
    ) {
        if (followLatest) {
            listState.scrollToLatest()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
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
                            scope.launch { listState.scrollToLatest() }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
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
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Tune,
                        contentDescription = "Open navigation and settings",
                    )
                }
            }

            T3MessageComposer(
                state = state.composer,
                turnRunning = state.isTurnRunning,
                onEvent = onEvent,
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
                SelectionContainer {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        ChatMessageAuthorUi.ASSISTANT -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    StreamingMarkdownText(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        inlineCode = SpanStyle(color = MaterialTheme.colorScheme.primary),
                    )
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
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        selectedInteractionMode = InteractionModeUi.PLAN,
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
