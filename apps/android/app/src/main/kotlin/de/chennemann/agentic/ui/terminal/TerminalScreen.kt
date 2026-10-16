package de.chennemann.agentic.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import expo.modules.t3terminal.T3TerminalView

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
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Column {
                        Text("Terminal")
                        Text(
                            state.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            val display = state.display as? GhosttyTerminalDisplay
            if (display == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.selectedId ==
                        null
                    ) {
                        Button(onClick = onCreate, enabled = !state.busy) { Text("Open terminal") }
                    } else {
                        Text("Connecting to terminal\u2026")
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
private fun TerminalSurface(display: GhosttyTerminalDisplay, canInput: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val appearance = rememberTerminalAppearance()
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var keyboard by remember { mutableStateOf(false) }
    var nativeView by remember { mutableStateOf<T3TerminalView?>(null) }
    val revision by display.session.revision.collectAsStateWithLifecycle()
    var pasteConfirmation by remember { mutableStateOf<String?>(null) }
    val paste = {
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.contains('\n') || text.contains('\r')) {
            pasteConfirmation = text
        } else if (canInput) {
            display.paste(text)
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        AndroidView(
            factory = { viewContext ->
                T3TerminalView(viewContext, display.session).also { nativeView = it }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal-emulator"),
            onRelease = { view ->
                view.cleanup()
                if (nativeView === view) nativeView = null
            },
            update = { view ->
                view.onInput = { if (canInput) display.input(it) }
                view.onResize = display::resize
                view.onResponse = display::reply
                view.onPaste = { if (canInput) paste() }
                view.inputEnabled = canInput
                appearance.applyTo(view)
                // Read the revision to refresh after output; no accumulated output buffer.
                view.refresh(revision)
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = {
                keyboard = !keyboard
                if (keyboard) nativeView?.showKeyboard() else nativeView?.dismissKeyboard()
            }, enabled = canInput) { Text(if (keyboard) "Hide keyboard" else "Keyboard") }
            FilterChip(selected = display.ctrl, onClick = {
                display.ctrl = !display.ctrl
            }, enabled = canInput, label = { Text("Ctrl") })
            FilterChip(selected = display.alt, onClick = {
                display.alt = !display.alt
            }, enabled = canInput, label = { Text("Alt") })
            listOf(
                "Esc" to "\u001b", "Tab" to "\t", "Up" to "\u001b[A", "Down" to "\u001b[B",
                "Left" to "\u001b[D", "Right" to "\u001b[C", "Home" to "\u001b[H", "End" to "\u001b[F",
                "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~"
            ).forEach { (label, code) ->
                TextButton(onClick = { display.input(code) }, enabled = canInput) { Text(label) }
            }
            TextButton(onClick = { display.input("\u0003") }, enabled = canInput) { Text("Ctrl+C") }
            TextButton(onClick = { nativeView?.selectAll() }) { Text("Select") }
            TextButton(onClick = {
                nativeView?.selectionText()?.takeIf(String::isNotEmpty)?.let {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", it))
                }
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
                    if (canInput) display.paste(text)
                }) { Text("Paste") }
            },
            dismissButton = { TextButton(onClick = { pasteConfirmation = null }) { Text("Cancel") } }
        )
    }
}
