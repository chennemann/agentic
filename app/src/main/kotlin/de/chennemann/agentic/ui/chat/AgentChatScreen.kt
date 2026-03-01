package de.chennemann.agentic.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.domain.session.ServerState
import de.chennemann.agentic.domain.session.ToolCallState
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.DoubleChevronDown
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.ui.components.MessageComposer
import de.chennemann.agentic.ui.components.ToolCallCard
import de.chennemann.agentic.ui.components.TurnTimer
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AgentChatScreen(
    state: ConversationUiState,
    onEvent: (ConversationEvent) -> Unit,
) {
    val density = LocalDensity.current
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    var follow by remember(state.title) { mutableStateOf(true) }
    val stepOpen = remember(state.focusedSessionId) { mutableStateMapOf<String, Boolean>() }
    val callOpen = remember(state.focusedSessionId) { mutableStateMapOf<String, Boolean>() }
    var viewportTop by remember { mutableIntStateOf(0) }
    var viewportBottom by remember { mutableIntStateOf(0) }
    val tools = remember { mutableStateMapOf<String, ToolPosition>() }
    val offset = if (state.canLoadMoreMessages || state.loadingMoreMessages) 1 else 0
    val workspaceHubSwipeThresholdPx = with(density) { 96.dp.toPx() }
    val snack = remember { SnackbarHostState() }
    val turns = state.turns

    val requestSessionFromQuickSwitch = { item: QuickSwitchState ->
        val cycle = item.cycleSessionIds
        val nextSessionId = if (item.active) {
            if (cycle.isEmpty()) {
                null
            } else {
                val index = cycle.indexOf(state.focusedSessionId)
                if (index < 0 || index == cycle.lastIndex) {
                    cycle.firstOrNull()
                } else {
                    cycle.getOrNull(index + 1)
                }
            }
        } else {
            item.primarySessionId
        }
        onEvent(
            ConversationEvent.SessionRequested(
                sessionId = nextSessionId,
                worktree = if (nextSessionId == null) item.worktree else null,
            )
        )
    }

    LaunchedEffect(list, turns.size, offset) {
        snapshotFlow { dragging to isAtEnd(list, turns.size + offset) }
            .distinctUntilChanged()
            .collect {
                if (it.first && !it.second) {
                    follow = false
                }
            }
    }

    LaunchedEffect(state.scroll) {
        follow = true
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snack.showSnackbar(message)
    }

    LaunchedEffect(list, turns.size, offset) {
        snapshotFlow { isAtEnd(list, turns.size + offset) }
            .distinctUntilChanged()
            .collect {
                if (it) {
                    follow = true
                }
            }
    }

    LaunchedEffect(
        state.title,
        turns.size,
        turns.lastOrNull()?.systemTexts?.lastOrNull()?.length,
        turns.lastOrNull()?.toolCalls?.size,
        turns.lastOrNull()?.toolCalls?.sumOf { it.details.size },
        turns.lastOrNull()?.toolCalls?.lastOrNull()?.id,
        turns.lastOrNull()?.toolCalls?.lastOrNull()?.details?.size,
        turns.lastOrNull()?.userText?.length,
        stepOpen[turns.lastOrNull()?.id],
        callOpen.entries.firstOrNull { it.value }?.key,
        follow,
        offset,
    ) {
        if (!follow) return@LaunchedEffect
        val count = turns.size + offset
        if (count <= 0) return@LaunchedEffect
        ensureEndVisible(list, count - 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(workspaceHubSwipeThresholdPx) {
                                var drag = 0f
                                var opened = false
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        drag = 0f
                                        opened = false
                                    },
                                    onHorizontalDrag = { _, dragAmount ->
                                        if (opened) return@detectHorizontalDragGestures
                                        drag += dragAmount
                                        if (drag > workspaceHubSwipeThresholdPx) {
                                            opened = true
                                            onEvent(ConversationEvent.WorkspaceHubRequested)
                                        }
                                    },
                                )
                            }
                            .onGloballyPositioned {
                                viewportTop = it.positionInRoot().y.roundToInt()
                                viewportBottom = (it.positionInRoot().y + it.size.height).roundToInt()
                            },
                        state = list,
                    ) {
                        if (state.canLoadMoreMessages || state.loadingMoreMessages) {
                            item("load-more") {
                                Button(
                                    onClick = { onEvent(ConversationEvent.MoreMessagesRequested) },
                                    enabled = !state.loadingMoreMessages,
                                ) {
                                    val label = if (state.loadingMoreMessages) {
                                        "Loading older messages..."
                                    } else {
                                        "Load older messages"
                                    }
                                    Text(label)
                                }
                            }
                        }
                        itemsIndexed(turns, key = { _, it -> it.id }) { index, turn ->
                            ConversationTurnItem(
                                turn = turn,
                                active = index == turns.lastIndex,
                                stepOpen = stepOpen[turn.id] == true,
                                callOpen = callOpen,
                                onToggleSteps = {
                                    stepOpen[turn.id] = stepOpen[turn.id] != true
                                },
                                onToggleToolCall = { callId ->
                                    if (callOpen[callId] == true) {
                                        callOpen.clear()
                                    } else {
                                        callOpen.clear()
                                        callOpen[callId] = true
                                    }
                                },
                                onToolCallSession = { onEvent(ConversationEvent.SubsessionRequested(it)) },
                                onEnsureToolVisible = { toolId, alignTop, topCompensation ->
                                    follow = false
                                    scope.launch {
                                        ensureToolVisible(
                                            list = list,
                                            toolId = toolId,
                                            tools = tools,
                                            viewportTop = viewportTop,
                                            viewportBottom = viewportBottom,
                                            alignTop = alignTop,
                                            topCompensation = topCompensation,
                                        )
                                    }
                                },
                                onToolLayout = { toolId, top, bottom ->
                                    tools[toolId] = ToolPosition(top = top, bottom = bottom)
                                },
                            )
                        }
                        item("bottom-spacer") {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    if (!follow) {
                        FollowLatestButton(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 8.dp),
                            onFollowLatest = {
                                scope.launch {
                                    follow = true
                                    val count = turns.size + offset
                                    if (count <= 0) return@launch
                                    ensureEndVisible(list, count - 1)
                                }
                            },
                        )
                    }
                }

                MessageComposer(
                    draft = state.draft,
                    mode = state.mode,
                    connected = state.status is ServerState.Connected,
                    suggestions = state.slashSuggestions,
                    quickSwitches = state.quickSwitches,
                    onDraftChange = { onEvent(ConversationEvent.DraftChanged(it)) },
                    onModeChange = { onEvent(ConversationEvent.ModeChanged(it)) },
                    onSend = { onEvent(ConversationEvent.MessageSubmitted) },
                    onReload = { onEvent(ConversationEvent.RefreshRequested) },
                    onCommandSelect = { onEvent(ConversationEvent.SlashCommandSelected(it.name)) },
                    onQuickSwitch = { key ->
                        val selected = state.quickSwitches.firstOrNull { it.key == key }
                        if (selected != null) {
                            requestSessionFromQuickSwitch(selected)
                        }
                    },
                    onQuickSwitchLongPress = { key -> onEvent(ConversationEvent.SessionsRequested(key)) },
                )
            }
        }
        SnackbarHost(
            hostState = snack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .imePadding(),
        )
    }
}

@Composable
private fun ConversationTurnItem(
    turn: ConversationTurnUiState,
    active: Boolean,
    stepOpen: Boolean,
    callOpen: Map<String, Boolean>,
    onToggleSteps: () -> Unit,
    onToggleToolCall: (String) -> Unit,
    onToolCallSession: (String) -> Unit,
    onEnsureToolVisible: (String, Boolean, Int) -> Unit,
    onToolLayout: (String, Int, Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        turn.userText?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        if (turn.userText != null || turn.toolCalls.isNotEmpty()) {
            ToolCallsSection(
                calls = turn.toolCalls,
                answerWriting = turn.answerWriting,
                startedAt = turn.startedAt,
                completedAt = turn.completedAt,
                active = active,
                open = stepOpen,
                callOpen = callOpen,
                onToggleSteps = onToggleSteps,
                onToggleToolCall = onToggleToolCall,
                onToolCallSession = onToolCallSession,
                onEnsureToolVisible = onEnsureToolVisible,
                onToolLayout = onToolLayout,
            )
        }

        turn.systemTexts.forEach {
            SelectionContainer {
                StreamingMarkdownText(
                    content = it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    inlineCode = SpanStyle(color = MaterialTheme.colorScheme.primary),
                )
            }
        }

        turn.startedAt?.let {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TurnTimer(
                    startedAt = it,
                    completedAt = turn.completedAt,
                )
            }
        }
    }
}

@Composable
private fun ToolCallsSection(
    calls: List<ToolCallState>,
    answerWriting: Boolean,
    startedAt: Long?,
    completedAt: Long?,
    active: Boolean,
    open: Boolean,
    callOpen: Map<String, Boolean>,
    onToggleSteps: () -> Unit,
    onToggleToolCall: (String) -> Unit,
    onToolCallSession: (String) -> Unit,
    onEnsureToolVisible: (String, Boolean, Int) -> Unit,
    onToolLayout: (String, Int, Int) -> Unit,
) {
    var lifted by remember { mutableStateOf<String?>(null) }
    var fallback by remember { mutableStateOf<ToolCallState?>(null) }
    val position = remember { mutableStateMapOf<String, ToolPosition>() }
    val collapsed = remember { mutableStateMapOf<String, Int>() }
    val latest = calls.lastOrNull()
    val activeCall = if (active && !answerWriting) latest ?: fallback else null
    val selected = lifted?.takeIf { id -> calls.any { it.id == id } }
    val card = remember {
        movableContentOf<ToolCallState, Boolean, () -> Unit, (() -> Unit)?> { call, expanded, onToggle, onOpenSession ->
            ToolCallCard(
                call = call,
                expanded = expanded,
                onToggle = onToggle,
                onOpenSession = onOpenSession,
            )
        }
    }
    LaunchedEffect(calls.size, calls.lastOrNull()?.id) {
        if (lifted != null && selected == null) {
            lifted = null
        }
    }
    LaunchedEffect(active, answerWriting, latest) {
        if (!active || answerWriting) {
            fallback = null
            return@LaunchedEffect
        }
        if (latest != null) {
            fallback = latest
        }
    }
    val count = if (calls.isEmpty() && activeCall != null) {
        1
    } else {
        calls.size
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (open) {
                        lifted = null
                    }
                    onToggleSteps()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (open) Icons.ChevronUp else Icons.ChevronDown,
                    "Toggle steps",
                )
                Text("${if (open) "Hide steps" else "Show steps"} • $count")
            }
            if (startedAt != null) {
                TurnTimer(
                    startedAt = startedAt,
                    completedAt = completedAt,
                )
            }
        }
        if (!open && activeCall != null) {
            val expanded = callOpen[activeCall.id] == true
            Box(
                modifier = Modifier.onGloballyPositioned {
                    val top = it.positionInRoot().y.roundToInt()
                    val bottom = (it.positionInRoot().y + it.size.height).roundToInt()
                    val height = bottom - top
                    position[activeCall.id] = ToolPosition(top, bottom)
                    if (!expanded) {
                        collapsed[activeCall.id] = height
                    }
                    onToolLayout(activeCall.id, top, bottom)
                },
            ) {
                card(
                    activeCall,
                    expanded,
                    {
                        lifted = activeCall.id
                        onToggleSteps()
                        if (!expanded) {
                            onToggleToolCall(activeCall.id)
                        }
                        onEnsureToolVisible(activeCall.id, true, 0)
                    },
                    activeCall.sessionId?.let { id -> { onToolCallSession(id) } },
                )
            }
        }
        AnimatedVisibility(
            visible = open && calls.isNotEmpty(),
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(240),
            ) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(240),
            ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                calls.forEach { call ->
                    val expanded = callOpen[call.id] == true
                    Box(
                        modifier = Modifier.onGloballyPositioned {
                            val top = it.positionInRoot().y.roundToInt()
                            val bottom = (it.positionInRoot().y + it.size.height).roundToInt()
                            val height = bottom - top
                            position[call.id] = ToolPosition(top, bottom)
                            if (!expanded) {
                                collapsed[call.id] = height
                            }
                            onToolLayout(call.id, top, bottom)
                        },
                    ) {
                        val previous = callOpen.entries.firstOrNull { it.value }?.key
                        val topCompensation = if (previous != null && previous != call.id && !expanded) {
                            val selectedPosition = position[previous]
                            val nextPosition = position[call.id]
                            val collapsedHeight = collapsed[previous]
                            if (
                                selectedPosition != null &&
                                nextPosition != null &&
                                collapsedHeight != null &&
                                selectedPosition.top < nextPosition.top
                            ) {
                                (selectedPosition.bottom - selectedPosition.top - collapsedHeight).coerceAtLeast(0)
                            } else {
                                0
                            }
                        } else {
                            0
                        }
                        val toggle = {
                            onToggleToolCall(call.id)
                            if (!expanded) {
                                onEnsureToolVisible(call.id, true, topCompensation)
                            }
                        }
                        val openSession = call.sessionId?.let { id -> { onToolCallSession(id) } }
                        if (call.id == selected) {
                            card(call, expanded, toggle, openSession)
                            return@Box
                        }
                        ToolCallCard(call = call, expanded = expanded, onToggle = toggle, onOpenSession = openSession)
                    }
                }
            }
        }
    }
}

private fun isAtEnd(list: androidx.compose.foundation.lazy.LazyListState, totalCount: Int): Boolean {
    if (totalCount <= 0) return true
    val info = list.layoutInfo
    val end = totalCount - 1
    val item = info.visibleItemsInfo.lastOrNull { it.index == end } ?: return false
    return item.offset + item.size <= info.viewportEndOffset + 8
}

private suspend fun ensureEndVisible(list: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    repeat(10) {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.lastOrNull { it.index == index }
        if (item == null) {
            list.scrollToItem(index)
            delay(16)
            return@repeat
        }
        val overflow = item.offset + item.size - info.viewportEndOffset
        if (overflow <= 0) {
            return
        }
        list.animateScrollBy(overflow.toFloat())
        delay(16)
    }
}

private suspend fun ensureToolVisible(
    list: androidx.compose.foundation.lazy.LazyListState,
    toolId: String,
    tools: Map<String, ToolPosition>,
    viewportTop: Int,
    viewportBottom: Int,
    alignTop: Boolean,
    topCompensation: Int,
) {
    if (viewportBottom <= viewportTop) return
    if (alignTop && topCompensation > 0) {
        delay(16)
        val before = tools[toolId] ?: return
        val predicted = (before.top - viewportTop - topCompensation).toFloat()
        if (predicted != 0f) {
            list.animateScrollBy(predicted)
        }
        delay(220)
        val settled = tools[toolId] ?: return
        val correction = (settled.top - viewportTop).toFloat()
        if (correction != 0f) {
            list.animateScrollBy(correction)
        }
        return
    }
    repeat(8) {
        delay(16)
        val tool = tools[toolId] ?: return
        val height = tool.bottom - tool.top
        val viewportHeight = viewportBottom - viewportTop
        val delta = when {
            alignTop -> (tool.top - viewportTop - topCompensation).toFloat()
            height >= viewportHeight -> (tool.top - viewportTop).toFloat()
            tool.bottom > viewportBottom -> (tool.bottom - viewportBottom).toFloat()
            tool.top < viewportTop -> (tool.top - viewportTop).toFloat()
            else -> return
        }
        if (delta == 0f) return
        list.animateScrollBy(delta)
    }
}

private data class ToolPosition(
    val top: Int,
    val bottom: Int,
)

@Composable
private fun FollowLatestButton(
    modifier: Modifier = Modifier,
    onFollowLatest: () -> Unit,
) {
    SmallFloatingActionButton(
        onClick = onFollowLatest,
        modifier = modifier,
    ) {
        Icon(Icons.DoubleChevronDown, "Follow latest")
    }
}
