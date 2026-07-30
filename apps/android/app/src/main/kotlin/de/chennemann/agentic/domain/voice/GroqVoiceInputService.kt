package de.chennemann.agentic.domain.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class GroqVoiceInputService(
    private val recorder: AudioRecorder,
    private val apiKeys: GroqApiKeyStore,
    private val transcriptions: AudioTranscriptionClient,
    private val ioDispatcher: CoroutineDispatcher,
) : VoiceInputService {
    override fun startRecording() {
        recorder.start()
    }

    override suspend fun stopAndTranscribe(): String = withContext(ioDispatcher) {
        val recording = recorder.stop()
        try {
            val apiKey = apiKeys.read()
                ?: throw IllegalStateException("Set up a Groq API key before using voice input.")
            transcriptions.transcribe(apiKey, recording).trim().ifBlank {
                throw IllegalStateException("Groq did not return a transcript.")
            }
        } finally {
            recording.delete()
        }
    }

    override fun cancelRecording() {
        recorder.cancel()
    }
}
