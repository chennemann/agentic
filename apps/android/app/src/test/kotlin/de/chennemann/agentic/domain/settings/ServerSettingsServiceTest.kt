package de.chennemann.agentic.domain.settings

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.AndroidClientMetadata
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.AuthSession
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ServerAuthDescriptor
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.TokenExchangeResponse
import de.chennemann.agentic.ui.settings.ProjectScopeUi
import de.chennemann.agentic.ui.settings.mapServerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerSettingsServiceTest {
    private val settings = T3Json.parseToJsonElement(
        """
        {"threadTitleInstructions":"Lead with the ticket","addProjectBaseDirectory":"",
         "projectSettingsOverrides":{"project-1":{"defaultRuntimeMode":"full-access","providerInstances":{"a":{"enabled":false}}}},
         "futureSetting":true}
        """.trimIndent()
    ).jsonObject

    @Test fun `project override keeps the project's other overrides because the server replaces the whole set`() {
        val patch = projectOverridePatch(settings, "project-1", ThreadTitleInstructionsKey, JsonPrimitive("Short"))

        assertEquals(
            """{"patch":{"projectSettingsOverrides":{"project-1":{"defaultRuntimeMode":"full-access","providerInstances":{"a":{"enabled":false}},"threadTitleInstructions":"Short"}}}}""",
            patch.toString()
        )
    }

    @Test fun `inheriting again removes the key and drops an emptied override set`() {
        val withTitle = projectOverridePatch(
            T3Json.parseToJsonElement("""{"projectSettingsOverrides":{"p":{"threadTitleInstructions":"x"}}}""").jsonObject,
            "p",
            ThreadTitleInstructionsKey,
            null
        )
        assertEquals("""{"patch":{"projectSettingsOverrides":{"p":null}}}""", withTitle.toString())

        val keepsOthers = projectOverridePatch(settings, "project-1", "defaultRuntimeMode", null)
        assertEquals(
            """{"patch":{"projectSettingsOverrides":{"project-1":{"providerInstances":{"a":{"enabled":false}}}}}}""",
            keepsOthers.toString()
        )
    }

    @Test fun `global patch and scoped reads`() {
        assertEquals(
            """{"patch":{"threadTitleInstructions":"Use nouns"}}""",
            globalSettingPatch(ThreadTitleInstructionsKey, textSetting("  Use nouns  ")).toString()
        )
        val scoped = settings.scopedText(ThreadTitleInstructionsKey, "project-1")
        assertEquals("Lead with the ticket", scoped.global)
        assertNull(scoped.projectOverride)
        assertEquals("Lead with the ticket", scoped.effective)
        assertTrue(settings.supportsProjectOverrides)
        assertFalse(JsonObject(emptyMap()).supportsProjectOverrides)
    }

    @Test fun `screen hides settings the server does not report and explains read-only pairings`() {
        val older = mapServerSettings(
            ServerSettingsState(ServerSettingsSnapshot("env", "Studio", JsonObject(emptyMap()), canWrite = false)),
            ProjectScopeUi("project-1", "Agentic")
        )
        assertNull(older.threadTitleInstructions)
        assertFalse(older.editable)
        assertTrue(older.message!!.contains("not change"))

        val current = mapServerSettings(
            ServerSettingsState(ServerSettingsSnapshot("env", "Studio", settings, canWrite = true)),
            ProjectScopeUi("project-1", "Agentic")
        )
        assertEquals("Lead with the ticket", current.threadTitleInstructions?.value?.global)
        assertTrue(current.projectOverridesSupported)
        assertTrue(current.editable)
    }

    @Test fun `saving writes through the active environment and stores the returned settings`() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var stored = settings
        val rpc = ServerSettingsRpcClient { _, _, method, input ->
            calls += method to input
            if (method == "server.updateSettings") {
                stored = JsonObject(stored + ("projectSettingsOverrides" to input.getValue("patch").jsonObject.getValue("projectSettingsOverrides")))
            }
            stored
        }
        val service = ServerSettingsService(Environments, Credentials, rpc, auth(grants = true), ServerSettingsRepository())

        service.load()
        assertTrue(service.repository.state.value.snapshot!!.canWrite)
        assertTrue(service.updateProject("project-1", ThreadTitleInstructionsKey, JsonPrimitive("Short")))

        assertEquals(listOf("server.getSettings", "server.updateSettings"), calls.map { it.first })
        assertEquals(
            "Short",
            service.repository.state.value.snapshot!!.settings.scopedText(ThreadTitleInstructionsKey, "project-1").projectOverride
        )
    }

    @Test fun `pairings without settings access never send writes`() = runTest {
        val calls = mutableListOf<String>()
        val rpc = ServerSettingsRpcClient { _, _, method, _ ->
            calls += method
            settings
        }
        val service = ServerSettingsService(Environments, Credentials, rpc, auth(grants = false), ServerSettingsRepository())

        service.load()
        assertFalse(service.updateGlobal(ThreadTitleInstructionsKey, JsonPrimitive("x")))
        assertEquals(listOf("server.getSettings"), calls)
    }

    private fun auth(grants: Boolean) = object : EnvironmentAuthClient {
        override suspend fun exchangeToken(baseUrl: String, bootstrapCredential: String, metadata: AndroidClientMetadata): TokenExchangeResponse =
            error("Not used")

        override suspend fun session(baseUrl: String, bearerToken: String) = AuthSession(
            authenticated = true,
            auth = ServerAuthDescriptor("remote-reachable", emptyList(), emptyList(), "t3"),
            scopes = if (grants) listOf("settings:write") else emptyList()
        )
    }

    private object Credentials : CredentialStore {
        override suspend fun read(environmentId: String) = "token"
        override suspend fun write(environmentId: String, bearerToken: String) = Unit
        override suspend fun remove(environmentId: String) = Unit
    }

    private object Environments : EnvironmentRepository {
        private val environment = SavedEnvironment("env", "Studio", "http://localhost", "linux", "x64", "1", true, null)
        override val environments = MutableStateFlow(listOf(environment))
        override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(environment)
        override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
        override suspend fun select(environmentId: String) = Unit
        override suspend fun remove(environmentId: String) = Unit
        override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
    }
}
