package de.chennemann.agentic.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.chennemann.agentic.streamingmarkdown.LocalMarkdownLinkHandler
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import de.chennemann.agentic.ui.components.AnnotationCountBadge
import de.chennemann.agentic.ui.components.ImageViewerDialog
import de.chennemann.agentic.ui.components.HtmlRenderView
import de.chennemann.agentic.ui.components.ViewerImage
import de.chennemann.agentic.ui.components.decodeDataUrlBitmap
import androidx.compose.foundation.clickable
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
import de.chennemann.agentic.icons.Copy
import de.chennemann.agentic.icons.DoubleChevronDown
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Paperclip
import de.chennemann.agentic.icons.Rename
import de.chennemann.agentic.domain.attachments.formatByteSize
import de.chennemann.agentic.domain.orchestration.FileLinkTarget
import de.chennemann.agentic.ui.files.FileLinkHandler
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
    onOpenFileLink: (threadId: String, target: FileLinkTarget) -> Unit = { _, _ -> },
    onOpenUpdater: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenPullRequests: () -> Unit = {},
    onOpenTerminal: (String) -> Unit = {},
    onOpenChanges: (String) -> Unit = {},
    onRunProjectScript: (String, ProjectScriptUi) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val currentThreadId by rememberUpdatedState(state.threadId)
    val currentOpenFileLink by rememberUpdatedState(onOpenFileLink)
    // Stable so streaming recompositions do not invalidate every markdown text that reads it.
    val openFileLink = remember {
        FileLinkHandler { target -> currentThreadId?.let { currentOpenFileLink(it, target) } }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var imageViewer by remember { mutableStateOf<ImageViewerRequest?>(null) }
    val openImage = { request: ImageViewerRequest -> imageViewer = request }
    val scope = rememberCoroutineScope()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val expandedActivities = remember(state.threadId) { mutableStateMapOf<String, Boolean>() }
    val pendingRequests = state.timeline.filterIsInstance<ChatTimelineItemUi.Activity>().filter {
        it.value is ChatActivityUi.Approval || it.value is ChatActivityUi.UserInput
    }
    var requestsExpanded by remember(state.threadId, pendingRequests.map { it.id }) { mutableStateOf(true) }
    val timeline = remember(state.timeline, expandedActivities.toMap()) {
        state.timeline.filterNot { it in pendingRequests }.flatMap { item ->
            if (item is ChatTimelineItemUi.WorkSummary && expandedActivities[item.lazyKey] == true) listOf(item) + item.items else listOf(item)
        }
    }
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
    val turnBoundaryFlingThresholdPx = with(density) { TurnBoundaryFlingThreshold.toPx() }
    val lastItemSignature = timeline.lastOrNull().contentSignature()
    val latestMessageId = timeline.latestMessageId()
    val userMessageIndices = remember(timeline) {
        timeline.mapIndexedNotNull { index, item ->
            index.takeIf {
                (item as? ChatTimelineItemUi.Message)?.value?.author == ChatMessageAuthorUi.USER
            }
        }
    }
    val nativeFlingBehavior = ScrollableDefaults.flingBehavior()
    val turnBoundaryFlingBehavior = remember(
        listState,
        userMessageIndices,
        turnBoundaryFlingThresholdPx,
        nativeFlingBehavior,
    ) {
        ChatTurnFlingBehavior(
            listState = listState,
            userMessageIndices = userMessageIndices,
            minimumVelocity = turnBoundaryFlingThresholdPx,
            nativeFlingBehavior = nativeFlingBehavior,
        )
    }
    val historyScrollConnection = remember(
        state.threadId,
        latestMessageId,
    ) {
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
        timeline.size,
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatProjectDrawer(
                state = state,
                onEvent = onEvent,
                onClose = { scope.launch { drawerState.close() } },
                onOpenFiles = onOpenFiles,
                onOpenTerminal = onOpenTerminal,
                onOpenChanges = onOpenChanges,
                onRunProjectScript = onRunProjectScript,
                onOpenUsage = onOpenUsage,
                onOpenPullRequests = onOpenPullRequests,
            )
        },
    ) {
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

                if (state.history.hasMore) {
                    TextButton(
                        onClick = {
                            followLatest = false
                            onEvent(ChatUiEvent.OlderHistoryRequested)
                        },
                        enabled = !state.history.loading,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(if (state.history.loading) "Loading history…" else "Load older messages")
                    }
                    state.history.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyColumn(
                        state = listState,
                        flingBehavior = turnBoundaryFlingBehavior,
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
                    if (timeline.isEmpty()) {
                        item("empty-chat") {
                            EmptyChatState(
                                hasProject = state.projectLabel != null,
                                onNewThread = { onEvent(ChatUiEvent.NewThreadRequested) },
                            )
                        }
                    }

                    items(
                        items = timeline,
                        key = { it.lazyKey },
                    ) { item ->
                        CompositionLocalProvider(LocalMarkdownLinkHandler provides openFileLink) {
                        when (item) {
                            is ChatTimelineItemUi.HtmlRender -> HtmlRenderView(
                                page = item.page,
                                content = state.htmlPages[item.page.attachmentId],
                                onLoad = { onEvent(ChatUiEvent.HtmlPageRequested(item.page.attachmentId)) },
                                onOpenImages = openImage,
                            )
                            is ChatTimelineItemUi.WorkSummary -> TextButton(
                                onClick = { expandedActivities[item.lazyKey] = expandedActivities[item.lazyKey] != true },
                            ) {
                                Text(item.label + if (expandedActivities[item.lazyKey] == true) " - Hide work" else " - Show work")
                            }
                            is ChatTimelineItemUi.Message -> MessageItem(
                                message = item.value,
                                inlineImages = state.inlineImages,
                                onRequestInlineImage = { onEvent(ChatUiEvent.InlineImageRequested(it)) },
                                onOpenImage = openImage,
                            )
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
                    }

                        state.latestTurnTiming?.let { timing ->
                            item("latest-turn-timing:${timing.startedAt}") {
                                TurnTimingRow(timing)
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
                            Icon(
                                imageVector = Icons.DoubleChevronDown,
                                contentDescription = "Scroll to latest message",
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                }
            }

            val threadPanelVisible = state.activePicker == ChatPickerUi.PROJECT_THREAD
            var threadPanelProgress by remember { mutableFloatStateOf(0f) }
            ThreadPanelScrim(
                visible = threadPanelVisible,
                progress = threadPanelProgress,
                onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Weighted slot: the composer below is measured first and keeps its size; the panel
                // may only grow into what is left above it.
                BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
                    ThreadPanel(
                        visible = threadPanelVisible,
                        state = state,
                        onEvent = onEvent,
                        onDismiss = { onEvent(ChatUiEvent.PickerDismissed) },
                        maxHeight = maxHeight,
                        onRevealProgress = { threadPanelProgress = it },
                    )
                }
                Column(modifier = Modifier.onSizeChanged { composerHeightPx = it.height }) {
                    if (pendingRequests.isNotEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { requestsExpanded = !requestsExpanded }, modifier = Modifier.weight(1f)) {
                                        Text(if (requestsExpanded) "Hide pending requests" else "Response needed (${pendingRequests.size})")
                                    }
                                    if (state.isTurnRunning) TextButton(onClick = { onEvent(ChatUiEvent.TurnInterruptRequested) }) { Text("Stop") }
                                }
                                if (requestsExpanded) Column(
                                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pendingRequests.forEach { request ->
                                        ChatActivityCard(request.value, expanded = true, onEvent = onEvent)
                                    }
                                }
                                state.composer.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                            }
                        }
                    }
                    IsolatedDraftComposer(
                        onOpenImage = { opened ->
                            val drafts = state.composer.imageAttachments.map { ImageViewerItem.Draft(it.id) }
                            openImage(ImageViewerRequest(drafts, drafts.indexOfFirst { it.imageId == opened.id }))
                        },
                        state = state.composer,
                        draft = composerDraft,
                        turnRunning = state.isTurnRunning,
                        onEvent = onEvent,
                        projectLabel = state.projectLabel,
                        threadTitle = state.title.takeIf { state.threadId != null },
                        onOpenProjectPicker = { scope.launch { drawerState.open() } },
                    threadsOpen = threadPanelVisible,
                    onOpenThreads = {
                        onEvent(
                            if (threadPanelVisible) ChatUiEvent.PickerDismissed else ChatUiEvent.PickerRequested(ChatPickerUi.PROJECT_THREAD),
                        )
                    },
                        modelsOpen = state.activePicker == ChatPickerUi.PROVIDER_MODEL,
                        modifier = Modifier,
                        inputFocusRequested = inputFocusRequested,
                        onInputFocusedManually = {
                            inputFocusedManually = true
                            messageIdWhenKeyboardOpened = latestMessageId
                            inputFocusRequested = true
                        },
                    )
                }
            }
        }
    }

    imageViewer?.let { request ->
        ImageViewerHost(
            request = request,
            state = state,
            onEvent = onEvent,
            onDismiss = { imageViewer = null },
        )
    }

    ChatSelectionSheets(
        state = state,
        onEvent = onEvent,
        onOpenUpdater = onOpenUpdater,
        onOpenUsage = onOpenUsage,
                onOpenPullRequests = onOpenPullRequests,
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
                    if (creation.canCreateNew) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onEvent(ChatUiEvent.ProjectCreationKindChanged(false)) },
                                enabled = !creation.creating && !creation.browsing,
                            ) { Text("Existing folder") }
                            TextButton(
                                onClick = { onEvent(ChatUiEvent.ProjectCreationKindChanged(true)) },
                                enabled = !creation.creating && !creation.browsing,
                            ) { Text("New project") }
                        }
                    }
                    Text(if (creation.createNew) "The server creates a folder for this project." else "Enter an absolute path on the server or a repository URL.")
                    OutlinedTextField(
                        value = creation.source,
                        onValueChange = { onEvent(ChatUiEvent.ProjectCreationSourceChanged(it)) },
                        enabled = !creation.creating,
                        label = { Text(if (creation.createNew) "Project name" else "Server path or repository URL") },
                        singleLine = true,
                    )
                    if (!creation.createNew) OutlinedButton(
                        onClick = { onEvent(ChatUiEvent.ProjectCreationBrowseRequested) },
                        enabled = creation.source.isNotBlank() && !creation.creating && !creation.browsing,
                    ) {
                        Text("Browse folders")
                    }
                    if (creation.canUseScratch) {
                        TextButton(
                            onClick = { onEvent(ChatUiEvent.ScratchProjectRequested) },
                            enabled = !creation.creating && !creation.browsing,
                        ) { Text("No project") }
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

    state.projectCreationNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.ProjectCreationNoticeDismissed) },
            title = { Text("Project created") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.ProjectCreationNoticeDismissed) }) { Text("OK") }
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

/** Dims the conversation in step with how far the thread panel is revealed. */
@Composable
private fun ThreadPanelScrim(visible: Boolean, progress: Float, onDismiss: () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    if (progress <= 0f) return
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(1f)
            .background(Color.Black.copy(alpha = 0.55f * progress))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = visible,
                onClick = onDismiss,
            ),
    )
}

@Composable
private fun IsolatedDraftComposer(
    onOpenImage: (ChatImageAttachmentUi) -> Unit,
    state: ComposerUiState,
    draft: StateFlow<String>?,
    turnRunning: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    projectLabel: String?,
    threadTitle: String?,
    onOpenProjectPicker: () -> Unit,
    threadsOpen: Boolean,
    onOpenThreads: () -> Unit,
    modelsOpen: Boolean,
    modifier: Modifier = Modifier,
    inputFocusRequested: Boolean? = null,
    onInputFocusedManually: () -> Unit = {},
) {
    if (draft == null) {
        T3MessageComposer(
            state = state,
            turnRunning = turnRunning,
            onEvent = onEvent,
            projectLabel = projectLabel,
            threadTitle = threadTitle,
            onOpenProjectPicker = onOpenProjectPicker,
            threadsOpen = threadsOpen,
            onOpenThreads = onOpenThreads,
            modelsOpen = modelsOpen,
            modifier = modifier,
            inputFocusRequested = inputFocusRequested,
            onInputFocusedManually = onInputFocusedManually,
            onOpenImage = onOpenImage,
        )
    } else {
        val value by draft.collectAsStateWithLifecycle()
        T3MessageComposer(
            state = state.copy(draft = value),
            turnRunning = turnRunning,
            onEvent = onEvent,
            projectLabel = projectLabel,
            threadTitle = threadTitle,
            onOpenProjectPicker = onOpenProjectPicker,
            threadsOpen = threadsOpen,
            onOpenThreads = onOpenThreads,
            modelsOpen = modelsOpen,
            modifier = modifier,
            inputFocusRequested = inputFocusRequested,
            onInputFocusedManually = onInputFocusedManually,
            onOpenImage = onOpenImage,
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
private fun MessageItem(
    message: ChatMessageUi,
    inlineImages: Map<String, InlineImageUi> = emptyMap(),
    onRequestInlineImage: (String) -> Unit = {},
    onOpenImage: (ImageViewerRequest) -> Unit = {},
) {
    val segments = remember(message.content) { splitMessageImages(message.content) }
    // Every picture of this message, attachments first, so the viewer can swipe between them.
    val attachmentItems = message.attachments.map {
        ImageViewerItem.Conversation(it.previewDataUrl, it.name, it.annotations, it.burnedAnnotationCount)
    }
    val inlineItems = segments.filterIsInstance<MessageSegment.Image>().associateWith { segment ->
        (inlineImages[segment.path] as? InlineImageUi.Loaded)?.let {
            ImageViewerItem.Conversation(it.dataUrl, segment.path.substringAfterLast('/').substringAfterLast('\\'))
        }
    }
    val gallery = attachmentItems + inlineItems.values.filterNotNull()
    val openInGallery = { item: ImageViewerItem -> onOpenImage(ImageViewerRequest(gallery, gallery.indexOf(item))) }
    val openAttachment = { index: Int -> openInGallery(attachmentItems[index]) }
    val inlineImage: @Composable (MessageSegment.Image) -> Unit = { segment ->
        InlineMessageImage(segment, inlineImages[segment.path], onRequestInlineImage) { dataUrl ->
            openInGallery(
                inlineItems[segment]
                    ?: ImageViewerItem.Conversation(dataUrl, segment.path.substringAfterLast('/').substringAfterLast('\\')),
            )
        }
    }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val copyMessage = {
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.content))
        copied = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (message.author) {
            ChatMessageAuthorUi.USER -> {
                Column(horizontalAlignment = Alignment.End) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MessageImages(message.attachments, openAttachment)
                            MessageFiles(message.files)
                            if (message.content.isNotBlank()) segments.forEach { segment ->
                                when (segment) {
                                    is MessageSegment.Image -> inlineImage(segment)
                                    is MessageSegment.Text -> SelectionContainer {
                                        StreamingMarkdownText(
                                            content = segment.markdown,
                                            modifier = Modifier.fillMaxWidth(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            streaming = false,
                                            inlineCode = SpanStyle(color = MaterialTheme.colorScheme.onPrimaryContainer),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ChatMessageAuthorUi.ASSISTANT -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageImages(message.attachments, openAttachment)
                    MessageFiles(message.files)
                    segments.forEachIndexed { index, segment ->
                        when (segment) {
                            is MessageSegment.Image -> inlineImage(segment)
                            is MessageSegment.Text -> SelectionContainer {
                                val codeScale = LocalCodeScale.current
                                val localDensity = LocalDensity.current
                                CompositionLocalProvider(
                                    LocalDensity provides Density(localDensity.density, localDensity.fontScale * codeScale),
                                ) { StreamingMarkdownText(
                                    content = segment.markdown,
                                    streaming = message.isStreaming && index == segments.lastIndex,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    inlineCode = SpanStyle(color = MaterialTheme.colorScheme.primary),
                                ) }
                            }
                        }
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
                    if (message.canCopy) {
                        MessageCopyButton(copied = copied, onCopy = copyMessage)
                    }
                }
            }

            ChatMessageAuthorUi.REASONING -> {
                var expanded by remember(message.id) { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide thought" else "Thought") }
                    if (expanded) SelectionContainer {
                        StreamingMarkdownText(message.content, streaming = message.isStreaming)
                    }
                }
            }
            ChatMessageAuthorUi.SYSTEM -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageImages(message.attachments, openAttachment)
                    MessageFiles(message.files)
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        message.timestamp?.let { timestamp ->
            val label = remember(timestamp, LocalConfiguration.current) {
                runCatching {
                    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date.from(java.time.Instant.parse(timestamp)))
                }.getOrNull()
            }
            label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MessageCopyButton(
    copied: Boolean,
    onCopy: () -> Unit,
) {
    IconButton(
        onClick = onCopy,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            imageVector = Icons.Copy,
            contentDescription = if (copied) "Copied" else "Copy message",
            modifier = Modifier.size(14.dp),
            tint = if (copied) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
        )
    }
}

@Composable
private fun MessageFiles(files: List<ChatFileAttachmentUi>) {
    if (files.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { file ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Paperclip, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatByteSize(file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageImages(attachments: List<ChatImageAttachmentUi>, onOpenImage: (index: Int) -> Unit) {
    if (attachments.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(attachments, key = { _, it -> it.id }) { index, attachment ->
            val bitmap = remember(attachment.previewDataUrl) {
                attachment.previewDataUrl?.let { decodeDataUrlBitmap(it, 720)?.asImageBitmap() }
            }
            if (bitmap == null) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("Loading ${attachment.name}…", modifier = Modifier.padding(12.dp))
                }
            } else {
                Box(
                    Modifier
                        .width(220.dp)
                        .height(164.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClickLabel = "Open image") { onOpenImage(index) },
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    AnnotationCountBadge(attachment.annotations.size, Modifier.align(Alignment.BottomEnd))
                }
            }
        }
    }
}

/** An image a message refers to by path, loaded on demand and shown in the flow of the text. */
@Composable
private fun InlineMessageImage(
    segment: MessageSegment.Image,
    image: InlineImageUi?,
    onRequest: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    LaunchedEffect(segment.path, image == null) { if (image == null) onRequest(segment.path) }
    val label = segment.alt.ifBlank { segment.path.substringAfterLast('/').substringAfterLast('\\') }
    when (image) {
        is InlineImageUi.Loaded -> {
            val bitmap = remember(image.dataUrl) { decodeDataUrlBitmap(image.dataUrl, 1280)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClickLabel = "Open image") { onOpen(image.dataUrl) },
                )
            } else {
                InlineImagePlaceholder("$label could not be shown")
            }
        }
        is InlineImageUi.Failed -> Surface(
            onClick = { onRequest(segment.path) },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text("${image.message} Tap to retry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        InlineImageUi.Loading, null -> InlineImagePlaceholder("Loading $label…")
    }
}

@Composable
private fun InlineImagePlaceholder(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** The images the viewer swipes through, opened at [index]. */
data class ImageViewerRequest(val items: List<ImageViewerItem>, val index: Int)

/** One viewer image; drafts are looked up live so edits land on the attachment. */
sealed interface ImageViewerItem {
    data class Draft(val imageId: String) : ImageViewerItem
    data class Remote(val source: String, val name: String) : ImageViewerItem
    data class Conversation(
        val dataUrl: String?,
        val name: String,
        val annotations: List<de.chennemann.agentic.domain.attachments.ImageAnnotation> = emptyList(),
        val burnedAnnotationCount: Int = 0,
    ) : ImageViewerItem
}

@Composable
internal fun ImageViewerHost(
    request: ImageViewerRequest,
    state: ChatUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = state.composer.imageAttachments.associateBy { it.id }
    val entries = request.items.mapIndexedNotNull { index, item ->
        when (item) {
            is ImageViewerItem.Remote -> {
                val content = state.inlineImages[item.source]
                item to ViewerImage(
                    key = "remote:$index",
                    dataUrl = (content as? InlineImageUi.Loaded)?.dataUrl,
                    name = item.name,
                    loadFailed = content is InlineImageUi.Failed,
                    annotationHint = "Closing adds this image with your annotations to the reply.",
                )
            }
            is ImageViewerItem.Draft -> drafts[item.imageId]?.let { image ->
                item to ViewerImage(
                    key = "draft:${image.id}",
                    dataUrl = image.previewDataUrl,
                    name = image.name,
                    annotations = image.annotations,
                    readOnlyCount = image.burnedAnnotationCount,
                    annotationHint = "Sending burns these marks into a copy and adds the comments to your message.",
                )
            }
            is ImageViewerItem.Conversation -> item to ViewerImage(
                key = "conversation:$index",
                dataUrl = item.dataUrl,
                name = item.name,
                annotations = item.annotations,
                readOnlyCount = item.annotations.size,
                annotationHint = "Closing adds this image with your annotations to the reply.",
            )
        }
    }
    if (entries.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val opened = request.items.getOrNull(request.index)
    ImageViewerDialog(
        images = entries.map { it.second },
        initialIndex = entries.indexOfFirst { it.first == opened }.coerceAtLeast(0),
        onImageRequested = { image ->
            (entries.firstOrNull { it.second.key == image.key }?.first as? ImageViewerItem.Remote)?.let {
                onEvent(ChatUiEvent.GalleryImageRequested(it.source))
            }
        },
        onClose = { marksByKey ->
            entries.forEach { (item, image) ->
                val marks = marksByKey[image.key] ?: return@forEach
                when (item) {
                    is ImageViewerItem.Remote -> {
                        val dataUrl = image.dataUrl
                        if (dataUrl != null && marks.isNotEmpty()) {
                            onEvent(ChatUiEvent.ConversationImageAnnotated(dataUrl, item.name, marks, 0))
                        }
                    }
                    is ImageViewerItem.Draft ->
                        if (marks != image.annotations) onEvent(ChatUiEvent.ImageAnnotationsSaved(item.imageId, marks))
                    is ImageViewerItem.Conversation -> {
                        val dataUrl = item.dataUrl
                        if (dataUrl != null && marks.size > item.annotations.size) {
                            onEvent(
                                ChatUiEvent.ConversationImageAnnotated(
                                    dataUrl = dataUrl,
                                    name = item.name,
                                    annotations = marks,
                                    burnedAnnotationCount = item.annotations.size,
                                ),
                            )
                        }
                    }
                }
            }
            onDismiss()
        },
    )
}

@Composable
private fun TurnTimingRow(timing: ChatTurnTimingUi) {
    var nowMillis by remember(timing.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timing.startedAt, timing.running) {
        while (timing.running) {
            delay(1_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val duration = formatTurnDuration(timing.startedAt, timing.completedAt, nowMillis) ?: return
    val label = when {
        timing.running -> "Working for $duration"
        timing.interrupted -> "Stopped after $duration"
        else -> "Worked for $duration"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (timing.running) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    is ChatTimelineItemUi.HtmlRender -> id to page
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

    is ChatTimelineItemUi.WorkSummary -> id to items.size
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
private val TurnBoundaryFlingThreshold = 1_200.dp

internal const val ChatTimelineTestTag = "chat-timeline"
internal const val FollowLatestTestTag = "follow-latest"
