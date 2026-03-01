package de.chennemann.agentic.api

import de.chennemann.agentic.api.apis.DefaultApi
import de.chennemann.agentic.api.models.GlobalHealth200Response
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedClientSmokeTest {
    @Test
    fun serializes_and_deserializes_generated_model() {
        val data = GlobalHealth200Response(
            healthy = GlobalHealth200Response.Healthy.`true`,
            version = "1.2.3",
        )

        val json = Json.encodeToString(data)
        val decoded = Json.decodeFromString<GlobalHealth200Response>(json)

        assertEquals(data, decoded)
    }

    @Test
    fun uses_expected_default_api_request_path() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("/global/health", req.url.encodedPath)

            respond(
                content = """{"healthy":true,"version":"1.2.3"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = DefaultApi(
            baseUrl = "http://localhost",
            httpClientEngine = engine,
        )

        val response = api.globalHealth()

        assertTrue(response.success)
        assertEquals(200, response.status)
    }
}
