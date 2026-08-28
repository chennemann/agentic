package de.chennemann.agentic.streamingmarkdown

/** Presentation-neutral categories understood by the Compose code renderer. */
enum class CodeTokenKind {
    PLAIN,
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
}

/** A classified source fragment. A lexer's ordered token values must reproduce its input exactly. */
data class CodeToken(
    val value: String,
    val kind: CodeTokenKind,
)

/** Classifies code without depending on Compose, a theme, or markdown parsing. */
fun interface CodeLexer {
    /** Returns ordered tokens whose values reproduce [source] exactly when concatenated. */
    fun tokenize(source: String): List<CodeToken>
}

/** Associates a lexer adapter with the fenced-code language names it accepts. */
data class CodeLexerPlugin(
    val languageAliases: Set<String>,
    val lexer: CodeLexer,
)

/** Routes fenced-code languages to lexer adapters and supplies plain-text fallback. */
class CodeLexerRegistry private constructor(
    private val lexersByLanguage: Map<String, CodeLexer>,
) {
    constructor(plugins: Collection<CodeLexerPlugin>) : this(plugins.toLexerMap())

    fun tokenize(
        language: String?,
        source: String,
    ): List<CodeToken> {
        val lexer = language?.let(::normalizeLanguage)?.let(lexersByLanguage::get)
            ?: return plainText(source)
        val tokens = lexer.tokenize(source)
        require(tokens.reproduce(source)) {
            "The code lexer for '$language' did not reproduce its source exactly"
        }
        return tokens
    }

    /** Returns a registry with [plugins] added, replacing existing adapters for matching aliases. */
    fun withPlugins(vararg plugins: CodeLexerPlugin): CodeLexerRegistry =
        CodeLexerRegistry(lexersByLanguage + plugins.asIterable().toLexerMap())

    companion object {
        val Default = CodeLexerRegistry(defaultCodeLexerPlugins())
    }
}

private fun Iterable<CodeLexerPlugin>.toLexerMap(): Map<String, CodeLexer> = buildMap {
    this@toLexerMap.forEach { plugin ->
        require(plugin.languageAliases.isNotEmpty()) { "A code lexer plugin needs at least one language alias" }
        plugin.languageAliases.forEach { alias ->
            val normalizedAlias = normalizeLanguage(alias)
            require(normalizedAlias.isNotEmpty()) { "Code lexer language aliases must not be blank" }
            require(put(normalizedAlias, plugin.lexer) == null) {
                "More than one code lexer is registered for '$normalizedAlias'"
            }
        }
    }
}

private fun normalizeLanguage(language: String): String =
    language.trim().takeWhile { !it.isWhitespace() }.lowercase()

private fun List<CodeToken>.reproduce(source: String): Boolean {
    var offset = 0
    forEach { token ->
        if (!source.regionMatches(offset, token.value, 0, token.value.length)) return false
        offset += token.value.length
    }
    return offset == source.length
}

private fun plainText(source: String): List<CodeToken> =
    if (source.isEmpty()) emptyList() else listOf(CodeToken(source, CodeTokenKind.PLAIN))
