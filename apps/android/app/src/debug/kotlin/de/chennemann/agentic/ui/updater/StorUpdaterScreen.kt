package de.chennemann.agentic.ui.updater

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.BuildConfig
import de.chennemann.agentic.domain.preferences.InterfacePreferencesRepository
import dev.stor.sdk.ArtifactManifest
import dev.stor.sdk.InstallResult
import dev.stor.sdk.StorClient
import dev.stor.sdk.StorClientCertificates
import dev.stor.sdk.StorUpdater
import dev.stor.sdk.UpdateCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

class StorUpdaterViewModel(context: Context, private val preferences: InterfacePreferencesRepository) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val updater = StorUpdater(applicationContext)
    private var artifact: ArtifactManifest? = null
    var alias by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var selecting by mutableStateOf(false)
        private set
    var state by mutableStateOf(UpdaterUiState(installedVersionCode = BuildConfig.VERSION_CODE.toLong()))
        private set

    init {
        viewModelScope.launch {
            alias = preferences.preferences.first().storCertificateAlias
            ready = true
        }
    }

    fun chooseCertificate(activity: Activity) {
        if (!ready || selecting || state.checking || state.installing) return
        selecting = true
        viewModelScope.launch {
            try {
                val selected = StorClientCertificates.choose(activity, selectedAlias = alias) ?: return@launch
                preferences.setStorCertificateAlias(selected)
                alias = selected
                artifact = null
                state = UpdaterUiState(installedVersionCode = state.installedVersionCode, status = "Certificate selected. Check for updates.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.withResult(UpdaterResult.Failed("Certificate selection failed. Select a certificate again."))
            } finally {
                selecting = false
            }
        }
    }

    /** Each operation owns and closes a client recreated from the persisted KeyChain alias. */
    private suspend fun <T> withCertificate(block: suspend (StorClient) -> T): T = withContext(Dispatchers.IO) {
        val client = StorClientCertificates.client(applicationContext, requireNotNull(alias))
        try { block(client) } finally { client.close() }
    }

    fun checkForUpdate() {
        if (!ready || alias == null || selecting || state.checking || state.installing) return
        artifact = null
        state = state.copy(checking = true, status = "Checking for updates...", installationEnabled = false, permissionSettingsEnabled = false)
        viewModelScope.launch {
            try {
                when (val result = withCertificate { updater.checkForUpdate(it) }) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        artifact = result.artifact
                        state = state.withResult(UpdaterResult.UpdateAvailable(result.artifact.versionCode))
                    }
                    is UpdateCheckResult.UpToDate -> state = state.withResult(UpdaterResult.UpToDate(result.installedVersionCode, result.latestVersionCode))
                    is UpdateCheckResult.Incompatible -> state = state.withResult(UpdaterResult.Incompatible(result.artifact.versionCode, result.reason))
                    is UpdateCheckResult.Failed -> failed()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed()
            }
        }
    }

    fun downloadAndInstall() {
        val selectedArtifact = artifact ?: return
        if (alias == null || selecting || state.checking || state.installing) return
        state = state.copy(installing = true, status = "Downloading and verifying the update...")
        viewModelScope.launch {
            try {
                state = when (val result = withCertificate { updater.downloadAndInstall(it, selectedArtifact) }) {
                    InstallResult.InstallPromptOpened -> state.withResult(UpdaterResult.InstallPromptOpened)
                    InstallResult.PermissionRequired -> {
                        updater.openInstallPermissionSettings()
                        state.withResult(UpdaterResult.PermissionRequired)
                    }
                    is InstallResult.Failed -> state.withResult(UpdaterResult.Failed("Update installation failed. Check your connection or select the certificate again."))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed()
            }
        }
    }

    private fun failed() {
        artifact = null
        state = state.withResult(UpdaterResult.Failed("Could not access Stor. Check your connection or select the certificate again if access was revoked."))
    }

    fun openInstallPermissionSettings() = updater.openInstallPermissionSettings()
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorUpdaterScreen(onBack: () -> Unit, controller: StorUpdaterViewModel = koinViewModel()) {
    val activity = LocalContext.current.activity()
    val state = controller.state
    var confirmInstall by remember { mutableStateOf(false) }
    val busy = !controller.ready || controller.selecting || state.checking || state.installing

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text("Download and install update?") },
            text = { Text("Download version ${state.latestVersionCode}. Android will ask you to confirm installation.") },
            confirmButton = {
                TextButton(onClick = { confirmInstall = false; controller.downloadAndInstall() }) { Text("Download and install") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } },
        )
    }
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
            Text("Stor · debug channel")
            Text("Install your client identity in Android's credential store, then select it here.")
            Text("Certificate: ${controller.alias ?: "Not selected"}")
            Button(onClick = { activity?.let(controller::chooseCertificate) }, enabled = !busy && activity != null) {
                Text(if (controller.alias == null) "Select certificate" else "Change certificate")
            }
            Text("Installed version code: ${state.installedVersionCode}")
            Text("Latest Stor version code: ${state.latestVersionCode?.toString() ?: "Not checked"}")
            Text(state.status)
            Button(onClick = controller::checkForUpdate, enabled = !busy && controller.alias != null) {
                Text(if (state.checking) "Checking..." else "Check for updates")
            }
            if (state.installationEnabled) {
                Button(onClick = { confirmInstall = true }, enabled = !busy) {
                    Text(if (state.installing) "Downloading..." else if (state.permissionSettingsEnabled) "Retry installation" else "Download and install")
                }
            }
            if (state.permissionSettingsEnabled) {
                Button(onClick = controller::openInstallPermissionSettings, enabled = !busy) { Text("Allow installation") }
            }
        }
    }
}
