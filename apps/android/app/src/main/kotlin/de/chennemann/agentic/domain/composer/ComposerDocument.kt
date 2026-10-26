package de.chennemann.agentic.domain.composer

import de.chennemann.agentic.t3.contract.T3Json
import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val COMPOSER_INPUT_MAX_CHARS = 120_000
const val PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES = 32 * 1024
const val COMPOSER_CONTEXT_MAX_RECORDS = 200

@Serializable
data class ComposerDocument(
    val text: String = "",
    val records: List<JsonObject> = emptyList(),
    val pastes: List<PastedTextFile> = emptyList(),
    val files: List<ComposerFile> = emptyList()
) {
    fun withText(value: String): ComposerDocument {
        val next = copy(text = value)
        if (records.size <= COMPOSER_CONTEXT_MAX_RECORDS) return next
        val live = next.referencedRecords().mapNotNull { it.string("contextId") }.toSet()
        val history = records.filter {
            it.string("contextId") !in live
        }.takeLast((COMPOSER_CONTEXT_MAX_RECORDS - live.size).coerceAtLeast(0))
        val retained = records.filter { it.string("contextId") in live } + history
        val attachments = retained.mapNotNull { it.string("attachmentId") }.toSet()
        return next.copy(
            records = retained,
            pastes = pastes.filter { it.id in attachments },
            files = files.filter { it.id in attachments }
        )
    }

    fun referencedRecords(): List<JsonObject> {
        val ids = composerTokens(text).mapNotNull { it.contextId }.toSet()
        return records.filter { it.string("contextId") in ids }
    }

    fun retained(): ComposerDocument {
        val live = referencedRecords()
        val ids = live.mapNotNull { it.string("attachmentId") }.toSet()
        return copy(records = live, pastes = pastes.filter { it.id in ids }, files = files.filter { it.id in ids })
    }
}

@Serializable
data class PastedTextFile(val id: String, val name: String, val text: String, val payloadFile: String? = null)

/** A file picked into a draft. Its bytes stay in app storage as [localFile] until the draft is sent. */
@Serializable
data class ComposerFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localFile: String
)

data class ComposerToken(
    val source: String,
    val start: Int,
    val end: Int,
    val kind: String,
    val label: String,
    val value: String,
    val contextId: String? = null
)

data class ComposerTrigger(val kind: String, val query: String, val start: Int, val end: Int)

/** Kotlin counterpart of the pinned upstream trigger, reference and inline-token helpers. */
fun composerTrigger(text: String, start: Int, end: Int = start): ComposerTrigger? {
    if (start != end || start !in 0..text.length) return null
    if (composerTokens(text).any { start > it.start && start < it.end }) return null
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
    val prefix = text.substring(lineStart.coerceAtMost(start), start)
    if (prefix.startsWith('/') && prefix.none(Char::isWhitespace)) {
        return ComposerTrigger("command", prefix.drop(1), lineStart, start)
    }
    val tokenStart = text.take(start).indexOfLast(Char::isWhitespace) + 1
    val token = text.substring(tokenStart, start)
    val kind = when (token.firstOrNull()) {
        '@' -> "path"
        '$' -> "skill"
        else -> return null
    }
    return ComposerTrigger(kind, token.drop(1), tokenStart, start)
}

private val ContextLink =
    Regex("!?\\[([^]\\n]{0,512})]\\(t3-context://v1/([a-z][a-z0-9-]{0,39})/([a-zA-Z0-9_-]{1,128})\\)")
private val FileLink = Regex("(?<!\\S)\\[((?:\\\\.|[^]\\\\\\n]){0,512})]\\(([^\\s)]+)\\)(?=\\s)")
private val SkillToken =
    Regex(
        "(?<!\\S)\\p{Sc}(?![0-9][0-9_]*(?:[kKmMbBtT]|[eE][0-9]+)?(?:\\s|$))(?=[a-zA-Z0-9:_-]*[a-zA-Z])([a-zA-Z0-9][a-zA-Z0-9:_-]*)(?=\\s)"
    )
private val BareMention = Regex("(?<!\\S)@(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^\\s@\"]+))(?=\\s)")
private val ScopedPackage = Regex("^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*(?:/[^\\s@\"]+)*$")

fun composerTokens(text: String, preserveTrailingFrom: List<ComposerToken> = emptyList()): List<ComposerToken> {
    val contexts = ContextLink.findAll(text).map {
        ComposerToken(
            it.value,
            it.range.first,
            it.range.last + 1,
            it.groupValues[2],
            it.groupValues[1],
            it.groupValues[3],
            it.groupValues[3]
        )
    }.toList()
    val files = FileLink.findAll(text).filter { match ->
        contexts.none { match.range.first < it.end && match.range.last >= it.start }
    }.mapNotNull {
        val destination = it.groupValues[2]
        val path = runCatching {
            java.net.URLDecoder.decode(destination.replace("+", "%2B"), Charsets.UTF_8)
        }.getOrDefault(destination)
        val windows = Regex("^[A-Za-z]:[/\\\\].*").matches(path)
        if (!windows && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(path)) return@mapNotNull null
        val label = it.groupValues[1].replace(Regex("\\\\(.)"), "$1")
        if (label != path.replace('\\', '/').substringAfterLast('/')) return@mapNotNull null
        ComposerToken(it.value, it.range.first, it.range.last + 1, "mention", label, path)
    }.toList()
    val mentions = BareMention.findAll(text).mapNotNull {
        val quoted = it.groups[1]?.value
        val path = quoted?.replace(Regex("\\\\(.)"), "$1") ?: it.groupValues[2]
        if (path.isEmpty() || (quoted == null && ScopedPackage.matches(path))) return@mapNotNull null
        ComposerToken(
            it.value,
            it.range.first,
            it.range.last + 1,
            "mention",
            path.replace('\\', '/').substringAfterLast('/'),
            path
        )
    }.toList()
    val skills = SkillToken.findAll(text).filter { match ->
        (contexts + files + mentions).none { match.range.first < it.end && match.range.last >= it.start }
    }.map {
        ComposerToken(it.value, it.range.first, it.range.last + 1, "skill", it.groupValues[1], it.groupValues[1])
    }.toList()
    val matched = (contexts + files + mentions + skills).toMutableList()
    preserveTrailingFrom.filter {
        it.end == text.length &&
            it.start >= 0 &&
            text.substring(it.start, it.end) == it.source
    }.forEach { token ->
        if (matched.none { it.start == token.start && it.end == token.end }) matched.add(token)
    }
    return matched.sortedBy { it.start }
}

fun composerFileLink(path: String): String {
    val label = path.replace(
        '\\',
        '/'
    ).substringAfterLast('/').replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
    // URI encodes spaces, Unicode and Markdown delimiters without interpreting paths as URLs.
    val encoded = URI(null, null, path.replace('\\', '/'), null).toASCIIString()
        .replace("(", "%28").replace(")", "%29").replace("?", "%3F").replace("#", "%23")
    return "[$label]($encoded)"
}

fun composerContextReference(kind: String, id: String, label: String): String {
    val clean = label.replace(Regex("[\\[\\]\\\\\\r\\n]"), " ").replace(Regex("\\s+"), " ").trim().take(200).ifEmpty {
        kind
    }
    return "${if (kind == "image") "!" else ""}[$clean](t3-context://v1/$kind/$id)"
}

fun pastedTextFile(text: String, existing: List<PastedTextFile>): PastedTextFile {
    val names = existing.map { it.name.lowercase() }.toSet()
    var name = "pasted-text.txt"
    var index = 2
    while (name in names) name = "pasted-text-${index++}.txt"
    return PastedTextFile(UUID.randomUUID().toString(), name, text)
}

fun pastedTextRecord(file: PastedTextFile): JsonObject = buildJsonObject {
    put("version", 1)
    put("kind", "file")
    put("contextId", file.id)
    put("label", file.name)
    put("attachmentId", file.id)
    put("name", file.name)
    put("mimeType", "text/plain")
    put("sizeBytes", file.text.toByteArray(Charsets.UTF_8).size)
}

fun composerFileRecord(file: ComposerFile): JsonObject = buildJsonObject {
    put("version", 1)
    put("kind", "file")
    put("contextId", file.id)
    put("label", file.name)
    put("attachmentId", file.id)
    put("name", file.name)
    put("mimeType", file.mimeType)
    put("sizeBytes", file.sizeBytes)
}

fun messageContext(records: List<JsonObject>): JsonObject? = records.takeIf { it.isNotEmpty() }?.let {
    buildJsonObject {
        put("version", 1)
        put("records", JsonArray(it))
    }
}

fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content

fun decodeComposerDocument(payload: String?): ComposerDocument = payload?.let {
    runCatching { T3Json.decodeFromString<ComposerDocument>(it) }.getOrNull()
        ?: runCatching { ComposerDocument(T3Json.decodeFromString<String>(it)) }.getOrNull()
} ?: ComposerDocument()
