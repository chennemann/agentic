package de.chennemann.agentic.data.t3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KtorGalleryImageTest {
    @Test fun `remote images download as data URLs without bearer credentials`() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://images.example/screen.png", request.url.toString())
            assertNull(request.headers["Authorization"])
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
        })
        try {
            assertEquals("data:image/png;base64,AQID", KtorT3Client(http, backgroundScope).loadGalleryImage("https://images.example/screen.png"))
        } finally { http.close() }
    }
}
