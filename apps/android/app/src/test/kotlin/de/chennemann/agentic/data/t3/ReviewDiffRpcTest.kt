package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.T3Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewDiffRpcTest {
    @Test fun `turn and full thread diffs use upstream RPC envelopes over the real websocket client`() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(T3Json) }
            install(WebSockets)
        }
        val requests = LinkedBlockingQueue<JsonObject>()
        try {
            server.enqueue(
                MockResponse().setHeader(
                    "content-type",
                    "application/json"
                ).setBody("""{"ticket":"test-ticket","expiresAt":"2099-01-01T00:00:00Z"}""")
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = T3Json.parseToJsonElement(text).jsonObject
                        if (message["_tag"]?.jsonPrimitive?.content != "Request") return
                        requests.offer(message)
                        val id = message.getValue("id").jsonPrimitive.content
                        val full = message.getValue("tag").jsonPrimitive.content == "orchestration.getFullThreadDiff"
                        webSocket.send(
                            """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"threadId":"thread-1","fromTurnCount":${if (full) 0 else 2},"toTurnCount":3,"diff":"sample patch"}}}"""
                        )
                    }
                })
            )
            val client = KtorT3Client(http, scope)
            val base = server.url("/").toString()
            val turn = withTimeout(5000) { client.turnDiff(base, "access-token", "thread-1", 2, 3) }
            val full = withTimeout(5000) { client.fullThreadDiff(base, "access-token", "thread-1", 3) }
            assertEquals("sample patch", turn.diff)
            assertEquals(0, full.fromTurnCount)
            val first = requests.poll(1, TimeUnit.SECONDS)!!
            val second = requests.poll(1, TimeUnit.SECONDS)!!
            assertEquals("orchestration.getTurnDiff", first["tag"]!!.jsonPrimitive.content)
            assertEquals(
                buildJsonObject {
                    put("threadId", "thread-1")
                    put("fromTurnCount", 2)
                    put("toTurnCount", 3)
                    put("ignoreWhitespace", false)
                },
                first["payload"]
            )
            assertEquals("orchestration.getFullThreadDiff", second["tag"]!!.jsonPrimitive.content)
            assertEquals(
                buildJsonObject {
                    put("threadId", "thread-1")
                    put("toTurnCount", 3)
                    put("ignoreWhitespace", false)
                },
                second["payload"]
            )
            assertEquals(2, server.requestCount)
        } finally {
            scope.cancel()
            http.close()
            server.close()
        }
    }
}
