package de.chennemann.agentic.data.voice

import de.chennemann.agentic.domain.voice.AudioTranscriptionClient
import de.chennemann.agentic.t3.contract.T3Json
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.serialization.Serializable

class KtorGroqTranscriptionClient(
    private val client: HttpClient,
) : AudioTranscriptionClient {
    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
    ): String {
        val response = client.submitFormWithBinaryData(
            url = TranscriptionsEndpoint,
            formData = formData {
                append("model", TranscriptionModel)
                append("response_format", "json")
                append(
                    key = "file",
                    value = audioFile.readBytes(),
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${audioFile.name}\"",
                        )
                    },
                )
            },
        ) {
            bearerAuth(apiKey)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(response.status.failureMessage())
        }
        return T3Json.decodeFromString<GroqTranscriptionResponse>(
            response.bodyAsText(),
        ).text
    }

    private companion object {
        const val TranscriptionsEndpoint = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val TranscriptionModel = "whisper-large-v3-turbo"
    }
}

@Serializable
private data class GroqTranscriptionResponse(
    val text: String,
)

private fun io.ktor.http.HttpStatusCode.failureMessage(): String = when (value) {
    401 -> "Groq rejected the API key. Update it in Navigation and settings."
    413 -> "The voice recording is too large for Groq."
    429 -> "Groq is rate limiting transcription. Please try again shortly."
    else -> "Groq transcription failed (HTTP $value)."
}
