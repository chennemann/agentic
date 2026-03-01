package de.chennemann.agentic.ui.components

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import de.chennemann.agentic.domain.session.CommandState
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.CircleSlash
import de.chennemann.agentic.icons.Refresh
import de.chennemann.agentic.icons.Send
import de.chennemann.agentic.ui.chat.QuickSwitchState
import de.chennemann.agentic.ui.chat.ConversationMode
import de.chennemann.agentic.ui.theme.MobileTheme

private val ModeSwipeThreshold = 28.dp

private fun cycleMode(mode: ConversationMode): ConversationMode {
    return when (mode) {
        ConversationMode.PLAN -> ConversationMode.BUILD
        ConversationMode.BUILD -> ConversationMode.PLAN
    }
}

private fun modeLabel(mode: ConversationMode): String {
    return when (mode) {
        ConversationMode.PLAN -> "Plan"
        ConversationMode.BUILD -> "Build"
    }
}

@Composable
fun MessageComposer(
    draft: String,
    mode: ConversationMode,
    connected: Boolean,
    suggestions: List<CommandState>,
    quickSwitches: List<QuickSwitchState>,
    onDraftChange: (String) -> Unit,
    onModeChange: (ConversationMode) -> Unit,
    onSend: () -> Unit,
    onReload: () -> Unit,
    onCommandSelect: (CommandState) -> Unit,
    onQuickSwitch: (String) -> Unit,
    onQuickSwitchLongPress: (String) -> Unit,
) {
    var commandOpen by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableLongStateOf(0L) }
    var field by remember { mutableStateOf(TextFieldValue(draft)) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(draft) {
        if (draft == field.text) return@LaunchedEffect
        field = TextFieldValue(
            text = draft,
            selection = TextRange(draft.length),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConversationMode.entries.forEach {
                    val selected = it == mode
                    Text(
                        text = modeLabel(it),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier
                            .clickable {
                                if (!selected) onModeChange(it)
                            },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = field,
                        onValueChange = {
                            field = it
                            onDraftChange(it.text)
                            commandOpen = commandOpen && (it.text.isBlank() || it.text.startsWith("/"))
                        },
                        modifier = Modifier
                            .fillMaxWidth().padding(end = 48.dp)
                            .pointerInput(mode) {
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
                                        if (delta > -threshold && delta < threshold) return@detectHorizontalDragGestures
                                        changed = true
                                        onModeChange(cycleMode(mode))
                                    },
                                )
                            },
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

                    if (draft.isEmpty()) {
                        DropdownMenu(
                            expanded = commandOpen && suggestions.isNotEmpty(),
                            onDismissRequest = {
                                commandOpen = false
                                dismissedAt = SystemClock.elapsedRealtime()
                            },
                            properties = PopupProperties(focusable = false),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                        ) {
                            suggestions.forEach { command ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = "/${command.name}",
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (!command.description.isNullOrBlank()) {
                                                Text(
                                                    text = command.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onCommandSelect(command)
                                        commandOpen = false
                                    },
                                )
                            }
                        }

                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 4.dp, end = 64.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val now = SystemClock.elapsedRealtime()
                                    if (commandOpen) {
                                        commandOpen = false
                                        dismissedAt = now
                                        return@IconButton
                                    }
                                    if (now - dismissedAt < CommandReopenDelayMs) {
                                        return@IconButton
                                    }
                                    commandOpen = true
                                },
                                modifier = Modifier
                                    .focusProperties { canFocus = false }
                                    .size(48.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.CircleSlash,
                                    contentDescription = "Toggle command suggestions",
                                )
                            }
                        }
                    }

                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 4.dp, end = 16.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (connected) {
                                    if (draft != field.text) {
                                        onDraftChange(field.text)
                                    }
                                    onSend()
                                    if (field.text.isNotBlank()) {
                                        commandOpen = false
                                        focus.clearFocus()
                                        keyboard?.hide()
                                    }
                                } else {
                                    onReload()
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            if (connected) {
                                Icon(Icons.Send, "Send message")
                            } else {
                                Icon(Icons.Refresh, "Reload")
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (quickSwitches.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(top = 0.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(quickSwitches, key = { it.key }) { item ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            QuickSwitchButton(
                                item = item,
                                onClick = { onQuickSwitch(item.key) },
                                onLongPress = { onQuickSwitchLongPress(item.key) },
                            )
                            Box(
                                modifier = Modifier.height(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (item.unread > 0) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        repeat(minOf(item.unread, 6)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = CircleShape,
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickSwitchButton(
    item: QuickSwitchState,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val container = if (item.active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (item.active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (item.processing) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                strokeWidth = 2.dp,
            )
        }
        Surface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier
                .size(36.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.label)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageComposerPreview() {
    var mode by remember { mutableStateOf(ConversationMode.BUILD) }
    var draft by remember { mutableStateOf("") }
    MobileTheme {
        MessageComposer(
            draft = draft,
            mode = mode,
            connected = true,
            suggestions = listOf(
                CommandState(
                    name = "help",
                    description = "Show all commands",
                )
            ),
            quickSwitches = listOf(
                QuickSwitchState(
                    key = "/repo/main",
                    worktree = "/repo/main",
                    label = "M",
                    project = "main",
                    primarySessionId = "s-main",
                    cycleSessionIds = listOf("s-main", "s-main-2"),
                    active = true,
                    processing = false,
                    unread = 0,
                ),
                QuickSwitchState(
                    key = "/repo/docs",
                    worktree = "/repo/docs",
                    label = "D",
                    project = "docs",
                    primarySessionId = null,
                    cycleSessionIds = emptyList(),
                    active = false,
                    processing = true,
                    unread = 2,
                ),
            ),
            onDraftChange = { draft = it },
            onModeChange = { mode = it },
            onSend = {},
            onReload = {},
            onCommandSelect = {},
            onQuickSwitch = {},
            onQuickSwitchLongPress = {},
        )
    }
}

private const val CommandReopenDelayMs = 500L
