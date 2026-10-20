package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.ClientOrchestrationCommand
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KtorT3ClientWebSocketTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        httpClient.close()
        server.close()
    }

    @Test
    fun `HTML uses an authenticated inline asset request and a credential free download`() = runBlocking {
        val requests = LinkedBlockingQueue<JsonObject>()
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody("""{"ticket":"ticket","expiresAt":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonObject
                if (message["_tag"]?.jsonPrimitive?.content != "Request") return
                requests.offer(message)
                val id = message.getValue("id").jsonPrimitive.content
                webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"relativeUrl":"/api/assets/signed/page.html","expiresAt":9999999999999}}}""")
            }
        }))
        server.enqueue(MockResponse().setHeader("content-type", "text/html").setBody("<p>Gallery</p>"))
        val client = KtorT3Client(httpClient, scope)
        assertEquals("<p>Gallery</p>", withTimeout(5_000) { client.loadHtml(server.url("/").toString(), "access-token", "page.html") })
        val request = requireNotNull(requests.poll(1, TimeUnit.SECONDS))
        assertEquals("assets.createUrl", request.getValue("tag").jsonPrimitive.content)
        val resource = request.getValue("payload").jsonObject.getValue("resource").jsonObject
        assertEquals("attachment", resource.getValue("_tag").jsonPrimitive.content)
        assertEquals("page.html", resource.getValue("attachmentId").jsonPrimitive.content)
        assertEquals("text/html", resource.getValue("mimeType").jsonPrimitive.content)
        assertEquals("inline", resource.getValue("disposition").jsonPrimitive.content)
        assertEquals("Bearer access-token", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("authorization"))
        server.takeRequest(1, TimeUnit.SECONDS)
        val download = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/api/assets/signed/page.html", download?.path)
        org.junit.jupiter.api.Assertions.assertNull(download?.getHeader("authorization"))
    }

    @Test
    fun `upstream RPC calls share one ticketed websocket`() = runBlocking {
        val acknowledged = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val requests = LinkedBlockingQueue<JsonObject>()
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""{"ticket":"ticket-value","expiresAt":"2099-01-01T00:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        when (message.getValue("_tag").jsonPrimitive.content) {
                            "Request" -> {
                                requests.offer(message)
                                respond(webSocket, message)
                            }

                            "Ack" -> acknowledged.countDown()
                            "Interrupt" -> {
                                interrupted.countDown()
                                webSocket.close(1000, "test complete")
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
                },
            ),
        )

        val client = KtorT3Client(httpClient, scope)
        val config = withTimeout(5_000) {
            client.serverConfig(server.url("/").toString(), "access-token")
        }
        val dispatch = withTimeout(5_000) {
            client.dispatch(
                baseUrl = server.url("/").toString(),
                bearerToken = "access-token",
                command = ClientOrchestrationCommand.ArchiveThread(
                    commandId = "command-1",
                    threadId = "thread-1",
                ),
            )
        }
        withTimeout(5_000) {
            client.reportClientActivity(
                baseUrl = server.url("/").toString(),
                bearerToken = "access-token",
                report = ClientActivityReport(
                    environmentId = "environment-1",
                    clientId = "mobile-installation-1",
                    visible = false,
                    appState = "background",
                    threadIds = setOf("thread-2", "thread-1"),
                    observedAt = "2026-08-16T12:00:00Z",
                ),
            )
        }
        val terminal = withTimeout(5_000) {
            client.openTerminal(
                baseUrl = server.url("/").toString(),
                bearerToken = "access-token",
                threadId = "thread-1",
                terminalId = "term-2",
                cwd = "/workspace/worktree",
                worktreePath = "/workspace/worktree",
                env = mapOf(
                    "T3CODE_PROJECT_ROOT" to "/workspace",
                    "T3CODE_WORKTREE_PATH" to "/workspace/worktree",
                ),
            )
        }
        withTimeout(5_000) {
            client.resizeTerminal(server.url("/").toString(), "access-token", "thread-1", "term-2", 93, 17)
            client.writeTerminal(server.url("/").toString(), "access-token", "thread-1", "term-2", "\u0003\u001b[A\t")
        }
        val item = withTimeout(5_000) {
            client.shellStream(
                baseUrl = server.url("/").toString(),
                bearerToken = "access-token",
                afterSequence = 7,
                requestCompletionMarker = true,
            ).first { it is OrchestrationShellStreamItem.Synchronized }
        }

        assertEquals("environment-1", config.environment.environmentId)
        assertEquals(8, dispatch.sequence)
        assertEquals(OrchestrationShellStreamItem.Synchronized, item)
        assertEquals("term-2", terminal.terminalId)
        val ticketRequest = server.takeRequest(1, TimeUnit.SECONDS)
        val webSocketRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/api/auth/websocket-ticket", ticketRequest?.path)
        assertEquals("Bearer access-token", ticketRequest?.getHeader("authorization"))
        assertEquals("/ws?wsTicket=ticket-value&orchestrationProtocol=2", webSocketRequest?.path)
        val rpcRequests = buildList {
            while (size < 7) {
                val request = requireNotNull(requests.poll(1, TimeUnit.SECONDS))
                if (request["tag"]?.jsonPrimitive?.content != "orchestration.subscribeArchivedShell") add(request)
            }
        }
        assertEquals(
            listOf(
                "server.getConfig",
                "orchestration.dispatchCommand",
                "server.reportClientActivity",
                "terminal.open",
                "terminal.resize",
                "terminal.write",
                "orchestration.subscribeShell",
            ),
            rpcRequests.map { it?.get("tag")?.jsonPrimitive?.content },
        )
        val activityPayload = rpcRequests[2]?.get("payload")?.jsonObject
        assertEquals("mobile", activityPayload?.get("clientKind")?.jsonPrimitive?.content)
        assertEquals(false, activityPayload?.get("visible")?.jsonPrimitive?.content?.toBoolean())
        assertEquals(
            listOf("thread-1", "thread-2"),
            activityPayload?.get("scopes")?.jsonArray?.map {
                it.jsonObject.getValue("threadId").jsonPrimitive.content
            },
        )
        val terminalPayload = rpcRequests[3]?.get("payload")?.jsonObject
        val resizePayload = rpcRequests[4]?.get("payload")?.jsonObject
        assertEquals("93", resizePayload?.get("cols")?.jsonPrimitive?.content)
        assertEquals("17", resizePayload?.get("rows")?.jsonPrimitive?.content)
        assertEquals("term-2", resizePayload?.get("terminalId")?.jsonPrimitive?.content)
        assertEquals("\u0003\u001b[A\t", rpcRequests[5]?.get("payload")?.jsonObject?.get("data")?.jsonPrimitive?.content)
        assertEquals("/workspace/worktree", terminalPayload?.get("cwd")?.jsonPrimitive?.content)
        assertEquals(
            "/workspace",
            terminalPayload?.get("env")?.jsonObject?.get("T3CODE_PROJECT_ROOT")?.jsonPrimitive?.content,
        )
        assertTrue(acknowledged.await(1, TimeUnit.SECONDS))
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        assertEquals(2, server.requestCount)
    }

    private fun respond(webSocket: WebSocket, request: JsonObject) {
        val requestId = request.getValue("id").jsonPrimitive.content
        when (request.getValue("tag").jsonPrimitive.content) {
            "server.getConfig" -> webSocket.send(
                """
                {
                  "_tag":"Exit",
                  "requestId":"$requestId",
                  "exit":{
                    "_tag":"Success",
                    "value":{
                      "environment":{
                        "environmentId":"environment-1",
                        "label":"Development",
                        "platform":{"os":"linux","arch":"x64"},
                        "serverVersion":"1.0.0",
                        "capabilities":{}
                      },
                      "auth":{
                        "policy":"desktop-managed-local",
                        "bootstrapMethods":["desktop-bootstrap"],
                        "sessionMethods":["bearer-access-token"],
                        "sessionCookieName":"t3_session"
                      },
                      "providers":[],
                      "shellResumeCompletionMarker":true,
                      "threadResumeCompletionMarker":true
                    }
                  }
                }
                """.trimIndent(),
            )

            "orchestration.dispatchCommand" -> webSocket.send(
                """{"_tag":"Exit","requestId":"$requestId","exit":{"_tag":"Success","value":{"sequence":8}}}""",
            )

            "server.reportClientActivity", "terminal.resize", "terminal.write" -> webSocket.send(
                """{"_tag":"Exit","requestId":"$requestId","exit":{"_tag":"Success","value":null}}""",
            )

            "orchestration.subscribeShell" -> webSocket.send(
                """{"_tag":"Chunk","requestId":"$requestId","values":[{"kind":"synchronized"}]}""",
            )

            "terminal.open" -> webSocket.send(
                """{"_tag":"Exit","requestId":"$requestId","exit":{"_tag":"Success","value":{"threadId":"thread-1","terminalId":"term-2","cwd":"/workspace/worktree","worktreePath":"/workspace/worktree","status":"running","pid":42,"history":"","exitCode":null,"exitSignal":null,"label":"Dev","updatedAt":"now"}}}""",
            )
        }
    }
}
