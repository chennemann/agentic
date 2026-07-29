package de.chennemann.agentic.data.t3

data class SseEvent(
    val id: String?,
    val event: String?,
    val data: String,
)

class SseParser {
    private var id: String? = null
    private var event: String? = null
    private val data = mutableListOf<String>()

    fun feedLine(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return emit()
        if (line.startsWith(':')) return null
        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        val value = if (separator < 0) {
            ""
        } else {
            line.substring(separator + 1).removePrefix(" ")
        }
        when (field) {
            "id" -> if ('\u0000' !in value) id = value
            "event" -> event = value
            "data" -> data += value
        }
        return null
    }

    fun finish(): SseEvent? = emit()

    private fun emit(): SseEvent? {
        if (data.isEmpty()) {
            event = null
            return null
        }
        return SseEvent(id = id, event = event, data = data.joinToString("\n")).also {
            event = null
            data.clear()
        }
    }
}
