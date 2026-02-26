package de.chennemann.opencode.mobile.streamingmarkdown

enum class MarkdownKind {
    TEXT,
    INLINE_CODE,
    EMPHASIS,
    STRONG,
    LINK,
    BLOCK_CODE,
}

data class MarkdownRun(
    val kind: MarkdownKind,
    val value: String,
    val href: String? = null,
)

class StreamingMarkdownParser {
    private val runs = mutableListOf<MarkdownRun>()
    private var open = 0
    private var ticks = 0
    private var slash = false

    fun start(): List<MarkdownRun> {
        reset()
        return snapshot()
    }

    fun write(chunk: String): List<MarkdownRun> {
        chunk
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .forEach { char -> step(char) }
        return snapshot()
    }

    fun end(): List<MarkdownRun> {
        flush(true)
        if (!slash) return snapshot()
        add(kind(), "\\")
        slash = false
        return snapshot()
    }

    fun reset() {
        runs.clear()
        open = 0
        ticks = 0
        slash = false
    }

    fun snapshot(): List<MarkdownRun> = runs.toList()

    private fun step(char: Char) {
        if (ticks > 0 && char != '`') {
            flush(false)
        }
        if (slash) {
            if (char == '`') {
                add(kind(), "`")
                slash = false
                return
            }
            add(kind(), "\\")
            slash = false
        }
        if (char == '\\') {
            slash = true
            return
        }
        if (char == '`') {
            ticks += 1
            return
        }
        if (char == '\n') {
            if (ticks > 0) {
                flush(false)
            }
            open = 0
            add(MarkdownKind.TEXT, "\n")
            return
        }
        add(kind(), char.toString())
    }

    private fun flush(end: Boolean) {
        if (ticks == 0) return
        if (open == 0) {
            if (end) {
                ticks = 0
                return
            }
            open = ticks
            ticks = 0
            return
        }
        if (ticks == open) {
            open = 0
            ticks = 0
            return
        }
        add(MarkdownKind.INLINE_CODE, "`".repeat(ticks))
        ticks = 0
    }

    private fun kind() = if (open > 0) MarkdownKind.INLINE_CODE else MarkdownKind.TEXT

    private fun add(kind: MarkdownKind, value: String) {
        if (value.isEmpty()) return
        val run = runs.lastOrNull()
        if (run == null) {
            runs += MarkdownRun(kind = kind, value = value)
            return
        }
        if (run.kind != kind) {
            runs += MarkdownRun(kind = kind, value = value)
            return
        }
        runs[runs.lastIndex] = run.copy(value = run.value + value)
    }
}

fun parseMarkdown(content: String): List<MarkdownRun> {
    val parser = StreamingMarkdownParser()
    parser.start()
    parser.write(content)
    return parser.end()
}

fun parseMarkdownDocument(content: String): List<MarkdownRun> {
    val runs = mutableListOf<MarkdownRun>()
    var block = false
    val normalized = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val lines = normalized.split('\n')
    lines.forEachIndexed { index, line ->
            if (line.trimStart().startsWith("```")) {
                block = !block
                return@forEachIndexed
            }
            val text = if (index == lines.lastIndex) {
                line
            } else {
                "$line\n"
            }
            if (text.isEmpty()) {
                return@forEachIndexed
            }
            if (block) {
                appendRun(runs, MarkdownKind.BLOCK_CODE, text)
                return@forEachIndexed
            }
            parseMarkdown(text).forEach { run ->
                appendRun(runs, run.kind, run.value, run.href)
            }
        }
    return runs
}

private fun appendRun(
    runs: MutableList<MarkdownRun>,
    kind: MarkdownKind,
    value: String,
    href: String? = null,
) {
    if (value.isEmpty()) return
    val run = runs.lastOrNull()
    if (run == null) {
        runs += MarkdownRun(kind = kind, value = value, href = href)
        return
    }
    if (run.kind != kind || run.href != href) {
        runs += MarkdownRun(kind = kind, value = value, href = href)
        return
    }
    runs[runs.lastIndex] = run.copy(value = run.value + value)
}
