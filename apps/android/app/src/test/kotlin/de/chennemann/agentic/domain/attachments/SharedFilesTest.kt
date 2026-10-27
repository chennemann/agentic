package de.chennemann.agentic.domain.attachments

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.AssetDownload
import de.chennemann.agentic.data.t3.SharedFileAssetClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SharedFilesTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `kinds follow the reported type and fall back to the extension`() {
        assertEquals(SharedFileKind.Svg, sharedFileKind("logo.svg"))
        assertEquals(SharedFileKind.Svg, sharedFileKind("logo", "image/svg+xml"))
        assertEquals(SharedFileKind.Image, sharedFileKind("photo.HEIC"))
        assertEquals(SharedFileKind.Audio, sharedFileKind("voice.m4a"))
        assertEquals(SharedFileKind.Audio, sharedFileKind("recording", "audio/webm"))
        assertEquals(SharedFileKind.Video, sharedFileKind("demo.mp4"))
        assertEquals(SharedFileKind.Pdf, sharedFileKind("spec.pdf", "application/octet-stream"))
        assertEquals(SharedFileKind.Markdown, sharedFileKind("README.md"))
        assertEquals(SharedFileKind.Delimited, sharedFileKind("data.tsv"))
        assertEquals(SharedFileKind.Text, sharedFileKind("build.gradle.kts"))
        assertEquals(SharedFileKind.Text, sharedFileKind("payload", "application/json"))
        assertEquals(SharedFileKind.Binary, sharedFileKind("report.docx"))
        assertEquals(SharedFileKind.Unknown, sharedFileKind("Makefile.custom"))
        // A specific type the server stored wins over a misleading name.
        assertEquals(SharedFileKind.Pdf, sharedFileKind("download.bin", "application/pdf"))
    }

    @Test
    fun `only kinds the workspace text viewer cannot show open in the shared viewer`() {
        assertTrue(opensInSharedViewer("/tmp/out/voice.mp3"))
        assertTrue(opensInSharedViewer("docs/diagram.svg"))
        assertTrue(opensInSharedViewer("C:\\reports\\q3.pdf"))
        assertTrue(opensInSharedViewer("assets/shot.png"))
        assertFalse(opensInSharedViewer("src/main.kt"))
        assertFalse(opensInSharedViewer("notes.md"))
        assertFalse(opensInSharedViewer("Dockerfile"))
    }

    @Test
    fun `other apps receive a specific type`() {
        assertEquals("audio/mpeg", sharedFileMimeType("a.mp3"))
        assertEquals("image/svg+xml", sharedFileMimeType("a.svg", "application/octet-stream"))
        assertEquals("application/zip", sharedFileMimeType("a.zip"))
        assertEquals("text/plain", sharedFileMimeType("a.kt"))
        assertEquals("application/octet-stream", sharedFileMimeType("a.unknown"))
        assertEquals("application/x-custom", sharedFileMimeType("a.zip", "application/x-custom; charset=binary"))
    }

    @Test
    fun `text decoding rejects binary data and invalid UTF-8 but tolerates a cut-off tail`() {
        assertEquals("héllo", decodeSharedText("héllo".toByteArray(), truncated = false))
        assertNull(decodeSharedText(byteArrayOf(0x41, 0x00, 0x42), truncated = false))
        assertNull(decodeSharedText(byteArrayOf(0xC3.toByte(), 0x28), truncated = false))
        val cut = "aé".toByteArray().dropLast(1).toByteArray()
        assertEquals("a", decodeSharedText(cut, truncated = true))
        assertNull(decodeSharedText(cut, truncated = false))
    }

    @Test
    fun `delimited text keeps quoted delimiters, quotes and line breaks`() {
        val table = parseDelimited("name,note\r\n\"Doe, J\",\"said \"\"hi\"\"\nthen left\"\nlast,\n", ',')
        assertEquals(
            listOf(listOf("name", "note"), listOf("Doe, J", "said \"hi\"\nthen left"), listOf("last", "")),
            table.rows,
        )
        assertFalse(table.truncated)
    }

    @Test
    fun `delimited tables are limited to the configured rows and columns`() {
        val text = (1..5).joinToString("\n") { row -> (1..4).joinToString("\t") { "r${row}c$it" } }
        val table = parseDelimited(text, '\t', maxRows = 2, maxColumns = 3)
        assertEquals(listOf(listOf("r1c1", "r1c2", "r1c3"), listOf("r2c1", "r2c2", "r2c3")), table.rows)
        assertTrue(table.truncated)
    }

    @Test
    fun `stored names keep their extension without path or control characters`() {
        assertEquals("a_b_c.mp3", safeFileName("a/b\\c.mp3"))
        assertEquals("env", safeFileName(".env"))
        assertEquals("file", safeFileName("..."))
    }

    @Test
    fun `attachments download once as stored and host paths ask for a download of any type`() = runTest {
        val client = RecordingAssetClient("audio-bytes")
        val service = service(client)
        val attachment = SharedFileSource.Attachment("thread-1-att", "voice.mp3", "audio/mpeg")

        val first = service.load(attachment)
        val second = service.load(attachment)

        assertEquals(first.file, second.file)
        assertEquals("voice.mp3", first.file.name)
        assertEquals("audio-bytes", first.file.readText())
        assertEquals(SharedFileKind.Audio, first.kind)
        assertEquals("audio/mpeg", first.mimeType)
        assertEquals(1, client.resources.size)
        assertEquals(
            buildJsonObject {
                put("_tag", "attachment")
                put("attachmentId", "thread-1-att")
            },
            client.resources.single(),
        )

        service.load(SharedFileSource.HostPath("thread-1", "~/Downloads/report.pdf"))
        assertEquals(
            buildJsonObject {
                put("_tag", "media-file")
                put("threadId", "thread-1")
                put("path", "~/Downloads/report.pdf")
                put("disposition", "attachment")
            },
            client.resources.last(),
        )
    }

    @Test
    fun `text previews stop at the text budget and reject binary files`() = runTest {
        val service = service(RecordingAssetClient("line\n".repeat(10), contentLength = 4096))
        val text = service.text(SharedFileSource.HostPath("thread-1", "/var/log/app.log"))
        assertEquals("line\n".repeat(10), text.contents)
        assertEquals(4096, text.byteLength)

        val binary = service(RecordingAssetClient("a\u0000b"))
        val failure = runCatching { binary.text(SharedFileSource.HostPath("thread-1", "/tmp/blob")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(directory.listFiles().orEmpty().none { it.isFile })
    }

    private fun service(client: SharedFileAssetClient) = SharedFilesService(
        environments = Environments,
        credentials = Credentials,
        client = client,
        directory = directory,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class RecordingAssetClient(private val body: String, private val contentLength: Long? = null) : SharedFileAssetClient {
        val resources = mutableListOf<JsonObject>()

        override suspend fun downloadAsset(
            baseUrl: String,
            bearerToken: String,
            resource: JsonObject,
            target: File,
            maxBytes: Long,
            truncate: Boolean,
        ): AssetDownload {
            assertEquals("http://localhost", baseUrl)
            assertEquals("test-token", bearerToken)
            resources += resource
            val bytes = body.toByteArray()
            target.writeBytes(bytes)
            return AssetDownload(bytes.size.toLong(), truncated = false, contentLength = contentLength)
        }
    }

    private object Credentials : CredentialStore {
        override suspend fun read(environmentId: String) = "test-token"
        override suspend fun write(environmentId: String, bearerToken: String) = Unit
        override suspend fun remove(environmentId: String) = Unit
    }

    private object Environments : EnvironmentRepository {
        private val environment = SavedEnvironment("env", "Server", "http://localhost", "linux", "x64", "1", false, null)
        override val environments = MutableStateFlow(listOf(environment))
        override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(environment)
        override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
        override suspend fun select(environmentId: String) = Unit
        override suspend fun remove(environmentId: String) = Unit
        override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
    }
}
