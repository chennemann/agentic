package de.chennemann.agentic.t3.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpstreamRpcContractTest {
    @Test
    fun `project scripts decode as executable provider-neutral metadata`() {
        val project = T3Json.decodeFromString<OrchestrationProject>(
            """{"id":"project-1","title":"Agentic","workspaceRoot":"/workspace","defaultModelSelection":null,"createdAt":"now","updatedAt":"now","scripts":[{"id":"dev","name":"Dev","command":"pnpm dev","icon":"debug","runOnWorktreeCreate":false,"futureHint":"ignored"}]}"""
        )

        assertEquals(
            ProjectScript("dev", "Dev", "pnpm dev", ProjectScriptIcon.DEBUG, false),
            project.scripts.single()
        )
    }

    @Test
    fun `terminal attach snapshot tolerates additive provider-neutral fields`() {
        val event = T3Json.decodeFromString<TerminalAttachEvent>(
            """{"type":"snapshot","future":"ignored","snapshot":{"threadId":"thread-1","terminalId":"term-1","cwd":"/workspace","worktreePath":null,"status":"running","pid":42,"history":"ready","exitCode":null,"exitSignal":null,"label":"shell","updatedAt":"now","futureState":true}}"""
        )

        assertEquals("ready", (event as TerminalAttachEvent.Snapshot).snapshot.history)
    }

    @Test
    fun `workspace file result preserves partial read metadata`() {
        val result = T3Json.decodeFromString<WorkspaceFileResult>(
            """{"relativePath":"README.md","contents":"hello","byteLength":1048580,"truncated":true}"""
        )

        assertEquals("README.md", result.relativePath)
        assertEquals(1_048_580, result.byteLength)
        assertTrue(result.truncated)
    }

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
                "capabilities": { "connectionProbe": true, "workspaceFiles": true }
              },
              "auth": {
                "policy": "desktop-managed-local",
                "bootstrapMethods": ["desktop-bootstrap"],
                "sessionMethods": ["bearer-access-token"],
                "sessionCookieName": "t3_session"
              },
              "cwd": "/workspace",
              "keybindings": {},
              "settings": { "addProjectBaseDirectory": "~/Development" },
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
        assertEquals("~/Development", config.settings.addProjectBaseDirectory)
        assertTrue(config.shellResumeCompletionMarker)
        assertTrue(config.threadResumeCompletionMarker)
        assertTrue(config.environment.capabilities.workspaceFiles)
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
