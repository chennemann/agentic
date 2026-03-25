package de.chennemann.agentic.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class ServerServiceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://example.test"

    @Test
    fun healthParsesSuccessResponse() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/up", req.url.encodedPath)
            respond(
                content =
                """
                {
                  "status": "ok",
                  "server": {
                    "version": "0.1.0"
                  }
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val health = service.health(baseUrl)

        assertTrue(health.healthy)
        assertEquals("0.1.0", health.version)
    }

    @Test
    fun healthUsesDefaultsWhenFieldsMissing() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/up", req.url.encodedPath)
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
    fun projectsParsesWrapperAndFiltersInvalidRows() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/projects", req.url.encodedPath)
            respond(
                content =
                """
                {
                  "projects": [
                    {
                      "id": "p1",
                      "cwd": "/repo/a",
                      "name": "Alpha"
                    },
                    {
                      "id": "p2",
                      "cwd": "/repo/b"
                    },
                    {
                      "id": "bad-no-cwd"
                    }
                  ]
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val projects = service.projects(baseUrl)

        assertEquals(2, projects.size)
        assertEquals("p1", projects[0].id)
        assertEquals("Alpha", projects[0].name)
        assertEquals("p2", projects[1].id)
        assertEquals("/repo/b", projects[1].name)
    }

    @Test
    fun sessionsResolveProjectByWorktreeAndParseFields() = runTest {
        val service = ServerService(json, MockEngine { req ->
            when (req.url.encodedPath) {
                "/projects" -> {
                    respond(
                        content =
                        """
                        {
                          "projects": [
                            {
                              "id": "p1",
                              "cwd": "/repo/a",
                              "name": "Alpha"
                            }
                          ]
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                "/projects/p1/sessions" -> {
                    respond(
                        content =
                        """
                        {
                          "sessions": [
                            {
                              "id": "s1",
                              "projectId": "p1",
                              "cwd": "/repo/a",
                              "title": "Session 1",
                              "parentSessionId": "parent-1",
                              "updatedAt": "2026-03-25T12:00:00Z",
                              "archivedAt": "2026-03-25T12:05:00Z"
                            },
                            {
                              "id": "s2",
                              "cwd": "/repo/a"
                            }
                          ]
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                else -> error("Unexpected path ${req.url.encodedPath}")
            }
        })

        val sessions = service.sessions(baseUrl, worktree = "/repo/a", limit = 10)

        assertEquals(2, sessions.size)
        assertEquals("s1", sessions[0].id)
        assertEquals("Session 1", sessions[0].title)
        assertEquals("mock", sessions[0].version)
        assertEquals("/repo/a", sessions[0].directory)
        assertEquals("parent-1", sessions[0].parentId)
        assertEquals(1774440000000L, sessions[0].updatedAt)
        assertEquals(1774440300000L, sessions[0].archivedAt)
        assertEquals("s2", sessions[1].id)
        assertEquals("Session", sessions[1].title)
        assertNull(sessions[1].updatedAt)
        assertNull(sessions[1].archivedAt)
    }

    @Test
    fun sessionMessagesExtractsRawTextAndFallbackText() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/sessions/s-1/messages", req.url.encodedPath)
            respond(
                content =
                """
                {
                  "session": {
                    "id": "s-1",
                    "cwd": "/repo/a"
                  },
                  "messages": [
                    {
                      "id": "m1",
                      "role": "assistant",
                      "text": "ignored fallback",
                      "createdAt": "1970-01-01T00:00:00.100Z",
                      "completedAt": "1970-01-01T00:00:00.120Z",
                      "raw": {
                        "content": [
                          { "type": "text", "text": "Hello" },
                          { "type": "text", "text": "World" }
                        ]
                      }
                    },
                    {
                      "id": "m2",
                      "role": "user",
                      "text": "Plain fallback"
                    }
                  ]
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val messages = service.sessionMessages(baseUrl, sessionId = "s-1", directory = "/repo/a", limit = 5)

        assertEquals(2, messages.size)
        assertEquals("m1", messages[0].id)
        assertEquals("assistant", messages[0].role)
        assertEquals("Hello\nWorld", messages[0].text)
        assertEquals(100L, messages[0].createdAt)
        assertEquals(120L, messages[0].completedAt)
        assertEquals("m2", messages[1].id)
        assertEquals("user", messages[1].role)
        assertEquals("Plain fallback", messages[1].text)
    }

    @Test
    fun sendMessageUsesExpectedPathAndBody() = runTest {
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
            agent = "build",
        )

        val req = requireNotNull(captured)
        assertEquals("/sessions/s-1/messages", req.url.encodedPath)
        assertEquals(ContentType.Application.Json, req.body.contentType)
        val body = json.parseToJsonElement(bodyText(req)).jsonObject
        assertEquals("Ship it", body["text"]?.jsonPrimitive?.content)
        assertEquals("build", body["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun sendMessageThrowsOnNon2xx() = runTest {
        val service = ServerService(json, MockEngine {
            respond(status = HttpStatusCode.BadRequest, content = "bad request")
        })

        expectIllegalState { service.sendMessage(baseUrl, "s-1", "/repo", "hello", "build") }
    }

    @Test
    fun sendCommandFallsBackToSlashCommandMessage() = runTest {
        var captured: HttpRequestData? = null
        val service = ServerService(json, MockEngine { req ->
            captured = req
            respond(status = HttpStatusCode.Accepted, content = "")
        })

        service.sendCommand(
            baseUrl = baseUrl,
            sessionId = "s-1",
            directory = "/repo/command",
            name = "format",
            arguments = "--check",
            agent = "build",
        )

        val req = requireNotNull(captured)
        assertEquals("/sessions/s-1/messages", req.url.encodedPath)
        val body = json.parseToJsonElement(bodyText(req)).jsonObject
        assertEquals("/format --check", body["text"]?.jsonPrimitive?.content)
        assertEquals("build", body["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun sessionUpdatedAtReadsSessionEndpoint() = runTest {
        val service = ServerService(json, MockEngine { req ->
            assertEquals("/sessions/s-1", req.url.encodedPath)
            respond(
                content =
                """
                {
                  "session": {
                    "id": "s-1",
                    "cwd": "/repo/a",
                    "updatedAt": "2026-03-25T12:00:00Z"
                  }
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val updatedAt = service.sessionUpdatedAt(baseUrl, "s-1", "/repo/a")

        assertEquals(1774440000000L, updatedAt)
    }

    @Test
    @Disabled("MockEngine SSE capability behavior is unstable on Ktor 3.1.0 in JVM unit tests")
    fun streamEventsMapsMockEnvelopeToSessionEvents() = runTest {
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
