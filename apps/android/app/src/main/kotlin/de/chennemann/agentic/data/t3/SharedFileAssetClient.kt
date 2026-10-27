package de.chennemann.agentic.data.t3

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a download wrote; [truncated] downloads stopped at their byte budget. */
data class AssetDownload(val bytes: Long, val truncated: Boolean, val contentLength: Long?)

/** Downloads any file the environment shares through a signed asset URL. */
interface SharedFileAssetClient {
    /**
     * Writes the asset to [target]. Past [maxBytes] the download stops: truncated when
     * [truncate] is set, otherwise it fails with [AssetTooLargeException].
     */
    suspend fun downloadAsset(
        baseUrl: String,
        bearerToken: String,
        resource: JsonObject,
        target: File,
        maxBytes: Long,
        truncate: Boolean = false,
    ): AssetDownload
}

class AssetTooLargeException(val maxBytes: Long) : Exception("The file is larger than ${maxBytes / (1024 * 1024)} MiB.")

/** A message attachment, downloaded as stored. */
fun attachmentAssetResource(attachmentId: String): JsonObject = buildJsonObject {
    put("_tag", "attachment")
    put("attachmentId", attachmentId)
}

/**
 * A file on the environment host of any type. Relative paths resolve against the thread workspace and
 * `~` against the host's home directory.
 */
fun mediaFileDownloadResource(threadId: String, path: String): JsonObject = buildJsonObject {
    put("_tag", "media-file")
    put("threadId", threadId)
    put("path", path)
    put("disposition", "attachment")
}
