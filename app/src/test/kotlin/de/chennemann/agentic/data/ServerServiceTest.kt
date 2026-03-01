package de.chennemann.agentic.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSESession
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.sse.ServerSentEvent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerServiceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://example.test"

    @Test
    fun healthParsesSuccessResponse() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/global/health", req.url.encodedPath)
            respond(
                content =
                """
                {
                  "healthy": true,
                  "version": "1.2.3"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val health = service.health(baseUrl)

        assertTrue(health.healthy)
        assertEquals("1.2.3", health.version)
    }

    @Test
    fun healthUsesDefaultsWhenFieldsMissing() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/global/health", req.url.encodedPath)
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val health = service.health(baseUrl)

        assertFalse(health.healthy)
        assertEquals("unknown", health.version)
    }

    @Test
    fun healthThrowsWhenServerReturnsError() = runTest {
        val service = ServerService(json, MockEngine {
            respond(status = HttpStatusCode.InternalServerError, content = "boom")
        })

        expectIllegalState { service.health(baseUrl) }
    }

    @Test
    fun projectsParsesSandboxesAndWorkspacesAndFiltersInvalidRows() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/project", req.url.encodedPath)
            respond(
                content =
                """
                [
                  {
                    "id": "p1",
                    "worktree": "/repo/a",
                    "name": "Alpha",
                    "sandboxes": [
                      " /repo/a-s1 ",
                      "/repo/a-s2",
                      "/repo/a-s2"
                    ]
                  },
                  {
                    "id": "p2",
                    "worktree": "/repo/b",
                    "workspaces": [
                      " /repo/b-w1 ",
                      "/repo/b-w2",
                      ""
                    ]
                  },
                  {
                    "id": "bad-no-worktree"
                  },
                  {
                    "worktree": "/repo/invalid-no-id"
                  }
                ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val projects = service.projects(baseUrl)

        assertEquals(2, projects.size)
        assertEquals("p1", projects[0].id)
        assertEquals("Alpha", projects[0].name)
        assertEquals(listOf("/repo/a-s1", "/repo/a-s2"), projects[0].sandboxes)
        assertEquals("p2", projects[1].id)
        assertEquals("/repo/b", projects[1].name)
        assertEquals(listOf("/repo/b-w1", "/repo/b-w2"), projects[1].sandboxes)
    }

    @Test
    fun sessionsParsesDefaultsAndTimeFields() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/session", req.url.encodedPath)
            assertEquals("/repo/a", req.url.parameters["directory"])
            assertEquals("true", req.url.parameters["roots"])
            assertEquals("10", req.url.parameters["limit"])
            respond(
                content =
                """
                [
                  {
                    "id": "s1",
                    "title": "Session 1",
                    "version": "v1",
                    "directory": "/repo/a",
                    "parentID": "parent-1",
                    "time": {
                      "updated": "1700000001000",
                      "archived": "1700000002000"
                    }
                  },
                  {
                    "id": "s2",
                    "time": {
                      "updated": "not-a-number"
                    }
                  },
                  {
                    "title": "bad-no-id"
                  }
                ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val sessions = service.sessions(baseUrl, worktree = "/repo/a", limit = 10)

        assertEquals(2, sessions.size)
        assertEquals("s1", sessions[0].id)
        assertEquals("Session 1", sessions[0].title)
        assertEquals("v1", sessions[0].version)
        assertEquals("/repo/a", sessions[0].directory)
        assertEquals("parent-1", sessions[0].parentId)
        assertEquals(1700000001000L, sessions[0].updatedAt)
        assertEquals(1700000002000L, sessions[0].archivedAt)
        assertEquals("s2", sessions[1].id)
        assertEquals("Session", sessions[1].title)
        assertEquals("unknown", sessions[1].version)
        assertEquals("", sessions[1].directory)
        assertNull(sessions[1].parentId)
        assertNull(sessions[1].updatedAt)
        assertNull(sessions[1].archivedAt)
    }

    @Test
    fun sessionMessagesExtractsTextAndFallbackTagsAndEmpty() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/session/s-1/message", req.url.encodedPath)
            assertEquals("/repo/a", req.url.parameters["directory"])
            assertEquals("5", req.url.parameters["limit"])
            respond(
                content =
                """
                [
                  {
                    "info": {
                      "id": "m1",
                      "role": "assistant",
                      "time": {
                        "created": "100",
                        "completed": "120"
                      }
                    },
                    "parts": [
                      { "type": "text", "text": "Hello" },
                      { "type": "text", "text": "  " },
                      { "type": "tool", "name": "grep" },
                      { "type": "text", "text": "World" }
                    ]
                  },
                  {
                    "info": {
                      "id": "m2"
                    },
                    "parts": [
                      { "type": "tool", "name": "read" },
                      { "type": "reasoning", "text": "Thinking" },
                      { "type": "tool", "name": "write" }
                    ]
                  },
                  {
                    "info": {
                      "id": "m3",
                      "role": "user"
                    },
                    "parts": [
                      { "text": "no-type" }
                    ]
                  },
                  {
                    "info": {
                      "role": "assistant"
                    },
                    "parts": []
                  }
                ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val messages = service.sessionMessages(baseUrl, sessionId = "s-1", directory = "/repo/a", limit = 5)

        assertEquals(3, messages.size)
        assertEquals("m1", messages[0].id)
        assertEquals("assistant", messages[0].role)
        assertEquals("Hello\nWorld", messages[0].text)
        assertEquals(100L, messages[0].createdAt)
        assertEquals(120L, messages[0].completedAt)
        assertEquals("m2", messages[1].id)
        assertEquals("assistant", messages[1].role)
        assertEquals("[tool, reasoning]", messages[1].text)
        assertEquals("m3", messages[2].id)
        assertEquals("user", messages[2].role)
        assertEquals("(empty)", messages[2].text)
    }

    @Test
    fun sendMessageUsesExpectedPathQueryAndBody() = runTest {
        var captured: HttpRequestData? = null
        val service = ServerService(json, MockEngine { req ->
            captured = req
            respond(status = HttpStatusCode.Accepted, content = "")
        })

        service.sendMessage(
            baseUrl = baseUrl,
            sessionId = "s-1",
            directory = "/repo/message",
            text = "Ship it",
            agent = "gpt-5",
        )

        val req = requireNotNull(captured)
        assertEquals("/session/s-1/prompt_async", req.url.encodedPath)
        assertEquals("/repo/message", req.url.parameters["directory"])
        assertEquals(ContentType.Application.Json, req.body.contentType)
        val body = json.parseToJsonElement(bodyText(req)).jsonObject
        assertEquals("gpt-5", body["agent"]?.jsonPrimitive?.content)
        val parts = body["parts"]?.jsonArray
        assertEquals(1, parts?.size)
        assertEquals("text", parts?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("Ship it", parts?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun sendMessageThrowsOnNon2xx() = runTest {
        val service = ServerService(json, MockEngine {
            respond(status = HttpStatusCode.BadRequest, content = "bad request")
        })

        expectIllegalState { service.sendMessage(baseUrl, "s-1", "/repo", "hello", "gpt-5") }
    }

    @Test
    fun sendCommandUsesExpectedPathQueryAndBody() = runTest {
        var captured: HttpRequestData? = null
        val service = ServerService(json, MockEngine { req ->
            captured = req
            respond(status = HttpStatusCode.NoContent, content = "")
        })

        service.sendCommand(
            baseUrl = baseUrl,
            sessionId = "s-1",
            directory = "/repo/command",
            name = "format",
            arguments = "--check",
            agent = "gpt-5",
        )

        val req = requireNotNull(captured)
        assertEquals("/session/s-1/command", req.url.encodedPath)
        assertEquals("/repo/command", req.url.parameters["directory"])
        assertEquals(ContentType.Application.Json, req.body.contentType)
        val body = json.parseToJsonElement(bodyText(req)).jsonObject
        assertEquals("format", body["command"]?.jsonPrimitive?.content)
        assertEquals("--check", body["arguments"]?.jsonPrimitive?.content)
        assertEquals("gpt-5", body["agent"]?.jsonPrimitive?.content)
    }

    @Test
    fun sendCommandThrowsOnNon2xx() = runTest {
        val service = ServerService(json, MockEngine {
            respond(status = HttpStatusCode.InternalServerError, content = "failed")
        })

        expectIllegalState { service.sendCommand(baseUrl, "s-1", "/repo", "build", "", "gpt-5") }
    }

    @Test
    fun streamEventsParsesValidPayloadsSkipsInvalidAndCarriesCursor() = runTest {
        var calls = 0
        val lastEventIds = mutableListOf<String?>()
        val raw = mutableListOf<String>()
        val events = mutableListOf<GlobalStreamEvent>()
        val delegate = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val engine = object : HttpClientEngine by delegate {
            override val supportedCapabilities = delegate.supportedCapabilities + SSECapability

            @OptIn(InternalAPI::class)
            override fun install(client: HttpClient) {
                super<HttpClientEngine>.install(client)
            }

            @OptIn(InternalAPI::class)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                assertEquals("/global/event", data.url.encodedPath)
                assertEquals("no-cache", data.headers[HttpHeaders.CacheControl])
                lastEventIds += data.headers["Last-Event-ID"]
                calls += 1
                val stream = if (calls == 1) {
                    flowOf(
                        ServerSentEvent(
                            id = "1",
                            event = "server.heartbeat",
                            data = "{\"directory\":\"/repo/a\",\"payload\":{\"type\":\"heartbeat\",\"properties\":{\"ok\":true}}}",
                        ),
                        ServerSentEvent(id = "2", event = "ignored", data = "not-json"),
                        ServerSentEvent(
                            id = "3",
                            event = "session.updated",
                            retry = 2500,
                            data = "{\"directory\":\"/repo/b\",\"payload\":{\"properties\":{\"id\":\"s-1\"}}}",
                        ),
                        ServerSentEvent(id = "4", event = "ignored", data = "{\"directory\":\"/repo/c\"}"),
                    )
                } else {
                    emptyFlow()
                }
                return HttpResponseData(
                    statusCode = HttpStatusCode.OK,
                    requestTime = GMTDate(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    version = HttpProtocolVersion.HTTP_1_1,
                    body = object : SSESession {
                        override val coroutineContext = data.executionContext
                        override val incoming = stream
                    },
                    callContext = data.executionContext,
                )
            }
        }
        val service = ServerService(json, engine)

        val firstCursor = service.streamEvents(
            baseUrl = baseUrl,
            lastEventId = null,
            onRawEvent = raw::add,
            onEvent = events::add,
        )
        val secondCursor = service.streamEvents(
            baseUrl = baseUrl,
            lastEventId = firstCursor,
            onRawEvent = raw::add,
            onEvent = events::add,
        )

        assertEquals("4", firstCursor)
        assertEquals("4", secondCursor)
        assertEquals(listOf(null, "4"), lastEventIds)
        assertEquals(4, raw.size)
        assertEquals(2, events.size)
        assertEquals("/repo/a", events[0].directory)
        assertEquals("heartbeat", events[0].type)
        assertEquals(true, events[0].properties["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertEquals("1", events[0].id)
        assertNull(events[0].retry)
        assertEquals("/repo/b", events[1].directory)
        assertEquals("session.updated", events[1].type)
        assertEquals("s-1", events[1].properties["id"]?.jsonPrimitive?.content)
        assertEquals("3", events[1].id)
        assertEquals(2500, events[1].retry)
    }

    private suspend fun expectIllegalState(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            return
        }
    }

    private fun bodyText(req: HttpRequestData): String {
        val body = req.body
        return when (body) {
            is TextContent -> body.text
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            else -> error("Unexpected body type: ${body::class.simpleName}")
        }
    }
}
