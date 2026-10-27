package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KtorT3ClientCompactThreadTest {
    @Test
    fun `bounded snapshot and history requests opt in and decode the compact forms`() = runTest {
        val queries = mutableListOf<Pair<String, Map<String, String?>>>()
        val http = HttpClient(
            MockEngine { request ->
                queries += request.url.encodedPath to request.url.parameters.names().associateWith { request.url.parameters[it] }
                val body = if (request.url.encodedPath.endsWith("/bounded")) BOUNDED_SNAPSHOT else HISTORY_PAGE
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        ) { install(ContentNegotiation) { json(de.chennemann.agentic.t3.contract.T3Json) } }
        try {
            val client = KtorT3Client(http, backgroundScope)
            val snapshot = client.threadHistory("https://environment.example/", "token", "thread-1", beforeCursor = null)
            val older = client.threadHistory(
                "https://environment.example/",
                "token",
                "thread-1",
                beforeCursor = "cursor-1",
                projection = snapshot.projection,
            )

            assertEquals("/api/orchestration/threads/thread-1/bounded", queries[0].first)
            assertEquals(mapOf("compactTurnItems" to "1", "dedupeBodies" to "1", "compactIds" to "1"), queries[0].second)
            assertEquals("/api/orchestration/threads/thread-1/history", queries[1].first)
            assertEquals(mapOf("cursor" to "cursor-1", "compactIds" to "1"), queries[1].second)

            assertEquals("thread-1", snapshot.thread.id)
            assertEquals(listOf("Done."), snapshot.thread.messages.map { it.text })
            assertEquals(listOf("item-1"), snapshot.projection.turnItems.map { it["id"]?.jsonPrimitive?.content })
            assertTrue(snapshot.page!!.hasMore)
            assertEquals(listOf("Earlier.", "Done."), older.thread.messages.map { it.text })
            assertEquals("older-cursor", older.page!!.beforeCursor)
            assertEquals(listOf("item-0", "item-1"), older.projection.visibleTurnItems.map { it.sourceItemId })
        } finally {
            http.close()
        }
    }

    @Test
    fun `thread subscriptions opt in and restore compact snapshots and deduped live bodies`() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val httpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets)
        }
        val requests = LinkedBlockingQueue<JsonObject>()
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody("""{"ticket":"ticket","expiresAt":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        if (message["_tag"]?.jsonPrimitive?.content != "Request") return
                        requests.offer(message)
                        val id = message.getValue("id").jsonPrimitive.content
                        webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[$STREAM_SNAPSHOT,$REPLY_MESSAGE,$REPLY_ITEM]}""")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
                },
            ),
        )
        try {
            val items = withTimeout(5_000) {
                KtorT3Client(httpClient, scope)
                    .threadStream(server.url("/").toString(), "token", "thread-1", afterSequence = null, requestCompletionMarker = true)
                    .take(3)
                    .toList()
            }

            val request = requireNotNull(requests.poll(1, TimeUnit.SECONDS))
            assertEquals("orchestration.subscribeThread", request.getValue("tag").jsonPrimitive.content)
            val payload = request.getValue("payload").jsonObject
            for (key in listOf("acceptBoundedSnapshot", "acceptCompactTurnItems", "acceptDedupedBodies", "acceptCompactIds")) {
                assertEquals("true", payload[key]?.jsonPrimitive?.content, key)
            }
            assertNull(payload["afterSequence"])
            val snapshot = (items[0] as OrchestrationThreadStreamItem.Snapshot).snapshot
            assertFalse(snapshot.page!!.hasMore)
            var projection = snapshot.projection
            items.drop(1).forEach { projection = projection.apply((it as OrchestrationThreadStreamItem.Event).event) }
            assertEquals(listOf("Done.", "Second reply."), projection.detail().messages.map { it.text })
            assertFalse(projection.turnItems.any { "textOmitted" in it })
        } finally {
            scope.cancel()
            httpClient.close()
            server.close()
        }
    }

    private companion object {
        const val AT = "2026-10-01T00:00:00.000Z"

        val THREAD = """{"id":0,"projectId":1,"title":"Fix","createdAt":"$AT","updatedAt":"$AT"}"""
        val MESSAGES = """[{"id":2,"threadId":0,"runId":3,"role":"assistant","text":"Done.","createdAt":"$AT","updatedAt":"$AT"}]"""
        val RUNS = """[{"id":3,"threadId":0,"ordinal":1,"status":"completed","requestedAt":"$AT"}]"""
        val ROW = """{"position":0,"visibility":"local","sourceThreadId":0,"sourceItemId":4,"item":{"id":4,"threadId":0,"runId":3,
            "type":"assistant_message","messageId":2,"ordinal":2,"status":"completed","text":"","textOmitted":true,"streaming":false,
            "updatedAt":"$AT"}}"""
        val IDS = """["thread-1","project-1","message-1","run-1","item-1"]"""
        val PROJECTION = """{"thread":$THREAD,"runs":$RUNS,"messages":$MESSAGES,"turnItems":[],"visibleTurnItems":[$ROW],"updatedAt":"$AT"}"""

        val BOUNDED_SNAPSHOT = """{"snapshotSequence":7,"projection":$PROJECTION,"historyCursor":"cursor-1","hasMoreHistory":true,
            "latestLocalTurnOrdinal":2,"turnItemsOmitLocalVisible":true,"ids":$IDS}"""
        val STREAM_SNAPSHOT = """{"kind":"snapshot","snapshotSequence":7,"projection":$PROJECTION,"historyCursor":null,
            "hasMoreHistory":false,"latestLocalTurnOrdinal":2,"turnItemsOmitLocalVisible":true,"ids":$IDS}"""
        val HISTORY_PAGE = """{"snapshotSequence":7,"items":[{"position":0,"visibility":"local","sourceThreadId":0,"sourceItemId":1,
            "item":{"id":1,"threadId":0,"runId":2,"type":"user_message","messageId":3,"ordinal":1,"status":"completed",
            "text":"Earlier.","updatedAt":"$AT"}}],"nextCursor":"older-cursor","hasMoreHistory":true,
            "ids":["thread-1","item-0","run-1","message-0"]}"""

        val REPLY_MESSAGE = """{"kind":"event","sequence":8,"event":{"type":"message.updated","payload":{"id":"message-2",
            "threadId":"thread-1","runId":"run-1","role":"assistant","text":"Second reply.","createdAt":"$AT","updatedAt":"$AT"}}}"""
        val REPLY_ITEM = """{"kind":"event","sequence":9,"event":{"type":"turn-item.updated","payload":{"id":"item-2",
            "threadId":"thread-1","runId":"run-1","type":"assistant_message","messageId":"message-2","ordinal":3,
            "status":"completed","text":"","textOmitted":true,"streaming":false,"updatedAt":"$AT"}}}"""
    }
}
