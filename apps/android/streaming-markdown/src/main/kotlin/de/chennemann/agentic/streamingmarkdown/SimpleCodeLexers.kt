package de.chennemann.agentic.streamingmarkdown

private data class LanguageDefinition(
    val aliases: Set<String>,
    val keywords: Set<String>,
    val lineCommentPrefixes: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val stringDelimiters: Set<Char> = setOf('"', '\''),
)

private class SimpleCodeLexer(
    private val definition: LanguageDefinition,
) : CodeLexer {
    override fun tokenize(source: String): List<CodeToken> {
        val tokens = mutableListOf<CodeToken>()
        var index = 0
        while (index < source.length) {
            val lineComment = definition.lineCommentPrefixes.firstOrNull { source.startsWith(it, index) }
            if (lineComment != null) {
                val end = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                tokens.append(source.substring(index, end), CodeTokenKind.COMMENT)
                index = end
                continue
            }

            val blockComment = definition.blockComment
            if (blockComment != null && source.startsWith(blockComment.first, index)) {
                val closingIndex = source.indexOf(blockComment.second, index + blockComment.first.length)
                val end = if (closingIndex < 0) source.length else closingIndex + blockComment.second.length
                tokens.append(source.substring(index, end), CodeTokenKind.COMMENT)
                index = end
                continue
            }

            val character = source[index]
            if (character in definition.stringDelimiters) {
                val end = stringEnd(source, index, character)
                tokens.append(source.substring(index, end), CodeTokenKind.STRING)
                index = end
                continue
            }

            if (character.isDigit()) {
                val end = numberEnd(source, index)
                tokens.append(source.substring(index, end), CodeTokenKind.NUMBER)
                index = end
                continue
            }

            if (character.isIdentifierStart()) {
                val end = identifierEnd(source, index)
                val value = source.substring(index, end)
                val kind = if (value in definition.keywords) CodeTokenKind.KEYWORD else CodeTokenKind.PLAIN
                tokens.append(value, kind)
                index = end
                continue
            }

            tokens.append(character.toString(), CodeTokenKind.PLAIN)
            index += 1
        }
        return tokens
    }
}

private fun MutableList<CodeToken>.append(
    value: String,
    kind: CodeTokenKind,
) {
    if (value.isEmpty()) return
    val previous = lastOrNull()
    if (previous?.kind == kind) {
        this[lastIndex] = previous.copy(value = previous.value + value)
    } else {
        add(CodeToken(value, kind))
    }
}

private fun stringEnd(
    source: String,
    start: Int,
    delimiter: Char,
): Int {
    var index = start + 1
    var escaped = false
    while (index < source.length) {
        val character = source[index]
        if (escaped) {
            escaped = false
        } else if (character == '\\') {
            escaped = true
        } else if (character == delimiter) {
            return index + 1
        }
        index += 1
    }
    return source.length
}

private fun numberEnd(
    source: String,
    start: Int,
): Int {
    var index = start + 1
    while (index < source.length) {
        val character = source[index]
        if (!character.isLetterOrDigit() && character != '_' && character != '.') break
        index += 1
    }
    return index
}

private fun identifierEnd(
    source: String,
    start: Int,
): Int {
    var index = start + 1
    while (index < source.length && source[index].isIdentifierPart()) index += 1
    return index
}

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_' || this == '$'

private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isDigit()

internal fun defaultCodeLexerPlugins(): List<CodeLexerPlugin> = listOf(
    definition(
        aliases = setOf("kotlin", "kt", "kts"),
        keywords = """
            as break class continue do else false for fun if in interface is null object package return super this
            throw true try typealias typeof val var when while by catch constructor delegate dynamic field file finally
            get import init param property receiver set setparam where actual abstract annotation companion const
            crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out
            override private protected public reified sealed suspend tailrec vararg
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
    ),
    definition(
        aliases = setOf("java"),
        keywords = """
            abstract assert boolean break byte case catch char class const continue default do double else enum
            extends final finally float for goto if implements import instanceof int interface long native new package
            private protected public return short static strictfp super switch synchronized this throw throws transient
            true false null try void volatile while record sealed permits non-sealed var yield
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
    ),
    definition(
        aliases = setOf("javascript", "js", "jsx", "typescript", "ts", "tsx"),
        keywords = """
            async await break case catch class const continue debugger default delete do else export extends false
            finally for function if import in instanceof let new null of return static super switch this throw true try
            typeof var void while with yield interface implements namespace private protected public readonly type
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        stringDelimiters = setOf('"', '\'', '`'),
    ),
    definition(
        aliases = setOf("json"),
        keywords = setOf("true", "false", "null"),
        stringDelimiters = setOf('"'),
    ),
    definition(
        aliases = setOf("python", "py"),
        keywords = """
            and as assert async await break class continue def del elif else except False finally for from global if
            import in is lambda None nonlocal not or pass raise return True try while with yield match case
        """.keywords(),
        lineCommentPrefixes = listOf("#"),
    ),
    definition(
        aliases = setOf("shell", "bash", "sh", "zsh"),
        keywords = """
            case do done elif else esac fi for function if in select then time until while coproc false true
        """.keywords(),
        lineCommentPrefixes = listOf("#"),
        stringDelimiters = setOf('"', '\'', '`'),
    ),
    definition(
        aliases = setOf("rust", "rs"),
        keywords = """
            as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod
            move mut pub ref return self Self static struct super trait true type unsafe use where while abstract become
            box do final macro override priv typeof unsized virtual yield try
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
    ),
    definition(
        aliases = setOf("go", "golang"),
        keywords = """
            break default func interface select case defer go map struct chan else goto package switch const
            fallthrough if range type continue for import return var true false nil
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
    ),
)

private fun definition(
    aliases: Set<String>,
    keywords: Set<String>,
    lineCommentPrefixes: List<String> = emptyList(),
    blockComment: Pair<String, String>? = null,
    stringDelimiters: Set<Char> = setOf('"', '\''),
): CodeLexerPlugin {
    val definition = LanguageDefinition(
        aliases = aliases,
        keywords = keywords,
        lineCommentPrefixes = lineCommentPrefixes,
        blockComment = blockComment,
        stringDelimiters = stringDelimiters,
    )
    return CodeLexerPlugin(definition.aliases, SimpleCodeLexer(definition))
}

private fun String.keywords(): Set<String> = trimIndent().split(Regex("\\s+")).toSet()
