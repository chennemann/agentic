package de.chennemann.agentic.ui.code

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import de.chennemann.agentic.streamingmarkdown.CodeTokenKind

/** How one token kind is painted. Plain tokens have no style and use the surrounding text color. */
data class CodeSyntaxStyle(val color: Color, val italic: Boolean = false) {
    fun toSpanStyle() = SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null)
}

/** IntelliJ's default Dark and Light editor schemes, so code reads the same as in the IDE. */
fun codeSyntaxStyles(dark: Boolean): Map<CodeTokenKind, CodeSyntaxStyle> = if (dark) {
    mapOf(
        CodeTokenKind.KEYWORD to CodeSyntaxStyle(Color(0xFFCF8E6D)),
        CodeTokenKind.STRING to CodeSyntaxStyle(Color(0xFF6AAB73)),
        CodeTokenKind.NUMBER to CodeSyntaxStyle(Color(0xFF2AACB8)),
        CodeTokenKind.COMMENT to CodeSyntaxStyle(Color(0xFF7A7E85), italic = true),
        CodeTokenKind.DOC_COMMENT to CodeSyntaxStyle(Color(0xFF5F826B), italic = true),
        CodeTokenKind.FUNCTION to CodeSyntaxStyle(Color(0xFF56A8F5)),
        CodeTokenKind.PROPERTY to CodeSyntaxStyle(Color(0xFFC77DBB)),
        CodeTokenKind.CONSTANT to CodeSyntaxStyle(Color(0xFFC77DBB), italic = true),
        CodeTokenKind.ANNOTATION to CodeSyntaxStyle(Color(0xFFB3AE60)),
        CodeTokenKind.TAG to CodeSyntaxStyle(Color(0xFFD5B778)),
        CodeTokenKind.ATTRIBUTE to CodeSyntaxStyle(Color(0xFFBABABA)),
    )
} else {
    mapOf(
        CodeTokenKind.KEYWORD to CodeSyntaxStyle(Color(0xFF0033B3)),
        CodeTokenKind.STRING to CodeSyntaxStyle(Color(0xFF067D17)),
        CodeTokenKind.NUMBER to CodeSyntaxStyle(Color(0xFF1750EB)),
        CodeTokenKind.COMMENT to CodeSyntaxStyle(Color(0xFF8C8C8C), italic = true),
        CodeTokenKind.DOC_COMMENT to CodeSyntaxStyle(Color(0xFF8C8C8C), italic = true),
        CodeTokenKind.FUNCTION to CodeSyntaxStyle(Color(0xFF00627A)),
        CodeTokenKind.PROPERTY to CodeSyntaxStyle(Color(0xFF871094)),
        CodeTokenKind.CONSTANT to CodeSyntaxStyle(Color(0xFF871094), italic = true),
        CodeTokenKind.ANNOTATION to CodeSyntaxStyle(Color(0xFF9E880D)),
        CodeTokenKind.TAG to CodeSyntaxStyle(Color(0xFF0033B3)),
        CodeTokenKind.ATTRIBUTE to CodeSyntaxStyle(Color(0xFF174AD4)),
    )
}

@Composable
fun rememberCodeSyntaxStyles(): Map<CodeTokenKind, CodeSyntaxStyle> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) { codeSyntaxStyles(dark) }
}
