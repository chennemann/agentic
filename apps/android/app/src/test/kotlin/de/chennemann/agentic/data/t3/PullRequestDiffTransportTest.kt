package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.T3Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PullRequestDiffTransportTest {
    @Test
    fun `diff uses authenticated HTTP post and preserves host identity and pagination`() = runTest {
        val input = buildJsonObject { put("projectId", "project"); put("repository", "owner/repo"); put("number", 42); put("host", "enterprise.example"); put("cursor", "page-two") }
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/pull-requests/diff", request.url.encodedPath)
            assertEquals("Bearer access-token", request.headers["Authorization"])
            assertEquals(input, T3Json.parseToJsonElement((request.body as TextContent).text))
            respond("""{"patch":"diff content","truncated":true,"nextCursor":"page-three"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json(T3Json) } }
        try {
            val result = KtorT3Client(http, backgroundScope).pullRequestDiff("https://environment.example/", "access-token", input).jsonObject
            assertEquals("page-three", result["nextCursor"]?.jsonPrimitive?.content)
        } finally { http.close() }
    }
}
