package de.chennemann.agentic.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CodeLexerRegistryTest {
    @Test
    fun routes_normalized_aliases_to_plugin_lexer() {
        val registry = CodeLexerRegistry(
            listOf(
                CodeLexerPlugin(setOf("demo", "dm")) { source ->
                    listOf(CodeToken(source, CodeTokenKind.KEYWORD))
                },
            ),
        )

        val tokens = registry.tokenize(" DM ", "example")

        assertEquals(listOf(CodeToken("example", CodeTokenKind.KEYWORD)), tokens)
    }

    @Test
    fun extends_default_registry_and_can_replace_a_language_adapter() {
        val plugin = CodeLexerPlugin(setOf("demo", "kotlin")) { source ->
            listOf(CodeToken(source, CodeTokenKind.COMMENT))
        }

        val registry = CodeLexerRegistry.Default.withPlugins(plugin)

        assertEquals(CodeTokenKind.COMMENT, registry.tokenize("demo", "example").single().kind)
        assertEquals(CodeTokenKind.COMMENT, registry.tokenize("kotlin", "example").single().kind)
        assertEquals(CodeTokenKind.KEYWORD, registry.tokenize("java", "class").single().kind)
    }

    @Test
    fun reads_only_the_language_name_from_a_fence_info_string() {
        val tokens = CodeLexerRegistry.Default.tokenize("kotlin title=sample", "val")

        assertEquals(CodeTokenKind.KEYWORD, tokens.single().kind)
    }

    @Test
    fun falls_back_to_plain_text_for_unknown_or_missing_languages() {
        val registry = CodeLexerRegistry(emptyList())
        val expected = listOf(CodeToken("example", CodeTokenKind.PLAIN))

        assertEquals(expected, registry.tokenize("unknown", "example"))
        assertEquals(expected, registry.tokenize(null, "example"))
    }

    @Test
    fun rejects_ambiguous_language_aliases() {
        val lexer = CodeLexer { source -> listOf(CodeToken(source, CodeTokenKind.PLAIN)) }

        assertThrows(IllegalArgumentException::class.java) {
            CodeLexerRegistry(
                listOf(
                    CodeLexerPlugin(setOf("demo"), lexer),
                    CodeLexerPlugin(setOf("DEMO"), lexer),
                ),
            )
        }
    }

    @Test
    fun rejects_plugins_that_do_not_preserve_the_source() {
        val registry = CodeLexerRegistry(
            listOf(
                CodeLexerPlugin(setOf("broken")) {
                    listOf(CodeToken("different", CodeTokenKind.PLAIN))
                },
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.tokenize("broken", "source")
        }
    }
}
