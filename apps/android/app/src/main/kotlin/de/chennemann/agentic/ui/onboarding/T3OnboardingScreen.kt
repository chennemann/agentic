package de.chennemann.agentic.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.ui.theme.MobileTheme

@Composable
fun T3OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var qrScannerVisible by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.savedEnvironments.any { it.active }) {
            TextButton(
                onClick = { onEvent(OnboardingUiEvent.CloseRequested) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Back to chat")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Connect to T3 Code",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Pair this device with an environment, or connect to a server you already trust.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.savedEnvironments.isNotEmpty()) {
            SavedEnvironmentSection(
                environments = state.savedEnvironments,
                enabled = !state.working,
                onSelect = { onEvent(OnboardingUiEvent.SavedEnvironmentSelected(it)) },
                onRemove = { onEvent(OnboardingUiEvent.SavedEnvironmentRemovalRequested(it)) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OnboardingInputKindUi.entries.forEach { kind ->
                    FilterChip(
                        selected = state.inputKind == kind,
                        onClick = { onEvent(OnboardingUiEvent.InputKindSelected(kind)) },
                        label = { Text(kind.label) },
                        enabled = !state.working,
                    )
                }
            }
            OutlinedTextField(
                value = state.endpointInput,
                onValueChange = { onEvent(OnboardingUiEvent.EndpointChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.working,
                singleLine = true,
                label = {
                    Text(
                        if (state.inputKind == OnboardingInputKindUi.PAIRING_LINK) {
                            "Paste pairing link"
                        } else {
                            "T3 server URL"
                        },
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onEvent(OnboardingUiEvent.EndpointSubmitted) },
                    enabled = state.endpointInput.isNotBlank() && !state.working,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.working) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Continue")
                    }
                }
                OutlinedButton(
                    onClick = {
                        qrScannerVisible = true
                        onEvent(OnboardingUiEvent.QrScanRequested)
                    },
                    enabled = !state.working,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan QR")
                }
            }
        }

        state.environmentPreview?.let {
            EnvironmentPreviewCard(
                preview = it,
                enabled = !state.working,
                onConfirm = { onEvent(OnboardingUiEvent.PairingConfirmed) },
            )
        }

        state.error?.let {
            OnboardingErrorCard(
                error = it,
                onDismiss = { onEvent(OnboardingUiEvent.ErrorDismissed) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    state.cleartextConfirmation?.let {
        AlertDialog(
            onDismissRequest = { onEvent(OnboardingUiEvent.CleartextDeclined) },
            title = { Text("Trust this private connection?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it.endpointLabel)
                    Text(it.explanation)
                }
            },
            confirmButton = {
                TextButton(onClick = { onEvent(OnboardingUiEvent.CleartextConfirmed) }) {
                    Text("Trust and continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(OnboardingUiEvent.CleartextDeclined) }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (qrScannerVisible) {
        QrScannerDialog(
            onScanned = {
                qrScannerVisible = false
                onEvent(OnboardingUiEvent.QrCodeScanned(it))
            },
            onDismiss = { qrScannerVisible = false },
        )
    }
}

@Composable
private fun SavedEnvironmentSection(
    environments: List<SavedEnvironmentUi>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Saved environments",
            style = MaterialTheme.typography.titleSmall,
        )
        environments.forEach { environment ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (environment.active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = environment.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = environment.endpointLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onSelect(environment.id) },
                        enabled = enabled,
                    ) {
                        Text(if (environment.active) "Open" else "Use")
                    }
                    TextButton(
                        onClick = { onRemove(environment.id) },
                        enabled = enabled,
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentPreviewCard(
    preview: EnvironmentPreviewUi,
    enabled: Boolean,
    onConfirm: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(preview.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "${preview.platform} • ${preview.version}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                preview.endpointLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pair with this environment")
            }
        }
    }
}

@Composable
private fun OnboardingErrorCard(
    error: OnboardingErrorUi,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = errorTitle(error),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

private fun errorTitle(error: OnboardingErrorUi): String = when (error) {
    is OnboardingErrorUi.InvalidPairing -> "Invalid pairing link"
    is OnboardingErrorUi.ExpiredPairing -> "Pairing link expired"
    is OnboardingErrorUi.UnsupportedServer -> "Server not supported"
    is OnboardingErrorUi.PublicCleartextRejected -> "Secure connection required"
    is OnboardingErrorUi.ConnectionFailed -> "Could not connect"
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPreview() {
    MobileTheme {
        T3OnboardingScreen(
            state = OnboardingUiState(
                endpointInput = "https://studio.example.test/pair#token",
                savedEnvironments = listOf(
                    SavedEnvironmentUi(
                        id = "home",
                        label = "Home workstation",
                        endpointLabel = "https://t3.home",
                    ),
                ),
                environmentPreview = EnvironmentPreviewUi(
                    environmentId = "studio",
                    label = "Studio",
                    platform = "Windows x64",
                    version = "1.4.0",
                    endpointLabel = "https://studio.example.test",
                ),
            ),
            onEvent = {},
        )
    }
}
