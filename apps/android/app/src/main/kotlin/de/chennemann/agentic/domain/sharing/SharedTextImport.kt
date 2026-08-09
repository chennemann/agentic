package de.chennemann.agentic.domain.sharing

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

sealed interface SharedTextParseResult {
    data class Accepted(val fingerprint: String, val text: String) : SharedTextParseResult
    data class Rejected(val message: String) : SharedTextParseResult
}

object SharedTextIntentParser {
    fun parse(action: String?, type: String?, text: String?): SharedTextParseResult {
        if (action != "android.intent.action.SEND" || type != "text/plain") {
            return SharedTextParseResult.Rejected("Only shared text and URLs are supported.")
        }
        val sanitized = text.orEmpty()
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .trim()
        if (sanitized.isEmpty()) return SharedTextParseResult.Rejected("The shared content is empty.")
        if (sanitized.length > MaxSharedTextLength) return SharedTextParseResult.Rejected("The shared content is too long.")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(sanitized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return SharedTextParseResult.Accepted(fingerprint, sanitized)
    }

    private const val MaxSharedTextLength = 100_000
}

data class PendingSharedText(val fingerprint: String, val text: String)

interface SharedTextImportRepository {
    val pending: Flow<PendingSharedText?>
    val error: Flow<String?>
    suspend fun receive(result: SharedTextParseResult)
    suspend fun markImported(fingerprint: String)
    suspend fun discard(fingerprint: String)
    suspend fun clearError()
}
