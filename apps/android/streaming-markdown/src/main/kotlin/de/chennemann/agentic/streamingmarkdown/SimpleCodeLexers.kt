package de.chennemann.agentic.streamingmarkdown

private data class LanguageDefinition(
    val aliases: Set<String>,
    val keywords: Set<String>,
    val lineCommentPrefixes: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val stringDelimiters: Set<Char> = setOf('"', '\''),
    /** Recognizes calls, member access, constants and annotations of general-purpose languages. */
    val code: Boolean = false,
    /** Capitalized identifiers name types, so `Foo(` is a constructor call rather than a function. */
    val capitalizedTypes: Boolean = false,
    /** An identifier followed by `{` is a call with a trailing lambda, as in Kotlin's `items.map { }`. */
    val trailingLambdas: Boolean = false,
    val annotations: Boolean = code,
    val escapes: Boolean = code,
    /** String delimiters whose contents may embed `${expression}` templates. */
    val templateDelimiters: Set<Char> = emptySet(),
    /** Whether templates may also be written as `$name`. */
    val identifierTemplates: Boolean = false,
    val dollarVariables: Boolean = false,
    /** Strings followed by `:` are object keys. */
    val stringKeys: Boolean = false,
    /** Bare words at the start of a line followed by one of these characters are keys. */
    val keySeparators: Set<Char> = emptySet(),
    /** `[section]` lines are headers. */
    val sectionHeaders: Boolean = false,
    /** Keywords that start a path whose segments are not member accesses. */
    val importKeywords: Set<String> = emptySet(),
)

private class SimpleCodeLexer(
    private val definition: LanguageDefinition,
) : CodeLexer {
    override fun tokenize(source: String): List<CodeToken> = mutableListOf<CodeToken>().also { tokenizeInto(source, it) }

    private fun tokenizeInto(source: String, tokens: MutableList<CodeToken>) {
        var index = 0
        var inImport = false
        while (index < source.length) {
            val character = source[index]
            if (character == '\n') inImport = false

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
                val doc = blockComment.first == "/*" && source.startsWith("/**", index) && !source.startsWith("/**/", index)
                tokens.append(source.substring(index, end), if (doc) CodeTokenKind.DOC_COMMENT else CodeTokenKind.COMMENT)
                index = end
                continue
            }

            if (definition.sectionHeaders && character == '[' && atLineStart(source, index)) {
                val lineEnd = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                val close = source.lastIndexOf(']', lineEnd - 1)
                if (close > index) {
                    tokens.append(source.substring(index, close + 1), CodeTokenKind.KEYWORD)
                    index = close + 1
                    continue
                }
            }

            if (character in definition.stringDelimiters) {
                val end = stringEnd(source, index, character)
                if (definition.stringKeys && source.isFollowedBy(end, setOf(':'))) {
                    tokens.append(source.substring(index, end), CodeTokenKind.PROPERTY)
                } else {
                    appendString(source, index, end, character, tokens)
                }
                index = end
                continue
            }

            if (definition.keySeparators.isNotEmpty() && character.isKeyPart() && atLineStart(source, index)) {
                val dotted = '=' in definition.keySeparators
                var end = index
                while (end < source.length && (source[end].isKeyPart() || (dotted && source[end] == '.'))) end += 1
                if (source.isFollowedBy(end, definition.keySeparators)) {
                    tokens.append(source.substring(index, end), CodeTokenKind.PROPERTY)
                    index = end
                    continue
                }
            }

            if (definition.annotations && character == '@' && source.getOrNull(index + 1)?.isLetter() == true) {
                var end = index + 1
                while (end < source.length) {
                    val part = source[end]
                    if (!part.isIdentifierPart() && part != '.' && (definition.code || part != '-')) break
                    end += 1
                }
                tokens.append(source.substring(index, end), CodeTokenKind.ANNOTATION)
                index = end
                continue
            }

            if (definition.dollarVariables && character == '$') {
                val end = shellVariableEnd(source, index)
                if (end > index + 1) {
                    tokens.append(source.substring(index, end), CodeTokenKind.PROPERTY)
                    index = end
                    continue
                }
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
                tokens.append(value, identifierKind(source, index, end, value, inImport))
                if (value in definition.importKeywords) inImport = true
                index = end
                continue
            }

            tokens.append(character.toString(), CodeTokenKind.PLAIN)
            index += 1
        }
    }

    private fun identifierKind(
        source: String,
        start: Int,
        end: Int,
        value: String,
        inImport: Boolean,
    ): CodeTokenKind {
        val member = source.getOrNull(start - 1) == '.'
        if (value in definition.keywords && !member) return CodeTokenKind.KEYWORD
        if (!definition.code) return CodeTokenKind.PLAIN
        val next = source.getOrNull(end)
        val call = next == '(' ||
            (next == '!' && source.getOrNull(end + 1) in MacroOpeners) ||
            (definition.trailingLambdas && source.isFollowedBy(end, setOf('{')))
        val type = definition.capitalizedTypes && value.first().isUpperCase()
        return when {
            call -> if (type) CodeTokenKind.PLAIN else CodeTokenKind.FUNCTION
            isConstantName(value) -> CodeTokenKind.CONSTANT
            member && !inImport && !type -> CodeTokenKind.PROPERTY
            else -> CodeTokenKind.PLAIN
        }
    }

    /** Emits a string, splitting out escape sequences and embedded templates. */
    private fun appendString(
        source: String,
        start: Int,
        end: Int,
        delimiter: Char,
        tokens: MutableList<CodeToken>,
    ) {
        val triple = source.startsWith(delimiter.toString().repeat(3), start)
        val templates = delimiter in definition.templateDelimiters
        val escapes = definition.escapes && !triple
        var segment = start
        var index = start + 1
        fun flush(to: Int) {
            tokens.append(source.substring(segment, to), CodeTokenKind.STRING)
        }
        while (index < end && (templates || escapes)) {
            val character = source[index]
            if (escapes && character == '\\' && index + 1 < end) {
                var escapeEnd = index + 2
                if (source[index + 1] == 'u') {
                    while (escapeEnd < end && escapeEnd < index + 6 && source[escapeEnd].isHexDigit()) escapeEnd += 1
                }
                flush(index)
                tokens.append(source.substring(index, escapeEnd), CodeTokenKind.KEYWORD)
                index = escapeEnd
                segment = index
                continue
            }
            if (templates && character == '$') {
                val close = if (source.getOrNull(index + 1) == '{') matchingBrace(source, index + 1, end) else -1
                if (close > 0) {
                    flush(index)
                    tokens.append("\${", CodeTokenKind.KEYWORD)
                    tokenizeInto(source.substring(index + 2, close), tokens)
                    tokens.append("}", CodeTokenKind.KEYWORD)
                    index = close + 1
                    segment = index
                    continue
                }
                val nameStart = source.getOrNull(index + 1)
                if (definition.identifierTemplates && nameStart != null && (nameStart.isLetter() || nameStart == '_')) {
                    var nameEnd = index + 1
                    while (nameEnd < end && (source[nameEnd].isLetterOrDigit() || source[nameEnd] == '_')) nameEnd += 1
                    flush(index)
                    tokens.append("$", CodeTokenKind.KEYWORD)
                    tokens.append(source.substring(index + 1, nameEnd), CodeTokenKind.PLAIN)
                    index = nameEnd
                    segment = index
                    continue
                }
            }
            index += 1
        }
        flush(end)
    }
}

/** Tags, attributes, comments, CDATA and entities of XML-like markup. */
private object MarkupLexer : CodeLexer {
    override fun tokenize(source: String): List<CodeToken> {
        val tokens = mutableListOf<CodeToken>()
        var index = 0
        while (index < source.length) {
            val character = source[index]
            val next = source.getOrNull(index + 1)
            when {
                source.startsWith("<!--", index) -> {
                    val end = source.indexOf("-->", index + 4).let { if (it < 0) source.length else it + 3 }
                    tokens.append(source.substring(index, end), CodeTokenKind.COMMENT)
                    index = end
                }
                source.startsWith("<![CDATA[", index) -> {
                    val end = source.indexOf("]]>", index).let { if (it < 0) source.length else it + 3 }
                    tokens.append(source.substring(index, end), CodeTokenKind.STRING)
                    index = end
                }
                character == '<' && next != null && (next.isLetter() || next in "/?!") -> index = tag(source, index, tokens)
                character == '&' && isEntity(source, index) -> {
                    val end = source.indexOf(';', index) + 1
                    tokens.append(source.substring(index, end), CodeTokenKind.KEYWORD)
                    index = end
                }
                else -> {
                    var end = index + 1
                    while (end < source.length && source[end] != '<' && source[end] != '&') end += 1
                    tokens.append(source.substring(index, end), CodeTokenKind.PLAIN)
                    index = end
                }
            }
        }
        return tokens
    }

    private fun tag(source: String, start: Int, tokens: MutableList<CodeToken>): Int {
        var index = start + 1
        if (source[index] in "/?!") index += 1
        tokens.append(source.substring(start, index), CodeTokenKind.PLAIN)
        val nameEnd = nameEnd(source, index)
        tokens.append(source.substring(index, nameEnd), CodeTokenKind.TAG)
        index = nameEnd
        while (index < source.length) {
            val character = source[index]
            when {
                character == '>' -> {
                    tokens.append(">", CodeTokenKind.PLAIN)
                    return index + 1
                }
                character == '<' -> return index
                character == '"' || character == '\'' -> {
                    val close = source.indexOf(character, index + 1)
                    val end = if (close >= 0) close + 1 else source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    tokens.append(source.substring(index, end), CodeTokenKind.STRING)
                    index = end
                }
                character.isLetter() || character == '_' || character == ':' -> {
                    val end = nameEnd(source, index)
                    tokens.append(source.substring(index, end), CodeTokenKind.ATTRIBUTE)
                    index = end
                }
                else -> {
                    tokens.append(character.toString(), CodeTokenKind.PLAIN)
                    index += 1
                }
            }
        }
        return index
    }

    private fun nameEnd(source: String, start: Int): Int {
        var index = start
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] in "_:.-")) index += 1
        return index
    }

    private fun isEntity(source: String, start: Int): Boolean {
        val end = source.indexOf(';', start)
        return end in start + 2..start + 10 && (start + 1 until end).all { source[it].isLetterOrDigit() || source[it] == '#' }
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

/**
 * Triple-quoted and backtick strings may span lines; other unterminated strings end at the line break so a
 * stray apostrophe in prose does not color the rest of the file.
 */
private fun stringEnd(
    source: String,
    start: Int,
    delimiter: Char,
): Int {
    val triple = delimiter.toString().repeat(3)
    if (source.startsWith(triple, start)) {
        val closing = source.indexOf(triple, start + triple.length)
        return if (closing < 0) source.length else closing + triple.length
    }
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
        } else if (character == '\n' && delimiter != '`') {
            return index
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

private fun matchingBrace(source: String, open: Int, limit: Int): Int {
    var depth = 0
    for (index in open until limit) {
        when (source[index]) {
            '{' -> depth += 1
            '}' -> if (--depth == 0) return index
        }
    }
    return -1
}

private fun shellVariableEnd(source: String, start: Int): Int {
    val next = source.getOrNull(start + 1) ?: return start
    return when {
        next == '{' -> {
            val close = source.indexOf('}', start + 2)
            val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            if (close in 0 until lineEnd) close + 1 else start
        }
        next.isLetter() || next == '_' -> {
            var index = start + 1
            while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index += 1
            index
        }
        next.isDigit() || next in "@*#?$!-" -> start + 2
        else -> start
    }
}

/** Whether only spaces, tabs and list dashes precede [index] on its line. */
private fun atLineStart(source: String, index: Int): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && source[cursor] in " \t-") cursor -= 1
    return cursor < 0 || source[cursor] == '\n'
}

private fun String.isFollowedBy(index: Int, characters: Set<Char>): Boolean {
    var cursor = index
    while (cursor < length && (this[cursor] == ' ' || this[cursor] == '\t')) cursor += 1
    return cursor < length && this[cursor] in characters
}

private fun isConstantName(value: String): Boolean =
    value.length >= 2 && value.first().isUpperCase() && value.any { it.isLetter() } &&
        value.all { it.isUpperCase() || it.isDigit() || it == '_' }

private val MacroOpeners = setOf('(', '[', '{')

private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

private fun Char.isKeyPart(): Boolean = isLetterOrDigit() || this == '_' || this == '-'

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_' || this == '$'

private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isDigit()

internal fun defaultCodeLexerPlugins(): List<CodeLexerPlugin> = listOf(
    LanguageDefinition(
        aliases = setOf("kotlin", "kt", "kts"),
        keywords = """
            as break class continue do else false for fun if in interface is null object package return super this
            throw true try typealias typeof val var when while by catch constructor finally import init where actual
            abstract annotation companion const crossinline data enum expect external final infix inline inner internal
            lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        code = true,
        capitalizedTypes = true,
        trailingLambdas = true,
        templateDelimiters = setOf('"'),
        identifierTemplates = true,
        importKeywords = setOf("import", "package"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("java"),
        keywords = """
            abstract assert boolean break byte case catch char class const continue default do double else enum
            extends final finally float for goto if implements import instanceof int interface long native new package
            private protected public return short static strictfp super switch synchronized this throw throws transient
            true false null try void volatile while record sealed permits non-sealed var yield def
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        code = true,
        capitalizedTypes = true,
        importKeywords = setOf("import", "package"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("javascript", "js", "jsx", "typescript", "ts", "tsx"),
        keywords = """
            async await break case catch class const continue debugger default delete do else export extends false
            finally for function if import in instanceof let new null of return static super switch this throw true try
            typeof var void while with yield interface implements namespace private protected public readonly type
            as enum abstract declare keyof satisfies undefined infer asserts override from any boolean never number
            string symbol unknown bigint
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        stringDelimiters = setOf('"', '\'', '`'),
        code = true,
        capitalizedTypes = true,
        templateDelimiters = setOf('`'),
        importKeywords = setOf("import"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("json"),
        keywords = setOf("true", "false", "null"),
        stringDelimiters = setOf('"'),
        escapes = true,
        stringKeys = true,
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("python", "py"),
        keywords = """
            and as assert async await break class continue def del elif else except False finally for from global if
            import in is lambda None nonlocal not or pass raise return True try while with yield match case self
        """.keywords(),
        lineCommentPrefixes = listOf("#"),
        code = true,
        capitalizedTypes = true,
        importKeywords = setOf("import", "from"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("shell", "bash", "sh", "zsh"),
        keywords = """
            case do done elif else esac fi for function if in select then time until while coproc false true export
            local return readonly
        """.keywords(),
        lineCommentPrefixes = listOf("#"),
        stringDelimiters = setOf('"', '\'', '`'),
        escapes = true,
        templateDelimiters = setOf('"'),
        identifierTemplates = true,
        dollarVariables = true,
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("rust", "rs"),
        keywords = """
            as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod
            move mut pub ref return self Self static struct super trait true type unsafe use where while abstract become
            box do final macro override priv typeof unsized virtual yield try
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        code = true,
        capitalizedTypes = true,
        importKeywords = setOf("use", "mod"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("go", "golang"),
        keywords = """
            break default func interface select case defer go map struct chan else goto package switch const
            fallthrough if range type continue for import return var true false nil
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        stringDelimiters = setOf('"', '\'', '`'),
        code = true,
        importKeywords = setOf("package", "import"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("swift"),
        keywords = """
            associatedtype class deinit enum extension fileprivate func import init inout internal let open operator
            private protocol public rethrows static struct subscript typealias var break case continue default defer
            do else fallthrough for guard if in repeat return switch where while as Any catch false is nil super self
            Self throw throws true try async await actor some any final lazy mutating override required weak
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        code = true,
        capitalizedTypes = true,
        importKeywords = setOf("import"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("cpp", "c++", "c", "h", "hpp", "objc"),
        keywords = """
            auto break case char const continue default do double else enum extern float for goto if inline int long
            register restrict return short signed sizeof static struct switch typedef union unsigned void volatile
            while bool class constexpr delete explicit false friend mutable namespace new noexcept nullptr operator
            private protected public template this throw true try typename using virtual override final
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        code = true,
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("csharp", "cs", "c#"),
        keywords = """
            abstract as async await base bool break byte case catch char checked class const continue decimal default
            delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit
            in int interface internal is lock long namespace new null object operator out override params private
            protected public readonly record ref return sealed short sizeof static string struct switch this throw
            true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile while yield
        """.keywords(),
        lineCommentPrefixes = listOf("//"),
        blockComment = "/*" to "*/",
        code = true,
        importKeywords = setOf("using", "namespace"),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("sql", "sq", "sqm"),
        keywords = """
            select from where insert into values update set delete create table index view trigger drop alter add
            primary key foreign references unique not null default and or in is like between join left right inner
            outer cross on as order by group having limit offset union all distinct case when then else end exists
            integer text real blob boolean begin commit rollback transaction if replace returning with
        """.keywords().let { keywords -> keywords + keywords.map { it.uppercase() } },
        lineCommentPrefixes = listOf("--"),
        blockComment = "/*" to "*/",
    ).plugin(),
    CodeLexerPlugin(setOf("xml", "html", "svg", "plist"), MarkupLexer),
    LanguageDefinition(
        aliases = setOf("yaml", "yml"),
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        lineCommentPrefixes = listOf("#"),
        stringKeys = true,
        keySeparators = setOf(':'),
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("toml"),
        keywords = setOf("true", "false"),
        lineCommentPrefixes = listOf("#"),
        escapes = true,
        keySeparators = setOf('='),
        sectionHeaders = true,
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("properties", "ini", "env"),
        keywords = emptySet(),
        lineCommentPrefixes = listOf("#"),
        stringDelimiters = emptySet(),
        keySeparators = setOf('=', ':'),
        sectionHeaders = true,
    ).plugin(),
    LanguageDefinition(
        aliases = setOf("css", "scss", "less"),
        keywords = setOf("important", "inherit", "initial", "unset", "none", "auto"),
        blockComment = "/*" to "*/",
        annotations = true,
        keySeparators = setOf(':'),
    ).plugin(),
)

private fun LanguageDefinition.plugin() = CodeLexerPlugin(aliases, SimpleCodeLexer(this))

private fun String.keywords(): Set<String> = trimIndent().split(Regex("\\s+")).toSet()
