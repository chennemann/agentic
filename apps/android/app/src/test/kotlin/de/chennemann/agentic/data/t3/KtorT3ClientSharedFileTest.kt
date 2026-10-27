package de.chennemann.agentic.data.t3

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KtorT3ClientSharedFileTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
    }

    @TempDir
    lateinit var directory: File

    @AfterEach
    fun tearDown() {
        scope.cancel()
        httpClient.close()
        server.close()
    }

    @Test
    fun `shared files download through a signed asset URL without the credential`() = runBlocking {
        val requests = enqueueAsset(MockResponse().setHeader("content-type", "application/octet-stream").setBody("ID3 audio"))
        val target = File(directory, "voice.mp3")

        val download = withTimeout(5_000) {
            KtorT3Client(httpClient, scope).downloadAsset(
                server.url("/").toString(),
                "access-token",
                mediaFileDownloadResource("thread-1", "/Users/me/out/voice.mp3"),
                target,
                maxBytes = 1024,
            )
        }

        assertEquals("ID3 audio", target.readText())
        assertEquals(9, download.bytes)
        assertFalse(download.truncated)
        val request = requireNotNull(requests.poll(1, TimeUnit.SECONDS))
        assertEquals("assets.createUrl", request.getValue("tag").jsonPrimitive.content)
        val resource = request.getValue("payload").jsonObject.getValue("resource").jsonObject
        assertEquals("media-file", resource.getValue("_tag").jsonPrimitive.content)
        assertEquals("thread-1", resource.getValue("threadId").jsonPrimitive.content)
        assertEquals("/Users/me/out/voice.mp3", resource.getValue("path").jsonPrimitive.content)
        assertEquals("attachment", resource.getValue("disposition").jsonPrimitive.content)
        server.takeRequest(1, TimeUnit.SECONDS)
        server.takeRequest(1, TimeUnit.SECONDS)
        val fetch = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/api/assets/signed/voice.mp3", fetch?.path)
        assertNull(fetch?.getHeader("authorization"))
    }

    @Test
    fun `downloads past the budget truncate when asked and fail otherwise`() = runBlocking {
        enqueueAsset(MockResponse().setBody("0123456789"))
        val client = KtorT3Client(httpClient, scope)
        val preview = File(directory, "preview.log")
        val truncated = withTimeout(5_000) {
            client.downloadAsset(
                server.url("/").toString(), "access-token", attachmentAssetResource("log"), preview, 4, truncate = true,
            )
        }
        assertEquals("0123", preview.readText())
        assertTrue(truncated.truncated)
        assertEquals(10L, truncated.contentLength)

        val full = File(directory, "full.log")
        server.enqueue(MockResponse().setBody("0123456789"))
        val failure = runCatching {
            withTimeout(5_000) {
                client.downloadAsset(
                    server.url("/").toString(), "access-token", attachmentAssetResource("log"), full, 4,
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is AssetTooLargeException, failure.toString())
        assertFalse(full.exists())
    }

    private fun enqueueAsset(download: MockResponse): LinkedBlockingQueue<JsonObject> {
        val requests = LinkedBlockingQueue<JsonObject>()
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody("""{"ticket":"ticket","expiresAt":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = Json.parseToJsonElement(text).jsonObject
                    if (message["_tag"]?.jsonPrimitive?.content != "Request") return
                    requests.offer(message)
                    val id = message.getValue("id").jsonPrimitive.content
                    webSocket.send(
                        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"relativeUrl":"/api/assets/signed/voice.mp3","expiresAt":1767225600000,"sourcePath":"/Users/me/out/voice.mp3","fileRevision":"66306:1234:9:1:1"}}}""",
                    )
                }
            }),
        )
        server.enqueue(download)
        return requests
    }
}
