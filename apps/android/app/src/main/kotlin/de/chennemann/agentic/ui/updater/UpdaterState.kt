package de.chennemann.agentic.ui.updater

data class UpdaterUiState(
    val installedVersionCode: Long,
    val latestVersionCode: Long? = null,
    val status: String = "Ready to check for updates.",
    val checking: Boolean = false,
    val installing: Boolean = false,
    val installationEnabled: Boolean = false,
    val permissionSettingsEnabled: Boolean = false,
)

sealed interface UpdaterResult {
    data class UpdateAvailable(val latestVersionCode: Long) : UpdaterResult
    data class UpToDate(val installedVersionCode: Long, val latestVersionCode: Long) : UpdaterResult
    data class Incompatible(val latestVersionCode: Long, val reason: String) : UpdaterResult
    data class Failed(val message: String) : UpdaterResult
    data object InstallPromptOpened : UpdaterResult
    data object PermissionRequired : UpdaterResult
}

fun UpdaterUiState.withResult(result: UpdaterResult, secrets: Iterable<String> = emptyList()): UpdaterUiState =
    when (result) {
        is UpdaterResult.UpdateAvailable -> copy(
            latestVersionCode = result.latestVersionCode,
            status = "An update is available.",
            checking = false,
            installing = false,
            installationEnabled = true,
            permissionSettingsEnabled = false,
        )
        is UpdaterResult.UpToDate -> copy(
            installedVersionCode = result.installedVersionCode,
            latestVersionCode = result.latestVersionCode,
            status = "The installed version is up to date.",
            checking = false,
            installing = false,
            installationEnabled = false,
            permissionSettingsEnabled = false,
        )
        is UpdaterResult.Incompatible -> copy(
            latestVersionCode = result.latestVersionCode,
            status = result.reason.sanitized(secrets),
            checking = false,
            installing = false,
            installationEnabled = false,
            permissionSettingsEnabled = false,
        )
        is UpdaterResult.Failed -> copy(
            status = result.message.sanitized(secrets),
            checking = false,
            installing = false,
            installationEnabled = false,
            permissionSettingsEnabled = false,
        )
        UpdaterResult.InstallPromptOpened -> copy(
            status = "Android's installation confirmation is open.",
            installing = false,
            permissionSettingsEnabled = false,
        )
        UpdaterResult.PermissionRequired -> copy(
            status = "Allow this app to install unknown apps, then try again.",
            installing = false,
            permissionSettingsEnabled = true,
        )
    }

private fun String.sanitized(secrets: Iterable<String>): String {
    var sanitized = this
    secrets.filter(String::isNotBlank).forEach { sanitized = sanitized.replace(it, "[redacted]") }
    sanitized = sanitized.replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
    return sanitized.take(500)
}
