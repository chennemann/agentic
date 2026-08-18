package de.chennemann.agentic.ui.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdaterStateTest {
    private val initial = UpdaterUiState(installedVersionCode = 10)

    @Test
    fun `available update enables installation`() {
        val state = initial.withResult(UpdaterResult.UpdateAvailable(latestVersionCode = 11))

        assertTrue(state.installationEnabled)
        assertEquals(11, state.latestVersionCode)
    }

    @Test
    fun `up to date result does not enable installation`() {
        val state = initial.withResult(UpdaterResult.UpToDate(10, 10))

        assertFalse(state.installationEnabled)
        assertEquals(10, state.latestVersionCode)
    }

    @Test
    fun `incompatible result displays reason`() {
        val state = initial.withResult(UpdaterResult.Incompatible(11, "Requires Android API 40"))

        assertEquals("Requires Android API 40", state.status)
        assertFalse(state.installationEnabled)
    }

    @Test
    fun `permission required exposes settings action`() {
        val state = initial.withResult(UpdaterResult.PermissionRequired)

        assertTrue(state.permissionSettingsEnabled)
    }

    @Test
    fun `failed states redact tokens and bearer credentials`() {
        val token = "stor-secret-read-token"
        val state = initial.withResult(
            UpdaterResult.Failed("request failed for $token using Bearer another-secret"),
            listOf(token),
        )

        assertFalse(state.status.contains(token))
        assertFalse(state.status.contains("another-secret"))
        assertTrue(state.status.contains("[redacted]"))
    }
}
