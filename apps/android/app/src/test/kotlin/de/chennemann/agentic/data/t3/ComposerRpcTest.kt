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

class ComposerRpcTest {
    @Test fun `composer search and file uploads follow the upstream RPC and HTTP contracts`() = runBlocking {
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
                        val tag = message.getValue("tag").jsonPrimitive.content
                        val result = when (tag) {
                            "projects.searchEntries" -> """
                                {"entries":[{"path":"src/Main.kt","kind":"file"}],"truncated":false}
                            """.trimIndent()
                            "server.refreshProviders" -> """{"providers":[]}"""
                            else -> """
                                {"attachmentId":"uploaded-file",
                                 "relativeUrl":"/api/attachments/upload/test?token=signed","expiresAt":1234567890}
                            """.trimIndent()
                        }
                        webSocket.send(
                            """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$result}}"""
                        )
                    }
                })
            )
            val client = KtorT3Client(http, scope)
            val base = server.url("/").toString()
            val paths = withTimeout(5000) { client.searchComposerPaths(base, "access-token", "/workspace", "Main") }
            assertEquals("src/Main.kt", paths.entries.single().path)
            withTimeout(5000) { client.composerProviders(base, "access-token", "instance", "/workspace") }
            server.enqueue(MockResponse().setResponseCode(204))
            val text = "pasted text".repeat(5000)
            val id = withTimeout(5000) { client.uploadPastedText(base, "access-token", "pasted-text.txt", text) }
            assertEquals("uploaded-file", id)
            val first = requests.poll(1, TimeUnit.SECONDS)!!
            val second = requests.poll(1, TimeUnit.SECONDS)!!
            val third = requests.poll(1, TimeUnit.SECONDS)!!
            assertEquals("projects.searchEntries", first["tag"]!!.jsonPrimitive.content)
            assertEquals(
                buildJsonObject {
                    put("cwd", "/workspace")
                    put("query", "Main")
                    put("limit", 20)
                },
                first["payload"]
            )
            assertEquals("server.refreshProviders", second["tag"]!!.jsonPrimitive.content)
            assertEquals(
                buildJsonObject {
                    put("instanceId", "instance")
                    put("cwd", "/workspace")
                },
                second["payload"]
            )
            assertEquals("attachments.createUploadUrl", third["tag"]!!.jsonPrimitive.content)
            assertEquals(
                buildJsonObject {
                    put("type", "file")
                    put("name", "pasted-text.txt")
                    put("mimeType", "text/plain")
                    put("sizeBytes", text.toByteArray().size)
                },
                third["payload"]
            )
            server.takeRequest()
            server.takeRequest()
            val upload = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", upload.method)
            assertEquals("/api/attachments/upload/test?token=signed", upload.path)
            assertEquals("text/plain", upload.getHeader("Content-Type"))
            assertEquals(text, upload.body.readUtf8())
            val attachment = de.chennemann.agentic.t3.contract.UploadChatAttachment(
                type = "file",
                id = id,
                name = "pasted-text.txt",
                mimeType = "text/plain",
                sizeBytes = text.toByteArray().size.toLong(),
                source = buildJsonObject {
                    put("_tag", "pasted-text")
                }
            )
            val encoded = de.chennemann.agentic.t3.contract.T3CommandJson.encodeToString(
                de.chennemann.agentic.t3.contract.UploadChatAttachment.serializer(),
                attachment
            )
            org.junit.jupiter.api.Assertions.assertFalse(encoded.contains("dataUrl"))
            org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("pasted-text"))
        } finally {
            scope.cancel()
            http.close()
            server.close()
        }
    }
}
