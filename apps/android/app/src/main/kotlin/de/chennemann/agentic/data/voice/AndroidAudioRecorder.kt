package de.chennemann.agentic.data.voice

import android.content.Context
import android.media.MediaRecorder
import de.chennemann.agentic.domain.voice.AudioRecorder
import java.io.File

class AndroidAudioRecorder(
    private val context: Context,
) : AudioRecorder {
    private var activeRecorder: MediaRecorder? = null
    private var activeFile: File? = null

    override fun start() {
        check(activeRecorder == null) { "A recording is already in progress." }
        val directory = File(context.cacheDir, RecordingDirectory).apply {
            check(mkdirs() || isDirectory) { "Unable to prepare voice input storage." }
        }
        val file = File.createTempFile(RecordingPrefix, RecordingSuffix, directory)
        val recorder = MediaRecorder(context)
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(AudioSampleRate)
            recorder.setAudioEncodingBitRate(AudioBitRate)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            activeFile = file
            activeRecorder = recorder
        } catch (failure: Throwable) {
            recorder.release()
            file.delete()
            throw failure
        }
    }

    override fun stop(): File {
        val recorder = checkNotNull(activeRecorder) { "No recording is in progress." }
        val file = checkNotNull(activeFile)
        activeRecorder = null
        activeFile = null
        try {
            recorder.stop()
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw IllegalStateException("The recording was too short. Please try again.", failure)
        } finally {
            recorder.release()
        }
    }

    override fun cancel() {
        val recorder = activeRecorder
        val file = activeFile
        activeRecorder = null
        activeFile = null
        runCatching { recorder?.stop() }
        recorder?.release()
        file?.delete()
    }

    private companion object {
        const val RecordingDirectory = "voice-input"
        const val RecordingPrefix = "voice-"
        const val RecordingSuffix = ".m4a"
        const val AudioSampleRate = 16_000
        const val AudioBitRate = 64_000
    }
}
