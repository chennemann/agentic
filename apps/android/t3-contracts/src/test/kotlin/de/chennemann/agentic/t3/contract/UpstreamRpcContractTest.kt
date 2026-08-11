package de.chennemann.agentic.t3.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpstreamRpcContractTest {
    @Test
    fun `server config decodes from the upstream RPC result`() {
        val config = T3Json.decodeFromString<ServerConfig>(
            """
            {
              "environment": {
                "environmentId": "environment-1",
                "label": "Development",
                "platform": { "os": "linux", "arch": "x64" },
                "serverVersion": "1.0.0",
                "capabilities": { "connectionProbe": true }
              },
              "auth": {
                "policy": "desktop-managed-local",
                "bootstrapMethods": ["desktop-bootstrap"],
                "sessionMethods": ["bearer-access-token"],
                "sessionCookieName": "t3_session"
              },
              "cwd": "/workspace",
              "keybindings": {},
              "providers": [
                { "instanceId": "codex", "displayName": "Codex", "models": [] }
              ],
              "shellResumeCompletionMarker": true,
              "threadResumeCompletionMarker": true
            }
            """.trimIndent()
        )

        assertEquals("environment-1", config.environment.environmentId)
        assertEquals("codex", config.providers.single().instanceId)
        assertTrue(config.shellResumeCompletionMarker)
        assertTrue(config.threadResumeCompletionMarker)
    }

    @Test
    fun `orchestration commands use the upstream RPC payload discriminator`() {
        val encoded = T3CommandJson.encodeToString<ClientOrchestrationCommand>(
            ClientOrchestrationCommand.ArchiveThread(
                commandId = "command-1",
                threadId = "thread-1"
            )
        )
        val payload = T3Json.parseToJsonElement(encoded).jsonObject

        assertEquals("thread.archive", payload.getValue("type").jsonPrimitive.content)
        assertEquals("command-1", payload.getValue("commandId").jsonPrimitive.content)
        assertEquals("thread-1", payload.getValue("threadId").jsonPrimitive.content)
    }

    @Test
    fun `subscription chunks decode upstream stream items`() {
        val item = T3Json.decodeFromString<OrchestrationShellStreamItem>(
            """{"kind":"synchronized"}"""
        )

        assertEquals(OrchestrationShellStreamItem.Synchronized, item)
    }
}
