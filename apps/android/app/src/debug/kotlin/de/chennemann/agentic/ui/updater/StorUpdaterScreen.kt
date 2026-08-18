package de.chennemann.agentic.ui.updater

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.BuildConfig
import dev.stor.sdk.ArtifactManifest
import dev.stor.sdk.InstallResult
import dev.stor.sdk.StorClient
import dev.stor.sdk.StorUpdater
import dev.stor.sdk.UpdateCheckResult
import kotlinx.coroutines.launch

private const val REGISTRY_URL = "https://stor.local.chennemann.de"
private const val CHANNEL = "debug"

private class StorUpdaterController(context: Context) {
    private val token = BuildConfig.STOR_READ_TOKEN
    private val client = StorClient(registryUrl = REGISTRY_URL, readToken = token.ifBlank { null })
    private val updater = StorUpdater(context.applicationContext)
    private var artifact: ArtifactManifest? = null

    var state by mutableStateOf(UpdaterUiState(installedVersionCode = BuildConfig.VERSION_CODE.toLong()))
        private set

    suspend fun checkForUpdate(applicationId: String) {
        state = state.copy(checking = true, status = "Checking for an update…", permissionSettingsEnabled = false)
        when (val result = updater.checkForUpdate(client, applicationId, CHANNEL)) {
            is UpdateCheckResult.UpdateAvailable -> {
                artifact = result.artifact
                state = state.withResult(UpdaterResult.UpdateAvailable(result.artifact.versionCode))
            }
            is UpdateCheckResult.UpToDate -> {
                artifact = null
                state = state.withResult(UpdaterResult.UpToDate(result.installedVersionCode, result.latestVersionCode))
            }
            is UpdateCheckResult.Incompatible -> {
                artifact = null
                state = state.withResult(UpdaterResult.Incompatible(result.artifact.versionCode, result.reason), listOf(token))
            }
            is UpdateCheckResult.Failed -> {
                artifact = null
                state = state.withResult(UpdaterResult.Failed(result.cause.message ?: "Update check failed."), listOf(token))
            }
        }
    }

    suspend fun downloadAndInstall() {
        val selectedArtifact = artifact ?: return
        state = state.copy(installing = true, status = "Downloading and verifying the update…")
        state = when (val result = updater.downloadAndInstall(client, selectedArtifact)) {
            InstallResult.InstallPromptOpened -> state.withResult(UpdaterResult.InstallPromptOpened)
            InstallResult.PermissionRequired -> state.withResult(UpdaterResult.PermissionRequired)
            is InstallResult.Failed -> state.withResult(
                UpdaterResult.Failed(result.cause.message ?: "Update installation failed."),
                listOf(token),
            )
        }
    }

    fun openInstallPermissionSettings() = updater.openInstallPermissionSettings()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorUpdaterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { StorUpdaterController(context) }
    val scope = rememberCoroutineScope()
    val state = controller.state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Development updates") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Installed version code: ${state.installedVersionCode}")
            Text("Latest Stor version code: ${state.latestVersionCode?.toString() ?: "Not checked"}")
            Text(state.status)
            Button(
                onClick = { scope.launch { controller.checkForUpdate(context.applicationContext.packageName) } },
                enabled = !state.checking && !state.installing,
            ) { Text(if (state.checking) "Checking…" else "Check for update") }
            if (state.installationEnabled) {
                Button(
                    onClick = { scope.launch { controller.downloadAndInstall() } },
                    enabled = !state.installing,
                ) { Text(if (state.installing) "Downloading…" else "Download and install") }
            }
            if (state.permissionSettingsEnabled) {
                Button(onClick = controller::openInstallPermissionSettings) { Text("Allow installation") }
            }
        }
    }
}
