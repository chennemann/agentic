package de.chennemann.agentic.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
    onReconnect: () -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    var confirmation by remember(state.selectedId) { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = menuExpanded) { menuExpanded = false }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal")
                        Text(state.status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = onCreate, enabled = !state.busy) { Text("New") }
                    Box {
                        TextButton(onClick = { menuExpanded = true }, enabled = !state.busy) { Text("More") }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            // Keep IME focus: resizing the remote PTY can redraw content after Clear.
                            properties = PopupProperties(focusable = false)
                        ) {
                            DropdownMenuItem(text = { Text("Reconnect") }, onClick = {
                                menuExpanded = false
                                onReconnect()
                            })
                            DropdownMenuItem(text = {
                                Text("Clear screen")
                            }, enabled = state.selectedId != null, onClick = {
                                menuExpanded =
                                    false
                                onClear()
                            })
                            DropdownMenuItem(text = {
                                Text("Restart terminal")
                            }, enabled = state.selectedId != null, onClick = {
                                menuExpanded =
                                    false
                                confirmation = "Restart"
                            })
                            DropdownMenuItem(text = {
                                Text("Close terminal")
                            }, enabled = state.selectedId != null, onClick = {
                                menuExpanded =
                                    false
                                confirmation = "Close"
                            })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (state.terminals.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.terminals.forEach { terminal ->
                        FilterChip(
                            selected = terminal.id == state.selectedId,
                            onClick = { if (terminal.id != state.selectedId) onSelect(terminal.id) },
                            enabled = !state.busy,
                            label = { Text(terminal.label) }
                        )
                    }
                }
            }
            state.errorMessage?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onReconnect, enabled = !state.busy) { Text("Reconnect") }
                }
            }
            val display = state.display as? ConnectBotTerminalDisplay
            if (display == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.selectedId ==
                        null
                    ) {
                        Button(onClick = onCreate, enabled = !state.busy) { Text("Open terminal") }
                    } else {
                        Text("Connecting to terminal…")
                    }
                }
            } else {
                key(display) {
                    TerminalSurface(display, state.canInput, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("$action terminal?") },
            text = { Text("This stops the shell and any programs running in this session.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    if (action ==
                        "Restart"
                    ) {
                        onRestart()
                    } else {
                        onClose()
                    }
                }) { Text(action) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TerminalSurface(display: ConnectBotTerminalDisplay, canInput: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val modifiers = remember { TerminalModifiers() }
    var keyboard by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<SelectionController?>(null) }
    var pasteConfirmation by remember { mutableStateOf<String?>(null) }
    val paste = {
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.contains('\n') || text.contains('\r')) {
            pasteConfirmation = text
        } else if (canInput) {
            display.emulator.pasteText(text)
        }
    }
    Column(modifier) {
        Terminal(
            terminalEmulator = display.emulator,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal-emulator"),
            initialFontSize = 13.sp,
            minFontSize = 8.sp,
            keyboardEnabled = canInput,
            showSoftKeyboard = keyboard && canInput,
            onTerminalTap = { keyboard = true },
            modifierManager = modifiers,
            onSelectionControllerAvailable = { selection = it },
            onPasteRequest = { if (canInput) paste() }
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                keyboard = !keyboard
            }, enabled = canInput) { Text(if (keyboard) "Hide keyboard" else "Keyboard") }
            FilterChip(selected = modifiers.ctrl, onClick = {
                modifiers.ctrl = !modifiers.ctrl
            }, enabled = canInput, label = { Text("Ctrl") })
            FilterChip(selected = modifiers.alt, onClick = {
                modifiers.alt = !modifiers.alt
            }, enabled = canInput, label = { Text("Alt") })
            listOf(
                "Esc" to VTermKey.ESCAPE, "Tab" to VTermKey.TAB, "Up" to VTermKey.UP, "Down" to VTermKey.DOWN,
                "Left" to VTermKey.LEFT, "Right" to VTermKey.RIGHT, "Home" to VTermKey.HOME, "End" to VTermKey.END,
                "PgUp" to VTermKey.PAGEUP, "PgDn" to VTermKey.PAGEDOWN
            ).forEach { (label, code) ->
                TextButton(onClick = {
                    display.emulator.dispatchKey(modifiers.mask(), code)
                    modifiers.clearTransients()
                }, enabled = canInput) { Text(label) }
            }
            TextButton(onClick = {
                display.emulator.dispatchCharacter(4, 'c'.code)
            }, enabled = canInput) { Text("Ctrl+C") }
            TextButton(onClick = { selection?.startSelection() }) { Text("Select") }
            TextButton(onClick = {
                selection?.copySelection()?.takeIf {
                    it.isNotEmpty()
                }?.let { clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", it)) }
                selection?.clearSelection()
            }) { Text("Copy") }
            TextButton(onClick = { paste() }, enabled = canInput) { Text("Paste") }
        }
    }
    pasteConfirmation?.let { text ->
        AlertDialog(
            onDismissRequest = { pasteConfirmation = null },
            title = { Text("Paste multiple lines?") },
            text = { Text("This may execute commands in the shell.\n\n${text.take(300)}") },
            confirmButton = {
                TextButton(onClick = {
                    pasteConfirmation = null
                    if (canInput) display.emulator.pasteText(text)
                }) { Text("Paste") }
            },
            dismissButton = { TextButton(onClick = { pasteConfirmation = null }) { Text("Cancel") } }
        )
    }
}

private class TerminalModifiers : ModifierManager {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = alt
    override fun isShiftActive() = false
    override fun clearTransients() {
        ctrl = false
        alt = false
    }
    fun mask() = (if (ctrl) 4 else 0) or (if (alt) 2 else 0)
}
