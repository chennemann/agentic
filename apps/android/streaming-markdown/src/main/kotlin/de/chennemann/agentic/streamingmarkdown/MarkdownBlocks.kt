package de.chennemann.agentic.streamingmarkdown

sealed interface MarkdownBlock {
    data class Paragraph(
        val runs: List<MarkdownRun>,
    ) : MarkdownBlock

    data class Code(
        val language: String?,
        val content: String,
    ) : MarkdownBlock

    data class ListBlock(
        val ordered: Boolean,
        val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    data class Table(
        val header: List<List<MarkdownRun>>,
        val rows: List<List<List<MarkdownRun>>>,
    ) : MarkdownBlock
}

data class MarkdownListItem(
    val marker: String,
    val level: Int,
    val runs: List<MarkdownRun>,
)

fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val lines = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index += 1
            continue
        }

        fenceStart(line)?.let { fence ->
            val code = mutableListOf<String>()
            index += 1
            while (index < lines.size && !closesFence(lines[index], fence)) {
                code += lines[index]
                index += 1
            }
            if (index < lines.size) index += 1
            blocks += MarkdownBlock.Code(
                language = fence.language,
                content = code.joinToString("\n"),
            )
            continue
        }

        if (index + 1 < lines.size && isTableDelimiter(lines[index + 1])) {
            val header = splitTableRow(line).map(::parseMarkdown)
            val rows = mutableListOf<List<List<MarkdownRun>>>()
            index += 2
            while (index < lines.size && looksLikeTableRow(lines[index])) {
                rows += normalizeTableRow(splitTableRow(lines[index]), header.size).map(::parseMarkdown)
                index += 1
            }
            blocks += MarkdownBlock.Table(header = header, rows = rows)
            continue
        }

        listItem(line)?.let { first ->
            val ordered = first.ordered
            val items = mutableListOf<MarkdownListItem>()
            while (index < lines.size) {
                val item = listItem(lines[index]) ?: break
                if (item.ordered != ordered) break
                items += MarkdownListItem(
                    marker = item.marker,
                    level = item.level,
                    runs = parseMarkdown(item.content),
                )
                index += 1
            }
            blocks += MarkdownBlock.ListBlock(ordered = ordered, items = items)
            continue
        }

        val paragraph = mutableListOf(line)
        index += 1
        while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines, index)) {
            paragraph += lines[index]
            index += 1
        }
        blocks += MarkdownBlock.Paragraph(parseMarkdown(paragraph.joinToString("\n")))
    }
    return blocks
}

internal fun hasMarkdownBlockSyntax(content: String): Boolean {
    val lines = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
    return lines.indices.any { index ->
        fenceStart(lines[index]) != null ||
            listItem(lines[index]) != null ||
            index + 1 < lines.size && isTableDelimiter(lines[index + 1])
    }
}

private data class Fence(
    val character: Char,
    val length: Int,
    val language: String?,
)

private fun fenceStart(line: String): Fence? {
    val trimmed = line.trimStart()
    val character = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == character }.length
    if (length < 3) return null
    return Fence(
        character = character,
        length = length,
        language = trimmed.drop(length).trim().takeIf(String::isNotEmpty),
    )
}

private fun closesFence(line: String, fence: Fence): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= fence.length && trimmed.all { it == fence.character }
}

private data class ParsedListItem(
    val ordered: Boolean,
    val marker: String,
    val level: Int,
    val content: String,
)

private val unorderedList = Regex("^(\\s*)([-+*])\\s+(.+)$")
private val orderedList = Regex("^(\\s*)(\\d+[.)])\\s+(.+)$")

private fun listItem(line: String): ParsedListItem? {
    unorderedList.matchEntire(line)?.let { match ->
        return ParsedListItem(
            ordered = false,
            marker = "•",
            level = match.groupValues[1].length / 2,
            content = match.groupValues[3],
        )
    }
    orderedList.matchEntire(line)?.let { match ->
        return ParsedListItem(
            ordered = true,
            marker = match.groupValues[2],
            level = match.groupValues[1].length / 2,
            content = match.groupValues[3],
        )
    }
    return null
}

private fun startsBlock(lines: List<String>, index: Int): Boolean =
    fenceStart(lines[index]) != null ||
        listItem(lines[index]) != null ||
        index + 1 < lines.size && isTableDelimiter(lines[index + 1])

private fun isTableDelimiter(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { it.matches(Regex(":?-{3,}:?")) }
}

private fun looksLikeTableRow(line: String): Boolean = line.isNotBlank() && line.contains('|')

private fun splitTableRow(line: String): List<String> {
    val trimmed = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var escaped = false
    trimmed.forEach { character ->
        when {
            escaped -> {
                cell.append(character)
                escaped = false
            }
            character == '\\' -> escaped = true
            character == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }
            else -> cell.append(character)
        }
    }
    if (escaped) cell.append('\\')
    cells += cell.toString().trim()
    return cells
}

private fun normalizeTableRow(row: List<String>, size: Int): List<String> = when {
    row.size == size -> row
    row.size < size -> row + List(size - row.size) { "" }
    else -> row.take(size)
}
