package de.chennemann.agentic.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val outputScroll = rememberScrollState()
    LaunchedEffect(state.output) { outputScroll.scrollTo(outputScroll.maxValue) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal · ${state.status}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onCreate) { Text("New") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.terminals.forEach { terminal ->
                    FilterChip(
                        selected = terminal.terminalId == state.selectedId,
                        onClick = { onSelect(terminal.terminalId) },
                        label = { Text(terminal.label.ifBlank { terminal.terminalId }) },
                    )
                }
            }
            state.errorMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            Text(
                text = state.output.ifEmpty { "No terminal output" },
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(outputScroll),
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Command") },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSend(input); input = "" },
                    enabled = state.selectedId != null && input.isNotEmpty(),
                ) { Text("Send") }
                OutlinedButton(onClick = onClear, enabled = state.selectedId != null) { Text("Clear") }
                OutlinedButton(onClick = onRestart, enabled = state.selectedId != null) { Text("Restart") }
                OutlinedButton(onClick = onClose, enabled = state.selectedId != null) { Text("Close") }
            }
        }
    }
}
