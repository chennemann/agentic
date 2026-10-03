package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Opt-in test against a disposable stock server, with credentials supplied through a local file. */
class UpstreamServerCompatibilityTest {
    @Test
    fun `native Android client pairs and operates against stock upstream`() = runBlocking {
        assumeTrue(System.getenv("AGENTIC_T3_TEST_URL") != null && System.getenv("AGENTIC_T3_TEST_TOKEN_FILE") != null,
            "No disposable upstream server configured")
        val baseUrl = requireNotNull(System.getenv("AGENTIC_T3_TEST_URL"))
        val tokenFile = requireNotNull(System.getenv("AGENTIC_T3_TEST_TOKEN_FILE"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(T3Json) }
            install(WebSockets)
        }
        try {
            withTimeout(60_000) {
                val client = KtorT3Client(http, scope)
                val descriptor = client.environmentDescriptor(baseUrl)
                val token = client.exchangeToken(
                    baseUrl, File(tokenFile).readText().trim(), AndroidClientMetadata("Agentic compatibility test", "JVM"),
                ).accessToken
                assertTrue(client.session(baseUrl, token).authenticated)
                val config = client.serverConfig(baseUrl, token)
                assertEquals(descriptor.environmentId, config.environment.environmentId)
                assertTrue(config.threadSnapshotPagination)
                assertTrue(config.newProjectsRoot != null)
                println("Stock upstream pairing and configuration passed")
                val streamed = client.configStream(baseUrl, token).first { it.type == "snapshot" }
                assertEquals(config.environment.environmentId, streamed.config?.environment?.environmentId)
                println("Stock upstream configuration subscription passed")

                val scratch = config.scratchWorkspaceRoot?.let {
                    client.ensureScratch(baseUrl, token).also { first ->
                        assertEquals(first.projectId, client.ensureScratch(baseUrl, token).projectId)
                        println("Stock upstream Scratch creation and reuse passed")
                    }
                }
                val created = client.createNewProject(baseUrl, token, "agentic-check-${UUID.randomUUID()}")
                println("Stock upstream project creation passed")
                val shell = (client.shellStream(baseUrl, token, null, true).first {
                    it is OrchestrationShellStreamItem.Snapshot
                } as OrchestrationShellStreamItem.Snapshot).snapshot
                scratch?.let { assertTrue(shell.projects.any { project -> project.id == it.projectId }) }
                assertTrue(shell.projects.any { it.id == created.projectId })
                println("Stock upstream shell subscription passed")
                val files = client.listEntries(baseUrl, token, created.workspaceRoot)
                assertFalse(files.truncated)
                val readme = files.entries.first { it.path.endsWith("README.md") }
                assertTrue(client.readFile(baseUrl, token, created.workspaceRoot, readme.path).contents.isNotBlank())
                client.listRefs(baseUrl, token, created.workspaceRoot)
                println("Stock upstream files and repository refs passed")

                val threadId = UUID.randomUUID().toString()
                client.dispatch(
                    baseUrl, token,
                    ClientOrchestrationCommand.CreateThread(
                        commandId = UUID.randomUUID().toString(), threadId = threadId,
                        projectId = created.projectId, title = "Compatibility check",
                        modelSelection = ModelSelection("compatibility-test", "test-model"),
                        runtimeMode = "approval-required", interactionMode = "default",
                        branch = null, worktreePath = null, createdAt = Instant.now().toString(),
                    ),
                )
                val initial = (client.threadStream(baseUrl, token, threadId, null, true, 20, true).first {
                    it is OrchestrationThreadStreamItem.Snapshot
                } as OrchestrationThreadStreamItem.Snapshot).snapshot
                assertEquals(threadId, initial.thread.id)
                assertFalse(requireNotNull(initial.page).hasMore)
                val history = client.threadHistory(baseUrl, token, threadId, null, 20, true)
                assertEquals(threadId, history.thread.id)
                assertFalse(requireNotNull(history.page).hasMore)
                println("Stock upstream thread subscription and windowed HTTP history passed")
                client.dispatch(
                    baseUrl, token,
                    ClientOrchestrationCommand.UpdateThreadMetadata(
                        commandId = UUID.randomUUID().toString(), threadId = threadId, title = "Renamed check",
                        modelSelection = ModelSelection("compatibility-test", "test-model"),
                    ),
                )
                val replay = client.shellStream(baseUrl, token, shell.snapshotSequence, true).first {
                    it is OrchestrationShellStreamItem.ThreadUpserted && it.thread.id == threadId && it.thread.title == "Renamed check"
                }
                assertTrue(replay is OrchestrationShellStreamItem.ThreadUpserted)
                assertEquals(OrchestrationThreadStreamItem.Synchronized,
                    client.threadStream(baseUrl, token, threadId, initial.snapshotSequence, true, 20, true).first {
                        it is OrchestrationThreadStreamItem.Synchronized
                    })
                println("Stock upstream shell and thread replay passed")

                client.reportClientActivity(
                    baseUrl, token,
                    ClientActivityReport(descriptor.environmentId, "agentic-check", true, "active", setOf(threadId), Instant.now().toString()),
                )
                val terminalId = "agentic-terminal"
                val terminal = client.openTerminal(baseUrl, token, threadId, terminalId, created.workspaceRoot, null, emptyMap())
                assertEquals(terminalId, terminal.terminalId)
                assertTrue(client.attachTerminal(baseUrl, token, threadId, terminalId).first() is TerminalAttachEvent.Snapshot)
                client.writeTerminal(baseUrl, token, threadId, terminalId, "echo AGENTIC_UPSTREAM_CHECK\r")
                client.clearTerminal(baseUrl, token, threadId, terminalId)
                client.closeTerminal(baseUrl, token, threadId, terminalId)
                println("Stock upstream activity reporting and terminal lifecycle passed")
                client.dispatch(baseUrl, token, ClientOrchestrationCommand.DeleteThread(UUID.randomUUID().toString(), threadId))
                Unit
            }
        } finally {
            scope.cancel()
            http.close()
        }
    }
}
