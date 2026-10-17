package de.chennemann.agentic.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleCodeLexersTest {
    @Test
    fun highlights_kotlin_keywords_strings_numbers_and_comments() {
        val source = "val answer = \"not // a comment\" + 42 // result"

        val tokens = CodeLexerRegistry.Default.tokenize("kotlin", source)

        assertEquals(source, tokens.joinToString(separator = "") { it.value })
        assertTrue(tokens.any { it == CodeToken("val", CodeTokenKind.KEYWORD) })
        assertTrue(tokens.any { it == CodeToken("\"not // a comment\"", CodeTokenKind.STRING) })
        assertTrue(tokens.any { it == CodeToken("42", CodeTokenKind.NUMBER) })
        assertTrue(tokens.any { it == CodeToken("// result", CodeTokenKind.COMMENT) })
    }

    @Test
    fun supports_language_aliases() {
        val tokens = CodeLexerRegistry.Default.tokenize("TS", "const count = 1")

        assertTrue(tokens.any { it == CodeToken("const", CodeTokenKind.KEYWORD) })
    }

    @Test
    fun keeps_multiline_raw_strings_and_ends_unterminated_quotes_at_the_line_break() {
        val raw = "val sql = \"\"\"\nSELECT 1\n\"\"\"\n"
        val prose = "summary: it's fine\nenabled: true"

        val rawTokens = CodeLexerRegistry.Default.tokenize("kotlin", raw)
        val proseTokens = CodeLexerRegistry.Default.tokenize("yaml", prose)

        assertTrue(rawTokens.any { it == CodeToken("\"\"\"\nSELECT 1\n\"\"\"", CodeTokenKind.STRING) })
        assertTrue(proseTokens.any { it == CodeToken("'s fine", CodeTokenKind.STRING) })
        assertTrue(proseTokens.any { it == CodeToken("true", CodeTokenKind.KEYWORD) })
    }

    @Test
    fun highlights_uppercase_sql_keywords_and_comments() {
        val tokens = CodeLexerRegistry.Default.tokenize("sq", "SELECT * FROM thread -- all")

        assertTrue(tokens.any { it == CodeToken("SELECT", CodeTokenKind.KEYWORD) })
        assertTrue(tokens.any { it == CodeToken("-- all", CodeTokenKind.COMMENT) })
    }

    @Test
    fun classifies_kotlin_calls_members_constants_annotations_and_docs() {
        val source = """
            import de.chennemann.agentic.Thing
            /** Docs. */
            @Composable
            fun render(items: List<Item>) = items.map { Item(it.name, MAX_SIZE) }.also(::log)
        """.trimIndent()

        val tokens = CodeLexerRegistry.Default.tokenize("kotlin", source)

        assertEquals(source, tokens.joinToString(separator = "") { it.value })
        assertTrue(tokens.none { it.kind == CodeTokenKind.PROPERTY && it.value in setOf("chennemann", "agentic", "Thing") })
        assertTrue(tokens.any { it == CodeToken("/** Docs. */", CodeTokenKind.DOC_COMMENT) })
        assertTrue(tokens.any { it == CodeToken("@Composable", CodeTokenKind.ANNOTATION) })
        assertTrue(tokens.any { it == CodeToken("render", CodeTokenKind.FUNCTION) })
        assertTrue(tokens.any { it == CodeToken("map", CodeTokenKind.FUNCTION) })
        assertTrue(tokens.any { it == CodeToken("also", CodeTokenKind.FUNCTION) })
        assertTrue(tokens.any { it == CodeToken("name", CodeTokenKind.PROPERTY) })
        assertTrue(tokens.any { it == CodeToken("MAX_SIZE", CodeTokenKind.CONSTANT) })
        assertTrue(tokens.none { it.value == "Item" && it.kind != CodeTokenKind.PLAIN })
    }

    @Test
    fun splits_string_templates_and_escapes() {
        val source = "\"Hi \$name, \${user.count + 1}\\n\""

        val tokens = CodeLexerRegistry.Default.tokenize("kotlin", source)

        assertEquals(
            listOf(
                CodeToken("\"Hi ", CodeTokenKind.STRING),
                CodeToken("$", CodeTokenKind.KEYWORD),
                CodeToken("name", CodeTokenKind.PLAIN),
                CodeToken(", ", CodeTokenKind.STRING),
                CodeToken("\${", CodeTokenKind.KEYWORD),
                CodeToken("user.", CodeTokenKind.PLAIN),
                CodeToken("count", CodeTokenKind.PROPERTY),
                CodeToken(" + ", CodeTokenKind.PLAIN),
                CodeToken("1", CodeTokenKind.NUMBER),
                CodeToken("}\\n", CodeTokenKind.KEYWORD),
                CodeToken("\"", CodeTokenKind.STRING),
            ),
            tokens,
        )
    }

    @Test
    fun highlights_markup_tags_attributes_and_comments() {
        val source = "<!-- note -->\n<item name=\"x\">Don't &amp; go</item>"

        val tokens = CodeLexerRegistry.Default.tokenize("xml", source)

        assertEquals(source, tokens.joinToString(separator = "") { it.value })
        assertTrue(tokens.any { it == CodeToken("<!-- note -->", CodeTokenKind.COMMENT) })
        assertTrue(tokens.count { it == CodeToken("item", CodeTokenKind.TAG) } == 2)
        assertTrue(tokens.any { it == CodeToken("name", CodeTokenKind.ATTRIBUTE) })
        assertTrue(tokens.any { it == CodeToken("\"x\"", CodeTokenKind.STRING) })
        assertTrue(tokens.any { it == CodeToken("&amp;", CodeTokenKind.KEYWORD) })
        assertTrue(tokens.none { it.kind == CodeTokenKind.STRING && it.value.startsWith("'") })
    }

    @Test
    fun marks_configuration_keys_sections_and_shell_variables() {
        val yaml = CodeLexerRegistry.Default.tokenize("yaml", "jobs:\n  - name: build\n    \"on\": push")
        val toml = CodeLexerRegistry.Default.tokenize("toml", "[versions]\nagp = \"9.1.1\"")
        val shell = CodeLexerRegistry.Default.tokenize("shell", "echo \$HOME \${PATH} \"\$USER\"")
        val json = CodeLexerRegistry.Default.tokenize("json", "{\"url\": \"x\"}")

        assertTrue(yaml.any { it == CodeToken("jobs", CodeTokenKind.PROPERTY) })
        assertTrue(yaml.any { it == CodeToken("name", CodeTokenKind.PROPERTY) })
        assertTrue(yaml.any { it == CodeToken("\"on\"", CodeTokenKind.PROPERTY) })
        assertTrue(toml.any { it == CodeToken("[versions]", CodeTokenKind.KEYWORD) })
        assertTrue(toml.any { it == CodeToken("agp", CodeTokenKind.PROPERTY) })
        assertTrue(shell.any { it == CodeToken("\$HOME", CodeTokenKind.PROPERTY) })
        assertTrue(shell.any { it == CodeToken("\${PATH}", CodeTokenKind.PROPERTY) })
        assertTrue(shell.any { it == CodeToken("USER", CodeTokenKind.PLAIN) })
        assertTrue(json.any { it == CodeToken("\"url\"", CodeTokenKind.PROPERTY) })
    }

    @Test
    fun treats_json_comment_markers_as_plain_text() {
        val source = "{\"url\": \"https://example.com\"}"

        val tokens = CodeLexerRegistry.Default.tokenize("json", source)

        assertEquals(source, tokens.joinToString(separator = "") { it.value })
        assertTrue(tokens.none { it.kind == CodeTokenKind.COMMENT })
    }
}
