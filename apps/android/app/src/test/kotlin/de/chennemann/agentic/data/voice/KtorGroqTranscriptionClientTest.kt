package de.chennemann.agentic.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KtorGroqTranscriptionClientTest {
    @Test
    fun `transcription sends authenticated multipart request and decodes text`() = runTest {
        var authorization: String? = null
        var requestUrl: String? = null
        var contentType: String? = null
        val httpClient = HttpClient(
            MockEngine { request ->
                authorization = request.headers[HttpHeaders.Authorization]
                requestUrl = request.url.toString()
                contentType = request.body.contentType?.toString()
                respond(
                    content = """{"text":"hello from voice","x_groq":{"id":"request-1"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val audioFile = File.createTempFile("agentic-groq-test", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        try {
            val transcript = KtorGroqTranscriptionClient(httpClient)
                .transcribe("gsk_secret", audioFile)

            assertEquals("hello from voice", transcript)
            assertEquals("Bearer gsk_secret", authorization)
            assertEquals(
                "https://api.groq.com/openai/v1/audio/transcriptions",
                requestUrl,
            )
            assertTrue(contentType.orEmpty().startsWith("multipart/form-data"))
        } finally {
            audioFile.delete()
            httpClient.close()
        }
    }

    @Test
    fun `authentication failure returns actionable message`() = runTest {
        val httpClient = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":{"message":"invalid key"}}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val audioFile = File.createTempFile("agentic-groq-test", ".m4a")

        try {
            val failure = runCatching {
                KtorGroqTranscriptionClient(httpClient).transcribe("bad-key", audioFile)
            }.exceptionOrNull()

            assertEquals(
                "Groq rejected the API key. Update it in Navigation and settings.",
                failure?.message,
            )
        } finally {
            audioFile.delete()
            httpClient.close()
        }
    }
}
