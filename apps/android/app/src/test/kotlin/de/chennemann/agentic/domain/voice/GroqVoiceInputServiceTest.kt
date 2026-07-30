package de.chennemann.agentic.domain.voice

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroqVoiceInputServiceTest {
    @Test
    fun `stopped recording is transcribed with saved key and deleted`() = runTest {
        val recording = File.createTempFile("agentic-voice-test", ".m4a").apply {
            writeText("audio")
        }
        val recorder = RecordingAudioRecorder(recording)
        val transcriptions = RecordingTranscriptionClient("  transcript text  ")
        val service = GroqVoiceInputService(
            recorder = recorder,
            apiKeys = InMemoryGroqApiKeyStore("gsk_test"),
            transcriptions = transcriptions,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        service.startRecording()
        val transcript = service.stopAndTranscribe()

        assertEquals(1, recorder.starts)
        assertEquals("transcript text", transcript)
        assertEquals("gsk_test", transcriptions.apiKey)
        assertEquals(recording.absolutePath, transcriptions.audioFile?.absolutePath)
        assertFalse(recording.exists())
    }
}

private class RecordingAudioRecorder(
    private val recording: File,
) : AudioRecorder {
    var starts = 0

    override fun start() {
        starts += 1
    }

    override fun stop(): File = recording

    override fun cancel() = Unit
}

private class RecordingTranscriptionClient(
    private val result: String,
) : AudioTranscriptionClient {
    var apiKey: String? = null
    var audioFile: File? = null

    override suspend fun transcribe(
        apiKey: String,
        audioFile: File,
    ): String {
        this.apiKey = apiKey
        this.audioFile = audioFile
        return result
    }
}

private class InMemoryGroqApiKeyStore(
    initialApiKey: String?,
) : GroqApiKeyStore {
    private var apiKey = initialApiKey
    private val mutableConfigured = MutableStateFlow(initialApiKey != null)

    override val configured: StateFlow<Boolean> = mutableConfigured

    override suspend fun read(): String? = apiKey

    override suspend fun write(apiKey: String) {
        this.apiKey = apiKey
        mutableConfigured.value = true
    }

    override suspend fun remove() {
        apiKey = null
        mutableConfigured.value = false
    }
}
