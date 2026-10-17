package de.chennemann.agentic.domain.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import de.chennemann.agentic.domain.composer.ComposerFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The message cap T3 enforces across images and files. */
const val MaxMessageAttachmentCount = 100

/** Draft files are named by a random id; anything else in the directory is not ours. */
val ComposerFileName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "%.1f MiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
}

interface ComposerFileStore {
    /** Copies a picked document into app storage, so the draft survives losing the picker's grant. */
    suspend fun import(uri: String, maxBytes: Long): ComposerFile

    /** The stored copy, or null once it was pruned or never existed. */
    fun stored(file: ComposerFile): File?
}

class AndroidComposerFileStore(
    private val contentResolver: ContentResolver,
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher,
) : ComposerFileStore {
    override suspend fun import(uri: String, maxBytes: Long): ComposerFile = withContext(ioDispatcher) {
        val contentUri = Uri.parse(uri)
        val name = contentResolver.query(contentUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> cursor.takeIf { it.moveToFirst() }?.getString(0) }
            ?.replace(Regex("[\\r\\n/\\\\]"), " ")
            ?.trim()
            ?.take(255)
            ?.ifEmpty { null }
            ?: "file"
        val mimeType = contentResolver.getType(contentUri)?.takeIf { it.length <= 100 }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        check(directory.isDirectory || directory.mkdirs()) { "Draft attachment storage is unavailable." }
        val id = UUID.randomUUID().toString()
        val target = File(directory, id)
        var size = 0L
        try {
            val input = contentResolver.openInputStream(contentUri)
                ?: throw IllegalArgumentException("'$name' could not be read.")
            input.use { stream ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= maxBytes) { "'$name' is larger than ${maxBytes / (1024 * 1024)} MiB." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(size > 0) { "'$name' is empty." }
        } catch (cause: Throwable) {
            target.delete()
            throw cause
        }
        ComposerFile(id = id, name = name, mimeType = mimeType, sizeBytes = size, localFile = id)
    }

    override fun stored(file: ComposerFile): File? = file.localFile
        .takeIf(ComposerFileName::matches)
        ?.let { File(directory, it) }
        ?.takeIf(File::isFile)
}
