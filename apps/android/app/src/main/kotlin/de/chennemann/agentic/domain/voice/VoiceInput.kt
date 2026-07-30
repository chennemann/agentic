package de.chennemann.agentic.domain.voice

import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface GroqApiKeyStore {
    val configured: StateFlow<Boolean>

    suspend fun read(): String?

    suspend fun write(apiKey: String)

    suspend fun remove()
}

interface AudioRecorder {
    fun start()

    fun stop(): File

    fun cancel()
}

interface AudioTranscriptionClient {
    suspend fun transcribe(
        apiKey: String,
        audioFile: File,
    ): String
}

interface VoiceInputService {
    fun startRecording()

    suspend fun stopAndTranscribe(): String

    fun cancelRecording()
}
