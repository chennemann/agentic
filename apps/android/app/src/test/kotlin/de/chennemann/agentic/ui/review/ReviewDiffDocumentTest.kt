package de.chennemann.agentic.ui.review

import de.chennemann.agentic.streamingmarkdown.CodeTokenKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewDiffDocumentTest {
    @Test fun `multiple hunks preserve content and both sides line numbers`() {
        val parsed =
            parseReviewDiff(
                """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1,2 +1,3 @@ fun main()
             context
            -old
            +new
            +more
            @@ -20 +21 @@
            -last
            +end
                """.trimIndent()
            )
        assertNull(parsed.notice)
        assertEquals(3, parsed.additions)
        assertEquals(2, parsed.deletions)
        val lines = parsed.rows.filter { it.kind == "line" }
        assertEquals(listOf("context", "old", "new", "more", "last", "end"), lines.map { it.content })
        assertEquals(listOf(1, 2, null, null, 20, null), lines.map { it.oldLineNumber })
        assertEquals(listOf(1, null, 2, 3, null, 21), lines.map { it.newLineNumber })
        assertEquals(parsed.rows.size, parsed.rows.map { it.id }.distinct().size)
    }

    @Test fun `added deleted renamed binary and empty files remain visible`() {
        val parsed =
            parseReviewDiff(
                """
            diff --git a/added.txt b/added.txt
            new file mode 100644
            --- /dev/null
            +++ b/added.txt
            @@ -0,0 +1 @@
            +new
            \ No newline at end of file
            diff --git a/deleted.txt b/deleted.txt
            deleted file mode 100644
            --- a/deleted.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -old
            diff --git a/old name.txt b/new name.txt
            similarity index 100%
            rename from old name.txt
            rename to new name.txt
            diff --git a/picture.png b/picture.png
            Binary files a/picture.png and b/picture.png differ
            diff --git a/empty.txt b/empty.txt
            new file mode 100644
                """.trimIndent()
            )
        assertNull(parsed.notice)
        assertEquals(
            listOf("added.txt", "deleted.txt", "new name.txt", "picture.png", "empty.txt"),
            parsed.files.map {
                it.path
            }
        )
        assertEquals(
            listOf("added", "deleted", "renamed", "modified", "added"),
            parsed.files.map {
                it.rows.first().changeType
            }
        )
        assertEquals("old name.txt", parsed.files[2].rows.first().previousPath)
        assertTrue(parsed.files[3].rows.any { it.text.contains("Binary") })
        assertTrue(parsed.files[0].rows.any { it.text.contains("No newline") })
    }

    @Test fun `git quoted UTF8 paths tabs and backslashes decode as bytes`() {
        assertEquals("caf\u00e9.kt", decodePatchPath("\"caf\\303\\251.kt\""))
        assertEquals("folder\\file\tname", decodePatchPath("\"folder\\\\file\\tname\""))
        val parsed =
            parseReviewDiff(
                "diff --git \"a/caf\\303\\251.kt\" \"b/caf\\303\\251.kt\"\n--- \"a/caf\\303\\251.kt\"\n+++ \"b/caf\\303\\251.kt\"\n@@ -1 +1 @@\n-old\n+new\n"
            )
        assertEquals("caf\u00e9.kt", parsed.files.single().path)
        assertNull(parsed.notice)
    }

    @Test fun `malformed and combined patches fall back to complete raw text`() {
        for (patch in listOf(
            "not a unified patch",
            "diff --cc file\n@@@ combined",
            "--- a/a\n+++ b/a\n@@ -1,2 +1 @@\n-only one"
        )) {
            val parsed = parseReviewDiff(patch)
            assertNotNull(parsed.notice)
            assertEquals(patch, parsed.rows.filter { it.kind == "line" }.joinToString("\n") { it.content })
        }
        assertTrue(parseReviewDiff("").files.isEmpty())
    }

    @Test fun `native payload keeps Unicode code intact through syntax tokens`() {
        val document =
            parseReviewDiff(
                "--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-val old = 1\n+val text = \"caf\u00e9 \uD83D\uDE00\"\n"
            )
        val colors = CodeTokenKind.entries.associateWith { "#AABBCC" }
        val payload = reviewPayload(document, colors)
        val rows = Json.parseToJsonElement(payload.rows).jsonArray
        val tokens = Json.parseToJsonElement(payload.tokens).jsonObject
        rows.filter { it.jsonObject["kind"]!!.jsonPrimitive.content == "line" }.forEach { element ->
            val row = element.jsonObject
            assertEquals(
                row["content"]!!.jsonPrimitive.content,
                tokens[row["id"]!!.jsonPrimitive.content]!!.jsonArray.joinToString("") {
                    it.jsonObject["content"]!!.jsonPrimitive.content
                }
            )
        }
    }
}
