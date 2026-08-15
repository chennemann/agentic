package de.chennemann.agentic.domain.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import de.chennemann.agentic.t3.contract.UploadChatAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

const val MaxImageAttachmentCount = 8
const val MaxImageAttachmentBytes = 10L * 1024 * 1024

fun interface ImageAttachmentReader {
    suspend fun read(uri: String): UploadChatAttachment
}

class AndroidImageAttachmentReader(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
) : ImageAttachmentReader {
    override suspend fun read(uri: String): UploadChatAttachment = withContext(ioDispatcher) {
        val contentUri = Uri.parse(uri)
        val mimeType = contentResolver.getType(contentUri)
            ?.takeIf { it.startsWith("image/") }
            ?: throw IllegalArgumentException("Only images can be attached.")
        val name = contentResolver.query(contentUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }?.getString(0)
            }
            ?.take(255)
            ?: "image"
        val bytes = contentResolver.openInputStream(contentUri)?.use { stream ->
            val buffer = ByteArray(MaxImageAttachmentBytes.toInt() + 1)
            var offset = 0
            while (offset < buffer.size) {
                val count = stream.read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                offset += count
            }
            buffer.copyOf(offset)
        } ?: throw IllegalArgumentException("The selected image could not be read.")
        require(bytes.isNotEmpty()) { "The selected image is empty." }
        require(bytes.size <= MaxImageAttachmentBytes) { "Images must be 10 MiB or smaller." }
        UploadChatAttachment(
            name = name,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            dataUrl = "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
        )
    }
}
