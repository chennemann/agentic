package de.chennemann.agentic.ui.chat

import de.chennemann.agentic.domain.attachments.SharedFileKind
import de.chennemann.agentic.domain.attachments.sharedFileKind
import de.chennemann.agentic.domain.orchestration.parseMarkdownFileLink

/** A message split into Markdown text and media that stand on their own line. */
sealed interface MessageSegment {
    data class Text(val markdown: String) : MessageSegment
    data class Image(val alt: String, val path: String) : MessageSegment

    /** Audio a message links on its own line, played in place; [path] is the resolved host path. */
    data class Audio(val label: String, val path: String) : MessageSegment
}

private val StandaloneImage = Regex("""^\s*!\[([^\]]*)]\(\s*<?([^)>\s]+(?:\s[^)>]+)*?)>?(?:\s+"[^"]*")?\s*\)\s*$""")
private val StandaloneLink = Regex("""^\s*!?\[([^\]]*)]\(\s*<?([^)>\s]+(?:\s[^)>]+)*?)>?(?:\s+"[^"]*")?\s*\)\s*$""")
private val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")

/**
 * Splits out `![alt](path)` lines so the image can be shown inline, and lines that only link or embed
 * an audio file so it can play inline. Fenced code blocks are left untouched, remote URLs stay
 * Markdown text, and messages without media return a single segment.
 */
fun splitMessageImages(content: String): List<MessageSegment> {
    if ("](" !in content) return listOf(MessageSegment.Text(content))
    val segments = mutableListOf<MessageSegment>()
    val text = StringBuilder()
    var fence: String? = null
    fun flush() {
        if (text.isNotBlank()) segments += MessageSegment.Text(text.toString().trim('\n'))
        text.clear()
    }
    content.split('\n').forEach { line ->
        val trimmed = line.trimStart()
        val marker = trimmed.takeWhile { it == '`' || it == '~' }.takeIf { it.length >= 3 }
        if (marker != null) {
            fence = when {
                fence == null -> marker
                marker.first() == fence!!.first() && marker.length >= fence!!.length -> null
                else -> fence
            }
        }
        val match = if (fence == null && marker == null) StandaloneImage.matchEntire(line) else null
        val path = match?.groupValues?.get(2)?.trim()
        val audio = if (fence == null && marker == null) standaloneAudio(line) else null
        if (match != null && path != null && path.isPreviewableImagePath()) {
            flush()
            segments += MessageSegment.Image(alt = match.groupValues[1], path = path)
        } else if (audio != null) {
            flush()
            segments += audio
        } else {
            if (text.isNotEmpty()) text.append('\n')
            text.append(line)
        }
    }
    flush()
    if (segments.none { it !is MessageSegment.Text }) return listOf(MessageSegment.Text(content))
    return segments
}

private fun standaloneAudio(line: String): MessageSegment.Audio? {
    val match = StandaloneLink.matchEntire(line) ?: return null
    val target = parseMarkdownFileLink(match.groupValues[2].trim()) ?: return null
    if (target.line != null || sharedFileKind(target.path) != SharedFileKind.Audio) return null
    val name = target.path.substringAfterLast('/').substringAfterLast('\\')
    return MessageSegment.Audio(label = match.groupValues[1].trim().ifEmpty { name }, path = target.path)
}

private fun String.isPreviewableImagePath(): Boolean {
    val lower = lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) return false
    return lower.substringBefore('?').substringBefore('#').substringAfterLast('.', "") in ImageExtensions
}
