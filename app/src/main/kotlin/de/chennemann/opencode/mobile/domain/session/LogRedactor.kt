package de.chennemann.opencode.mobile.domain.session

class LogRedactor {
    fun redact(message: String): String {
        return message
            .replace(urlRegex, "[url]")
            .replace(tokenRegex, "[token]")
            .replace(bearerRegex, "Bearer [redacted]")
            .replace(pathRegex, "[path]")
    }

    fun redact(context: Map<String, String>): Map<String, String> {
        return context.mapValues {
            val key = it.key.lowercase()
            if (sensitiveKeys.any(key::contains)) {
                "[redacted]"
            } else {
                redact(it.value)
            }
        }
    }

    fun throwable(error: Throwable?): String? {
        if (error == null) return null
        val text = error.message?.trim().orEmpty()
        if (text.isBlank()) return error::class.java.simpleName
        return "${error::class.java.simpleName}: ${redact(text)}"
    }
}

private val sensitiveKeys = listOf("token", "secret", "password", "authorization", "cookie", "api_key", "apikey")
private val urlRegex = Regex("https?://[^\\s]+")
private val tokenRegex = Regex("(?i)(token|secret|password|api[_-]?key)\\s*[=:]\\s*[^\\s,;]+")
private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-]+")
private val pathRegex = Regex("([A-Za-z]:\\\\[^\\s]+|/(Users|home|var|data)[^\\s]*)")
