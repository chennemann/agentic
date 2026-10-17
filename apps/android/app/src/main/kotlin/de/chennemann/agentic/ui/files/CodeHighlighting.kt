package de.chennemann.agentic.ui.files

import de.chennemann.agentic.streamingmarkdown.CodeLexerRegistry
import de.chennemann.agentic.streamingmarkdown.CodeToken

/** Tokenizes [contents] with the lexer matching [path] and splits the result into display lines. */
internal fun highlightCodeLines(
    path: String,
    contents: String,
    registry: CodeLexerRegistry = CodeLexerRegistry.Default,
): List<List<CodeToken>> {
    val source = contents.replace("\r\n", "\n").replace("\t", "    ")
    return codeTokenLines(registry.tokenize(codeLanguageFor(path), source))
}

/** Splits lexer tokens at line breaks, dropping the breaks themselves. */
internal fun codeTokenLines(tokens: List<CodeToken>): List<List<CodeToken>> {
    val lines = mutableListOf(mutableListOf<CodeToken>())
    tokens.forEach { token ->
        token.value.split('\n').forEachIndexed { index, part ->
            if (index > 0) lines += mutableListOf<CodeToken>()
            if (part.isNotEmpty()) lines.last() += CodeToken(part, token.kind)
        }
    }
    return lines
}

internal fun codeLanguageFor(path: String): String? {
    val name = path.substringAfterLast('/').lowercase()
    FileNameLanguages[name]?.let { return it }
    return ExtensionLanguages[name.substringAfterLast('.', "")]
}

private val FileNameLanguages = mapOf(
    "gradlew" to "shell",
    "dockerfile" to "shell",
    "makefile" to "shell",
    ".bashrc" to "shell",
    ".zshrc" to "shell",
    ".gitignore" to "properties",
    ".gitattributes" to "properties",
    ".editorconfig" to "properties",
    ".env" to "properties",
)

private val ExtensionLanguages = buildMap {
    fun language(name: String, vararg extensions: String) = extensions.forEach { put(it, name) }
    language("kotlin", "kt", "kts")
    language("java", "java", "gradle", "groovy")
    language("javascript", "js", "jsx", "mjs", "cjs", "jsonc", "json5")
    language("typescript", "ts", "tsx", "mts", "cts")
    language("json", "json")
    language("python", "py")
    language("shell", "sh", "bash", "zsh")
    language("rust", "rs")
    language("go", "go")
    language("xml", "xml", "plist")
    language("yaml", "yaml", "yml")
    language("toml", "toml")
    language("properties", "properties", "ini", "env")
    language("css", "css", "scss", "less")
    language("sql", "sql", "sq", "sqm")
    language("swift", "swift")
    language("cpp", "c", "h", "cc", "cpp", "cxx", "hh", "hpp", "m", "mm")
    language("csharp", "cs")
}
