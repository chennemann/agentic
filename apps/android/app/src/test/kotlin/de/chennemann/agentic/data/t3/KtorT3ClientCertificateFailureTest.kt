package de.chennemann.agentic.data.t3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KtorT3ClientCertificateFailureTest {
    @Test
    fun `proxy certificate rejection explains the server trust failure`() = runBlocking {
        assertEquals(
            "The server rejected the device certificate. Check the selected certificate and the proxy's trusted CA and certificate revocation list.",
            failureMessage("<html><head><title>400 The SSL certificate error</title></head></html>"),
        )
    }

    @Test
    fun `missing client certificate asks for certificate selection`() = runBlocking {
        assertEquals(
            "The server requires a device certificate. Select the installed client certificate for this server.",
            failureMessage("<html><head><title>400 No required SSL certificate was sent</title></head></html>"),
        )
    }

    @Test
    fun `ordinary bad requests retain their HTTP error without exposing the body`() = runBlocking {
        assertEquals("T3 request failed with HTTP 400.", failureMessage("<html>private server details</html>"))
    }

    private suspend fun failureMessage(body: String): String? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(MockEngine {
            respond(body, HttpStatusCode.BadRequest, headersOf("Content-Type", "text/html"))
        })
        return try {
            runCatching {
                KtorT3Client(http, scope).environmentDescriptor("https://example.test/")
            }.exceptionOrNull()?.message
        } finally {
            http.close()
            scope.cancel()
        }
    }
}
