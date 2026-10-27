package de.chennemann.agentic.domain.attachments

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.SharedFileAssetClient
import de.chennemann.agentic.data.t3.attachmentAssetResource
import de.chennemann.agentic.data.t3.mediaFileDownloadResource
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The cap T3 enforces on file attachments, also applied to files opened from the host. */
const val MaxSharedFileBytes = 50L * 1024 * 1024
const val MaxSharedTextBytes = 1024L * 1024

/** A file an agent or user shared in a conversation. */
sealed interface SharedFileSource {
    val name: String
    val mimeType: String?

    data class Attachment(
        val attachmentId: String,
        override val name: String,
        override val mimeType: String?,
    ) : SharedFileSource

    /** A path a message names on the environment host: absolute, `~/`, or relative to the thread workspace. */
    data class HostPath(val threadId: String, val path: String) : SharedFileSource {
        override val name: String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { "file" }
        override val mimeType: String? = null
    }
}

enum class SharedFileKind { Image, Svg, Audio, Video, Pdf, Markdown, Delimited, Html, Text, Binary, Unknown }

private val ImageMimeTypes = mapOf(
    "avif" to "image/avif",
    "bmp" to "image/bmp",
    "gif" to "image/gif",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "ico" to "image/x-icon",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "png" to "image/png",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "webp" to "image/webp",
)

private val AudioMimeTypes = mapOf(
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "flac" to "audio/flac",
    "aac" to "audio/aac",
    "m4a" to "audio/mp4",
    "opus" to "audio/ogg",
    "aiff" to "audio/aiff",
    "weba" to "audio/webm",
    "webm-audio" to "audio/webm",
)

private val VideoMimeTypes = mapOf(
    "avi" to "video/x-msvideo",
    "m4v" to "video/mp4",
    "mkv" to "video/x-matroska",
    "mov" to "video/quicktime",
    "mp4" to "video/mp4",
    "ogv" to "video/ogg",
    "webm" to "video/webm",
)

private val BinaryMimeTypes = mapOf(
    "zip" to "application/zip",
    "jar" to "application/java-archive",
    "tar" to "application/x-tar",
    "gz" to "application/gzip",
    "tgz" to "application/gzip",
    "7z" to "application/x-7z-compressed",
    "sqlite" to "application/vnd.sqlite3",
    "sqlite3" to "application/vnd.sqlite3",
    "db" to "application/vnd.sqlite3",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "odt" to "application/vnd.oasis.opendocument.text",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "rtf" to "application/rtf",
    "epub" to "application/epub+zip",
    "ttf" to "font/ttf",
    "otf" to "font/otf",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "apk" to "application/vnd.android.package-archive",
    "exe" to "application/octet-stream",
    "bin" to "application/octet-stream",
)

private val TextExtensions = (
    "txt log json jsonc jsonl ndjson yaml yml toml ini conf config env xml css scss sass less js jsx mjs cjs ts tsx " +
        "mts cts py pyi rb go rs swift kt kts java c h cc cpp hpp cs php sh bash zsh fish sql graphql gql vue svelte r " +
        "lua ex exs erl hs clj dart diff patch lock properties gradle ipynb"
    ).split(' ').toSet()

private val GenericMimeTypes = setOf("", "application/octet-stream", "text/plain")

private fun extensionOf(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

/** Mirrors T3's `filePreviewKind`, limited to what this client can show. */
fun sharedFileKind(name: String, mimeType: String? = null): SharedFileKind {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val extension = extensionOf(name)
    if (mime == "image/svg+xml" || (mime in GenericMimeTypes && extension == "svg")) return SharedFileKind.Svg
    if (mime !in GenericMimeTypes) {
        when {
            mime == "application/pdf" -> return SharedFileKind.Pdf
            mime == "text/html" -> return SharedFileKind.Html
            mime == "text/markdown" || mime == "text/x-markdown" -> return SharedFileKind.Markdown
            mime == "text/csv" || mime == "text/tab-separated-values" -> return SharedFileKind.Delimited
            mime.startsWith("image/") -> return SharedFileKind.Image
            mime.startsWith("audio/") -> return SharedFileKind.Audio
            mime.startsWith("video/") -> return SharedFileKind.Video
            mime in BinaryMimeTypes.values || mime.startsWith("font/") -> return SharedFileKind.Binary
            mime.startsWith("text/") ||
                Regex("application/(json|.*\\+json|xml|.*\\+xml|javascript|x-javascript|yaml|x-yaml|toml|sql)").matches(mime) ->
                return SharedFileKind.Text
        }
    }
    return when {
        extension == "pdf" -> SharedFileKind.Pdf
        extension == "html" || extension == "htm" -> SharedFileKind.Html
        extension in setOf("md", "markdown", "mdown", "mkd", "mdx") -> SharedFileKind.Markdown
        extension == "csv" || extension == "tsv" -> SharedFileKind.Delimited
        extension in ImageMimeTypes -> SharedFileKind.Image
        extension in AudioMimeTypes -> SharedFileKind.Audio
        extension in VideoMimeTypes -> SharedFileKind.Video
        extension in BinaryMimeTypes -> SharedFileKind.Binary
        extension in TextExtensions -> SharedFileKind.Text
        mime == "text/plain" -> SharedFileKind.Text
        else -> SharedFileKind.Unknown
    }
}

/** The MIME type to hand other apps, preferring a specific type the server reported. */
fun sharedFileMimeType(name: String, mimeType: String? = null): String {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (mime !in GenericMimeTypes) return mime
    val extension = extensionOf(name)
    return when (extension) {
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "html", "htm" -> "text/html"
        "md", "markdown", "mdx" -> "text/markdown"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "json" -> "application/json"
        else -> ImageMimeTypes[extension] ?: AudioMimeTypes[extension] ?: VideoMimeTypes[extension]
            ?: BinaryMimeTypes[extension] ?: if (extension in TextExtensions || mime == "text/plain") "text/plain" else "application/octet-stream"
    }
}

/**
 * Kinds the workspace text viewer cannot show. Links to them open the shared-file viewer, which plays,
 * renders or hands them to another app; everything else keeps opening as workspace text.
 */
fun opensInSharedViewer(path: String): Boolean = sharedFileKind(path) in setOf(
    SharedFileKind.Image,
    SharedFileKind.Svg,
    SharedFileKind.Audio,
    SharedFileKind.Video,
    SharedFileKind.Pdf,
    SharedFileKind.Delimited,
    SharedFileKind.Html,
    SharedFileKind.Binary,
)

/** A shared file downloaded to app storage. */
data class SharedFile(
    val source: SharedFileSource,
    val file: File,
    val kind: SharedFileKind,
    val mimeType: String,
    val sizeBytes: Long,
)

data class SharedText(val contents: String, val byteLength: Long, val truncated: Boolean)

interface SharedFileLoader {
    suspend fun load(source: SharedFileSource): SharedFile

    /** The first [MaxSharedTextBytes] of a file as UTF-8 text; fails for binary data. */
    suspend fun text(source: SharedFileSource): SharedText

    suspend fun readText(file: SharedFile): SharedText
}

class SharedFilesService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val client: SharedFileAssetClient,
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher,
) : SharedFileLoader {
    override suspend fun load(source: SharedFileSource): SharedFile {
        val (baseUrl, token, environmentId) = context()
        return withContext(ioDispatcher) {
            prune()
            val folder = File(directory, cacheKey(environmentId, source))
            val target = File(folder, safeFileName(source.name))
            if (!target.isFile) {
                check(folder.isDirectory || folder.mkdirs()) { "File storage is unavailable." }
                val partial = File.createTempFile("download", null, folder)
                try {
                    client.downloadAsset(baseUrl, token, source.resource(), partial, MaxSharedFileBytes)
                    check(partial.renameTo(target) || target.isFile) { "The file could not be stored." }
                } finally {
                    partial.delete()
                }
            }
            folder.setLastModified(System.currentTimeMillis())
            SharedFile(
                source = source,
                file = target,
                kind = sharedFileKind(source.name, source.mimeType),
                mimeType = sharedFileMimeType(source.name, source.mimeType),
                sizeBytes = target.length(),
            )
        }
    }

    override suspend fun text(source: SharedFileSource): SharedText {
        val (baseUrl, token, _) = context()
        return withContext(ioDispatcher) {
            check(directory.isDirectory || directory.mkdirs()) { "File storage is unavailable." }
            val partial = File.createTempFile("text", null, directory)
            try {
                val download = client.downloadAsset(baseUrl, token, source.resource(), partial, MaxSharedTextBytes, truncate = true)
                val bytes = partial.readBytes()
                val contents = requireNotNull(decodeSharedText(bytes, download.truncated)) {
                    "This file is not UTF-8 text and cannot be shown here."
                }
                SharedText(contents, download.contentLength ?: bytes.size.toLong(), download.truncated)
            } finally {
                partial.delete()
            }
        }
    }

    override suspend fun readText(file: SharedFile): SharedText = withContext(ioDispatcher) {
        val bytes = file.file.inputStream().use { it.readNBytes(MaxSharedTextBytes.toInt()) }
        val truncated = file.sizeBytes > bytes.size
        val contents = requireNotNull(decodeSharedText(bytes, truncated)) {
            "This file is not UTF-8 text and cannot be shown here."
        }
        SharedText(contents, file.sizeBytes, truncated)
    }

    private suspend fun context(): Triple<String, String, String> {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val token = requireNotNull(credentials.read(environment.id)) {
            "The environment credential is unavailable. Pair the environment again."
        }
        return Triple(environment.baseUrl, token, environment.id)
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }
}

private fun SharedFileSource.resource() = when (this) {
    is SharedFileSource.Attachment -> attachmentAssetResource(attachmentId)
    is SharedFileSource.HostPath -> mediaFileDownloadResource(threadId, path)
}

internal fun cacheKey(environmentId: String, source: SharedFileSource): String {
    val identity = when (source) {
        is SharedFileSource.Attachment -> "attachment\u0000${source.attachmentId}"
        is SharedFileSource.HostPath -> "path\u0000${source.threadId}\u0000${source.path}"
    }
    return MessageDigest.getInstance("SHA-256").digest("$environmentId\u0000$identity".toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)
}

/** Keeps the extension other apps detect types by while dropping path and control characters. */
internal fun safeFileName(name: String): String = name
    .replace(Regex("[\\u0000-\\u001f/\\\\:*?\"<>|]"), "_")
    .trim()
    .trimStart('.')
    .take(120)
    .ifEmpty { "file" }

/** Mirrors T3's `decodeFilePreviewText`: null for binary data or invalid UTF-8; a cut-off tail is tolerated. */
fun decodeSharedText(bytes: ByteArray, truncated: Boolean): String? {
    if (bytes.any { it == 0.toByte() }) return null
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val output = CharBuffer.allocate(bytes.size)
    val result = decoder.decode(ByteBuffer.wrap(bytes), output, !truncated)
    if (result.isError) return null
    if (!truncated && decoder.flush(output).isError) return null
    output.flip()
    return output.toString()
}

data class DelimitedTable(val rows: List<List<String>>, val truncated: Boolean)

/** Parses CSV or TSV with quoted fields, keeping the first [maxRows] rows and [maxColumns] columns. */
fun parseDelimited(text: String, delimiter: Char, maxRows: Int = 100, maxColumns: Int = 30): DelimitedTable {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var truncated = false
    var index = 0
    fun endRow() {
        row += cell.toString()
        cell.clear()
        if (row.size > maxColumns) truncated = true
        rows += row.take(maxColumns)
        row = mutableListOf()
    }
    while (index < text.length) {
        val char = text[index]
        when {
            quoted && char == '"' && text.getOrNull(index + 1) == '"' -> {
                cell.append('"')
                index++
            }
            char == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
            quoted -> cell.append(char)
            char == delimiter -> {
                row += cell.toString()
                cell.clear()
            }
            char == '\r' && text.getOrNull(index + 1) == '\n' -> Unit
            char == '\n' || char == '\r' -> {
                endRow()
                if (rows.size >= maxRows) {
                    truncated = truncated || index < text.length - 1
                    return DelimitedTable(rows, truncated)
                }
            }
            else -> cell.append(char)
        }
        index++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
    return DelimitedTable(rows, truncated)
}
