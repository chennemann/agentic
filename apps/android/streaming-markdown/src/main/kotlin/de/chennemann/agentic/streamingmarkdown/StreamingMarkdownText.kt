package de.chennemann.agentic.streamingmarkdown

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun StreamingMarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    streaming: Boolean = true,
    inlineCode: SpanStyle = StreamingMarkdownTextDefaults.inlineCodeStyle(),
    emphasis: SpanStyle = StreamingMarkdownTextDefaults.emphasisStyle(),
    strong: SpanStyle = StreamingMarkdownTextDefaults.strongStyle(),
    link: SpanStyle = StreamingMarkdownTextDefaults.linkStyle(),
    blockCode: SpanStyle = StreamingMarkdownTextDefaults.blockCodeStyle(),
    codeLexerRegistry: CodeLexerRegistry = CodeLexerRegistry.Default,
    onCopyCode: ((String) -> Unit)? = null,
) {
    val markdownInlineCode = StreamingMarkdownTextDefaults.inlineCodeStyle().merge(inlineCode)
    val markdownEmphasis = StreamingMarkdownTextDefaults.emphasisStyle().merge(emphasis)
    val markdownStrong = StreamingMarkdownTextDefaults.strongStyle().merge(strong)
    val markdownLink = StreamingMarkdownTextDefaults.linkStyle().merge(link)
    val markdownBlockCode = StreamingMarkdownTextDefaults.blockCodeStyle().merge(blockCode)

    val model = remember(streaming) { StreamingMarkdownState(streaming) }
    val blocks = remember(content, model) {
        if (hasMarkdownBlockSyntax(content)) {
            parseMarkdownBlocks(content)
        } else {
            listOf(MarkdownBlock.Paragraph(model.update(content)))
        }
    }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copyCode = onCopyCode ?: { value: String ->
        clipboard.setPrimaryClip(ClipData.newPlainText("Code", value))
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownRunsText(
                    runs = block.runs,
                    style = style,
                    color = color,
                    maxLines = maxLines,
                    overflow = overflow,
                    inlineCode = markdownInlineCode,
                    emphasis = markdownEmphasis,
                    strong = markdownStrong,
                    link = markdownLink,
                    blockCode = markdownBlockCode,
                )
                is MarkdownBlock.Code -> MarkdownCodeBlock(
                    block = block,
                    style = style,
                    codeLexerRegistry = codeLexerRegistry,
                    onCopy = copyCode,
                )
                is MarkdownBlock.ListBlock -> MarkdownList(
                    block = block,
                    style = style,
                    color = color,
                    inlineCode = markdownInlineCode,
                    emphasis = markdownEmphasis,
                    strong = markdownStrong,
                    link = markdownLink,
                    blockCode = markdownBlockCode,
                )
                is MarkdownBlock.Table -> MarkdownTable(
                    block = block,
                    style = style,
                    color = color,
                    inlineCode = markdownInlineCode,
                    emphasis = markdownEmphasis,
                    strong = markdownStrong,
                    link = markdownLink,
                    blockCode = markdownBlockCode,
                )
            }
        }
    }
}

@Composable
private fun MarkdownRunsText(
    runs: List<MarkdownRun>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineCode: SpanStyle,
    emphasis: SpanStyle,
    strong: SpanStyle,
    link: SpanStyle,
    blockCode: SpanStyle,
) {
    val text = remember(runs, inlineCode, emphasis, strong, link, blockCode) {
        toAnnotatedString(runs, inlineCode, emphasis, strong, link, blockCode)
    }
    Text(text, modifier, color, style = style, maxLines = maxLines, overflow = overflow)
}

@Composable
private fun MarkdownCodeBlock(
    block: MarkdownBlock.Code,
    style: TextStyle,
    codeLexerRegistry: CodeLexerRegistry,
    onCopy: (String) -> Unit,
) {
    val keywordStyle = SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
    val stringStyle = SpanStyle(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f))
    val numberStyle = SpanStyle(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.78f))
    val commentStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f),
        fontStyle = FontStyle.Italic,
    )
    val highlightedCode = remember(
        block.content,
        block.language,
        codeLexerRegistry,
        keywordStyle,
        stringStyle,
        numberStyle,
        commentStyle,
    ) {
        codeTokensToAnnotatedString(
            tokens = codeLexerRegistry.tokenize(block.language, block.content),
            keywordStyle = keywordStyle,
            stringStyle = stringStyle,
            numberStyle = numberStyle,
            commentStyle = commentStyle,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = block.language?.uppercase() ?: "TEXT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { onCopy(block.content) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = LucideCopyIcon,
                    contentDescription = "Copy code",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = highlightedCode,
                style = style.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

internal fun codeTokensToAnnotatedString(
    tokens: List<CodeToken>,
    keywordStyle: SpanStyle,
    stringStyle: SpanStyle,
    numberStyle: SpanStyle,
    commentStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    tokens.forEach { token ->
        val tokenStyle = when (token.kind) {
            CodeTokenKind.PLAIN -> null
            CodeTokenKind.KEYWORD -> keywordStyle
            CodeTokenKind.STRING -> stringStyle
            CodeTokenKind.NUMBER -> numberStyle
            CodeTokenKind.COMMENT -> commentStyle
        }
        if (tokenStyle == null) {
            append(token.value)
        } else {
            pushStyle(tokenStyle)
            append(token.value)
            pop()
        }
    }
}

@Composable
private fun MarkdownList(
    block: MarkdownBlock.ListBlock,
    style: TextStyle,
    color: Color,
    inlineCode: SpanStyle,
    emphasis: SpanStyle,
    strong: SpanStyle,
    link: SpanStyle,
    blockCode: SpanStyle,
) {
    Column(
        modifier = Modifier.padding(start = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        block.items.forEach { item ->
            Row(modifier = Modifier.padding(start = (item.level * 18).dp)) {
                Text(
                    text = item.marker,
                    modifier = Modifier.width(if (block.ordered) 32.dp else 22.dp),
                    style = style,
                    color = color,
                )
                MarkdownRunsText(
                    runs = item.runs,
                    modifier = Modifier.weight(1f),
                    style = style,
                    color = color,
                    inlineCode = inlineCode,
                    emphasis = emphasis,
                    strong = strong,
                    link = link,
                    blockCode = blockCode,
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    block: MarkdownBlock.Table,
    style: TextStyle,
    color: Color,
    inlineCode: SpanStyle,
    emphasis: SpanStyle,
    strong: SpanStyle,
    link: SpanStyle,
    blockCode: SpanStyle,
) {
    val widths = remember(block) { tableColumnWidths(block) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            MarkdownTableRow(
                cells = block.header,
                widths = widths,
                header = true,
                style = style,
                color = color,
                inlineCode = inlineCode,
                emphasis = emphasis,
                strong = strong,
                link = link,
                blockCode = blockCode,
            )
            block.rows.forEach { row ->
                MarkdownTableRow(
                    cells = row,
                    widths = widths,
                    header = false,
                    style = style,
                    color = color,
                    inlineCode = inlineCode,
                    emphasis = emphasis,
                    strong = strong,
                    link = link,
                    blockCode = blockCode,
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<List<MarkdownRun>>,
    widths: List<Dp>,
    header: Boolean,
    style: TextStyle,
    color: Color,
    inlineCode: SpanStyle,
    emphasis: SpanStyle,
    strong: SpanStyle,
    link: SpanStyle,
    blockCode: SpanStyle,
) {
    Row {
        cells.forEachIndexed { index, runs ->
            Box(
                modifier = Modifier
                    .width(widths.getOrElse(index) { 120.dp })
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                MarkdownRunsText(
                    runs = runs,
                    style = if (header) style.copy(fontWeight = FontWeight.Bold) else style,
                    color = color,
                    inlineCode = inlineCode,
                    emphasis = emphasis,
                    strong = strong,
                    link = link,
                    blockCode = blockCode,
                )
            }
        }
    }
}

private fun tableColumnWidths(block: MarkdownBlock.Table): List<Dp> = block.header.indices.map { column ->
    val cells = listOf(block.header) + block.rows
    val length = cells.maxOfOrNull { row -> row.getOrNull(column)?.sumOf { it.value.length } ?: 0 } ?: 0
    (length * 8 + 24).coerceIn(88, 240).dp
}

object StreamingMarkdownTextDefaults {
    @Composable
    fun inlineCodeStyle() = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    )

    @Composable
    fun emphasisStyle() = SpanStyle(
        fontStyle = FontStyle.Italic,
    )

    @Composable
    fun strongStyle() = SpanStyle(
        fontWeight = FontWeight.Bold,
    )

    @Composable
    fun linkStyle() = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )

    @Composable
    fun blockCodeStyle() = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    )
}

fun toAnnotatedString(
    runs: List<MarkdownRun>,
    inlineCode: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color(0x1A7A7A7A),
    ),
    emphasis: SpanStyle = SpanStyle(
        fontStyle = FontStyle.Italic,
    ),
    strong: SpanStyle = SpanStyle(
        fontWeight = FontWeight.Bold,
    ),
    link: SpanStyle = SpanStyle(
        color = Color(0xFF1565C0),
        textDecoration = TextDecoration.Underline,
    ),
    blockCode: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color(0x1A7A7A7A),
    ),
): AnnotatedString = buildAnnotatedString {
    linkify(decorate(runs)).forEach { run ->
        val start = length
        if (run.kind == MarkdownKind.TEXT) {
            append(run.value)
            return@forEach
        }
        if (run.kind == MarkdownKind.INLINE_CODE) {
            pushStyle(inlineCode)
            append(run.value)
            pop()
            return@forEach
        }
        if (run.kind == MarkdownKind.EMPHASIS) {
            pushStyle(emphasis)
            append(run.value)
            pop()
            return@forEach
        }
        if (run.kind == MarkdownKind.STRONG) {
            pushStyle(strong)
            append(run.value)
            pop()
            return@forEach
        }
        if (run.kind == MarkdownKind.LINK) {
            pushStyle(link)
            append(run.value)
            pop()
            val href = run.href
            if (href != null) {
                addStringAnnotation(
                    tag = "URL",
                    annotation = href,
                    start = start,
                    end = length,
                )
                if (href.isExternalLinkDestination()) {
                    addLink(
                        url = LinkAnnotation.Url(href),
                        start = start,
                        end = length,
                    )
                }
            }
            return@forEach
        }
        if (run.kind == MarkdownKind.BLOCK_CODE) {
            pushStyle(blockCode)
            append(run.value)
            pop()
            return@forEach
        }
        pushStyle(link)
        append(run.value)
        pop()
    }
}

private fun decorate(runs: List<MarkdownRun>): List<MarkdownRun> {
    val out = mutableListOf<MarkdownRun>()
    runs.forEach { run ->
        if (run.kind != MarkdownKind.TEXT) {
            append(out, run.kind, run.value)
            return@forEach
        }
        var emphasis = false
        var strong = false
        var slash = false
        var index = 0
        while (index < run.value.length) {
            val char = run.value[index]
            if (slash) {
                append(out, kind(emphasis, strong), char.toString())
                slash = false
                index += 1
                continue
            }
            if (char == '\\') {
                slash = true
                index += 1
                continue
            }
            if (char == '*') {
                val pair = index + 1 < run.value.length && run.value[index + 1] == '*'
                if (pair) {
                    strong = !strong
                    index += 2
                    continue
                }
                emphasis = !emphasis
                index += 1
                continue
            }
            append(out, kind(emphasis, strong), char.toString())
            index += 1
        }
        if (slash) {
            append(out, kind(emphasis, strong), "\\")
        }
    }
    return out
}

private fun kind(emphasis: Boolean, strong: Boolean): MarkdownKind {
    if (strong) return MarkdownKind.STRONG
    if (emphasis) return MarkdownKind.EMPHASIS
    return MarkdownKind.TEXT
}

private fun append(
    runs: MutableList<MarkdownRun>,
    kind: MarkdownKind,
    value: String,
    href: String? = null,
) {
    if (value.isEmpty()) return
    val run = runs.lastOrNull()
    if (run == null) {
        runs += MarkdownRun(kind, value, href)
        return
    }
    if (run.kind != kind || run.href != href) {
        runs += MarkdownRun(kind, value, href)
        return
    }
    runs[runs.lastIndex] = run.copy(value = run.value + value)
}

private fun linkify(runs: List<MarkdownRun>): List<MarkdownRun> {
    val out = mutableListOf<MarkdownRun>()
    runs.forEach { run ->
        if (run.kind != MarkdownKind.TEXT) {
            append(out, run.kind, run.value, run.href)
            return@forEach
        }
        val plain = StringBuilder()
        var index = 0
        fun flush() {
            if (plain.isEmpty()) return
            append(out, MarkdownKind.TEXT, plain.toString())
            plain.clear()
        }
        while (index < run.value.length) {
            if (run.value[index] == '[') {
                val parsed = run.value.parseMarkdownLink(index)
                if (parsed != null) {
                    flush()
                    append(out, MarkdownKind.LINK, parsed.label, parsed.href)
                    index = parsed.endExclusive
                    continue
                }
            }
            val http = run.value.startsWith("http://", index) || run.value.startsWith("https://", index)
            if (http) {
                var end = index
                while (end < run.value.length && !run.value[end].isWhitespace()) {
                    end += 1
                }
                val href = run.value.substring(index, end)
                flush()
                append(out, MarkdownKind.LINK, href, href)
                index = end
                continue
            }
            plain.append(run.value[index])
            index += 1
        }
        flush()
    }
    return out
}

private data class ParsedMarkdownLink(
    val label: String,
    val href: String,
    val endExclusive: Int,
)

private fun String.parseMarkdownLink(start: Int): ParsedMarkdownLink? {
    val labelEnd = indexOf(']', start + 1).takeIf { it > start + 1 } ?: return null
    val destinationStart = labelEnd + 2
    if (getOrNull(labelEnd + 1) != '(' || destinationStart >= length) return null

    val destination: String
    val closingParenthesis: Int
    if (this[destinationStart] == '<') {
        val destinationEnd = indexOf('>', destinationStart + 1).takeIf { it > destinationStart + 1 } ?: return null
        closingParenthesis = destinationEnd + 1
        if (getOrNull(closingParenthesis) != ')') return null
        destination = substring(destinationStart + 1, destinationEnd)
    } else {
        closingParenthesis = findMarkdownLinkClosingParenthesis(destinationStart) ?: return null
        destination = substring(destinationStart, closingParenthesis)
    }

    val href = destination.unescapeMarkdownLinkDestination().takeIf(String::isSafeLinkDestination) ?: return null
    return ParsedMarkdownLink(
        label = substring(start + 1, labelEnd),
        href = href,
        endExclusive = closingParenthesis + 1,
    )
}

private fun String.findMarkdownLinkClosingParenthesis(start: Int): Int? {
    var escaped = false
    var nested = 0
    for (index in start until length) {
        val character = this[index]
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '(' -> nested += 1
            character == ')' && nested > 0 -> nested -= 1
            character == ')' -> return index
            character.isWhitespace() -> return null
        }
    }
    return null
}

private fun String.unescapeMarkdownLinkDestination(): String =
    replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")

private fun String.isSafeLinkDestination(): Boolean =
    isNotBlank() &&
        !startsWith("javascript:", ignoreCase = true) &&
        !startsWith("data:", ignoreCase = true)

private fun String.isExternalLinkDestination(): Boolean =
    startsWith("https://", ignoreCase = true) ||
        startsWith("http://", ignoreCase = true) ||
        startsWith("mailto:", ignoreCase = true) ||
        startsWith("tel:", ignoreCase = true)

class StreamingMarkdownState(private val streaming: Boolean) {
    private val parser = StreamingMarkdownParser()
    private var previous = ""
    private var runs = emptyList<MarkdownRun>()

    fun update(content: String): List<MarkdownRun> {
        if (!streaming) {
            runs = parseMarkdownDocument(content)
            previous = content
            return runs
        }
        if (content == previous) return runs
        if (content.contains("```")) {
            runs = parseMarkdownDocument(content)
            previous = content
            return runs
        }
        if (content.startsWith(previous)) {
            parser.write(content.substring(previous.length))
            previous = content
            runs = parser.snapshot()
            return runs
        }
        parser.start()
        parser.write(content)
        previous = content
        runs = parser.snapshot()
        return runs
    }
}
