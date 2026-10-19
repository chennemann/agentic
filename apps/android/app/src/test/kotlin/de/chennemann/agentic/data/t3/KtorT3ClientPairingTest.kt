package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.T3Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class KtorT3ClientPairingTest {
    @Test
    fun `pairing requests permissions needed by files terminal and usage features`() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/oauth/token", request.url.encodedPath)
            val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals(REQUIRED_T3_SCOPES, form["scope"])
            assertEquals("pairing-token", form["subject_token"])
            respond(
                """{"access_token":"access-token","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","token_type":"Bearer","expires_in":3600,"scope":"$REQUIRED_T3_SCOPES"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json")
            )
        }) { install(ContentNegotiation) { json(T3Json) } }
        try {
            val token = KtorT3Client(http, backgroundScope).exchangeToken(
                "https://example.test/", "pairing-token", AndroidClientMetadata("Agentic", "Android")
            )
            assertEquals(REQUIRED_T3_SCOPES, token.scope)
        } finally {
            http.close()
        }
    }

    @Test
    fun `HTTP permission rejection reports the actual permission and recovery`() = runTest {
        val http = HttpClient(MockEngine {
            respond(
                """{"code":"insufficient_scope","requiredScope":"orchestration:read","requiredPermission":"filesystem:read"}""",
                HttpStatusCode.Forbidden,
                headersOf("Content-Type", "application/json")
            )
        })
        try {
            val error = runCatching {
                KtorT3Client(http, backgroundScope).environmentDescriptor("https://example.test/")
            }.exceptionOrNull()
            val failure = assertInstanceOf(T3TransportException.Authorization::class.java, error)
            assertEquals("filesystem:read", failure.requiredScope)
            assertEquals(
                "T3 authorization is missing the required permission: filesystem:read. Create a new pairing link with this permission and pair this environment again.",
                failure.message
            )
        } finally {
            http.close()
        }
    }
}
