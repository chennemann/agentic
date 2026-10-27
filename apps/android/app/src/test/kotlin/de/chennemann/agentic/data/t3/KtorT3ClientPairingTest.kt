package de.chennemann.agentic.data.t3

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.auth.PairingException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentService
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `pairing rejects an incompatible orchestration protocol without spending the token`() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(
                """{"environmentId":"environment-1","label":"Old host","platform":{"os":"linux","arch":"x64"},
                "serverVersion":"0.0.45","capabilities":{"repositoryIdentity":true}}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json")
            )
        }) { install(ContentNegotiation) { json(T3Json) } }
        try {
            val client = KtorT3Client(http, backgroundScope)
            val credentials = RecordingCredentialStore()
            val service = EnvironmentService(
                UnusedEnvironmentRepository,
                credentials,
                client,
                client,
                client,
                AndroidClientMetadata("Agentic", "Android")
            )
            val preview = service.inspect("https://example.test/pair#token=pairing-token", requireCredential = true)
            val error = runCatching { service.pair(preview) }.exceptionOrNull()
            assertInstanceOf(PairingException.IncompatibleProtocol::class.java, error)
            assertEquals(listOf("/.well-known/t3/environment"), paths)
            assertTrue(credentials.written.isEmpty())
        } finally {
            http.close()
        }
    }

    @Test
    fun `current protocol descriptors are accepted for pairing`() {
        val descriptor = T3Json.decodeFromString<ExecutionEnvironmentDescriptor>(
            """{"environmentId":"environment-1","label":"Host","platform":{"os":"linux","arch":"x64","machine":"server"},
            "serverVersion":"0.1.3-fork.1","orchestrationProtocolVersion":2,
            "capabilities":{"repositoryIdentity":true,"shellOlderSettled":true,"commandOutputStreaming":true}}"""
        )
        assertTrue(descriptor.supportsOrchestrationProtocol)
    }

    private class RecordingCredentialStore : CredentialStore {
        val written = mutableMapOf<String, String>()
        override suspend fun read(environmentId: String): String? = written[environmentId]
        override suspend fun write(environmentId: String, bearerToken: String) {
            written[environmentId] = bearerToken
        }
        override suspend fun remove(environmentId: String) {
            written.remove(environmentId)
        }
    }

    private object UnusedEnvironmentRepository : EnvironmentRepository {
        override val environments = MutableStateFlow(emptyList<SavedEnvironment>())
        override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(null)
        override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) =
            error("Incompatible environments must not be saved.")
        override suspend fun select(environmentId: String) = error("Unused")
        override suspend fun remove(environmentId: String) = error("Unused")
        override suspend fun markConnected(environmentId: String, connectedAt: Long) = error("Unused")
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
