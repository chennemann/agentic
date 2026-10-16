package de.chennemann.agentic.ui.review

import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable

/** The upstream native row format, independent of RPC DTOs and Android Views. */
@Serializable
data class ReviewRow(
    val kind: String,
    val id: String,
    val fileId: String,
    val filePath: String = "",
    val previousPath: String? = null,
    val changeType: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
    val text: String = "",
    val content: String = "",
    val change: String = "context",
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

data class ReviewFile(val id: String, val path: String, val rows: List<ReviewRow>)
data class ReviewDocument(val files: List<ReviewFile> = emptyList(), val notice: String? = null) {
    val rows: List<ReviewRow> by lazy { files.flatMap { it.rows } }
    val additions: Int get() = files.sumOf { it.rows.first().additions }
    val deletions: Int get() = files.sumOf { it.rows.first().deletions }
}

/** Convert standard Git unified patches into the row contract retained in t3-review-diff/reference. */
internal fun parseReviewDiff(patch: String): ReviewDocument {
    if (patch.isBlank()) return ReviewDocument()
    return try {
        val files = mutableListOf<ReviewFile>()
        var current: PatchFile? = null
        var oldLine = 0
        var newLine = 0
        var oldRemaining = 0
        var newRemaining = 0
        var inHunk = false
        fun finishHunk() {
            check(oldRemaining == 0 && newRemaining == 0)
            inHunk = false
        }
        fun finishFile() {
            finishHunk()
            current?.let { files += it.build(files.size) }
        }
        patch.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> {
                    finishFile()
                    val header = line.removePrefix("diff --git ")
                    val paths = if (header.startsWith('"')) {
                        val end = quotedEnd(header)
                        decodePatchPath(header.substring(0, end)) to decodePatchPath(header.substring(end).trimStart())
                    } else {
                        val split = header.lastIndexOf(" b/")
                        check(split > 0)
                        header.substring(0, split) to decodePatchPath(header.substring(split + 1))
                    }
                    current = PatchFile(paths.first.removePrefix("a/"), paths.second.removePrefix("b/"))
                }
                line.startsWith("diff --") -> error("Unsupported patch format")
                line.startsWith("@@ ") -> {
                    finishHunk()
                    val match = requireNotNull(HunkHeader.find(line))
                    oldLine = match.groupValues[1].toInt()
                    oldRemaining = match.groupValues[2].ifEmpty { "1" }.toInt()
                    newLine = match.groupValues[3].toInt()
                    newRemaining = match.groupValues[4].ifEmpty { "1" }.toInt()
                    requireNotNull(current).rows += ReviewRow("hunk", "", "", text = line)
                    inHunk = true
                }
                inHunk && line.startsWith("\\ No newline at end of file") -> {
                    requireNotNull(current).rows += ReviewRow("notice", "", "", text = "No newline at end of file")
                }
                inHunk && (oldRemaining > 0 || newRemaining > 0) -> {
                    val file = requireNotNull(current)
                    when (line.firstOrNull()) {
                        '+' -> {
                            check(newRemaining-- > 0)
                            file.rows +=
                                ReviewRow(
                                    "line",
                                    "",
                                    "",
                                    content = line.drop(1),
                                    change = "add",
                                    newLineNumber = newLine++
                                )
                        }
                        '-' -> {
                            check(oldRemaining-- > 0)
                            file.rows +=
                                ReviewRow(
                                    "line",
                                    "",
                                    "",
                                    content = line.drop(1),
                                    change = "delete",
                                    oldLineNumber = oldLine++
                                )
                        }
                        ' ' -> {
                            check(oldRemaining-- > 0 && newRemaining-- > 0)
                            file.rows +=
                                ReviewRow(
                                    "line",
                                    "",
                                    "",
                                    content = line.drop(1),
                                    oldLineNumber = oldLine++,
                                    newLineNumber = newLine++
                                )
                        }
                        else -> error("Malformed patch hunk")
                    }
                }
                line.startsWith("--- ") -> {
                    if (current == null || inHunk) {
                        finishFile()
                        current = PatchFile("", "")
                    }
                    current!!.previous = decodePatchPath(line.drop(4)).removePrefix("a/")
                }
                line.startsWith("+++ ") -> requireNotNull(current).path =
                    decodePatchPath(line.drop(4)).removePrefix("b/")
                line.startsWith("rename from ") -> requireNotNull(current).previous = decodePatchPath(line.drop(12))
                line.startsWith("rename to ") -> requireNotNull(current).path = decodePatchPath(line.drop(10))
                line.startsWith("new file mode ") -> requireNotNull(current).type = "added"
                line.startsWith("deleted file mode ") -> requireNotNull(current).type = "deleted"
                line.startsWith("Binary files ") || line == "GIT binary patch" -> {
                    requireNotNull(current).rows +=
                        ReviewRow("notice", "", "", text = "Binary file content is not included in this view.")
                }
                line.startsWith("old mode ") || line.startsWith("new mode ") -> {
                    requireNotNull(current).rows += ReviewRow("notice", "", "", text = line)
                }
            }
        }
        finishFile()
        check(files.isNotEmpty())
        ReviewDocument(files)
    } catch (_: Exception) {
        // Keep unknown/combined or malformed patches available rather than silently losing changes.
        val fileId = "raw-patch"
        val rows = listOf(ReviewRow("file", fileId, fileId, filePath = "Raw patch")) +
            patch.lineSequence().mapIndexed { index, line ->
                ReviewRow("line", "$fileId:$index", fileId, content = line)
            }
        ReviewDocument(listOf(ReviewFile(fileId, "Raw patch", rows)), "This patch format is shown as raw text.")
    }
}

private val HunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

private class PatchFile(var previous: String, var path: String, var type: String = "modified") {
    val rows = mutableListOf<ReviewRow>()
    fun build(index: Int): ReviewFile {
        val actualPath = if (path == "/dev/null") previous else path
        check(actualPath.isNotEmpty())
        val changeType = when {
            path == "/dev/null" -> "deleted"
            previous == "/dev/null" -> "added"
            previous != path -> "renamed"
            else -> type
        }
        val id = "file:$index:$actualPath"
        val header =
            ReviewRow(
                "file",
                id,
                id,
                actualPath,
                previous.takeIf { it != actualPath && it != "/dev/null" },
                changeType,
                rows.count { it.change == "add" },
                rows.count { it.change == "delete" }
            )
        val content = rows.ifEmpty { listOf(ReviewRow("notice", "", "", text = "No textual changes in this file.")) }
        return ReviewFile(
            id,
            actualPath,
            listOf(header) + content.mapIndexed { rowIndex, row -> row.copy(id = "$id:$rowIndex", fileId = id) }
        )
    }
}

private fun quotedEnd(path: String): Int {
    var escaped = false
    for (index in 1 until path.length) {
        if (!escaped && path[index] == '"') return index + 1
        escaped = !escaped && path[index] == '\\'
    }
    error("Unclosed quoted patch path")
}

internal fun decodePatchPath(raw: String): String {
    if (!raw.startsWith('"')) return raw.substringBefore('\t')
    val bytes = raw.substring(1, quotedEnd(raw) - 1).toByteArray(Charsets.UTF_8)
    val output = ByteArrayOutputStream()
    var index = 0
    while (index < bytes.size) {
        val value = bytes[index++].toInt() and 255
        if (value != '\\'.code || index == bytes.size) {
            output.write(value)
        } else {
            val next = bytes[index++].toInt().toChar()
            if (next in '0'..'7') {
                var octal = next.digitToInt()
                repeat(2) {
                    if (index < bytes.size &&
                        bytes[index].toInt().toChar() in '0'..'7'
                    ) {
                        octal =
                            octal * 8 + bytes[index++].toInt().toChar().digitToInt()
                    }
                }
                output.write(octal)
            } else {
                output.write(
                    when (next) {
                        't' -> 9
                        'n' -> 10
                        'r' -> 13
                        'b' -> 8
                        'f' -> 12
                        else -> next.code
                    }
                )
            }
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}
