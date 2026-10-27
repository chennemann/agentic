package de.chennemann.agentic.streamingmarkdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownMathTest {
    @Test
    fun renders_common_tex_as_unicode() {
        assertEquals("E = mc²", latexToUnicode("E = mc^2"))
        assertEquals("α + β ≤ γ", latexToUnicode("\\alpha + \\beta \\leq \\gamma"))
        assertEquals("(a+b)/2", latexToUnicode("\\frac{a+b}{2}"))
        assertEquals("x/y", latexToUnicode("\\frac x y"))
        assertEquals("√(x+1)", latexToUnicode("\\sqrt{x+1}"))
        assertEquals("³√x", latexToUnicode("\\sqrt[3]{x}"))
        assertEquals("xᵢ₊₁", latexToUnicode("x_{i+1}"))
        assertEquals("x²ʸ", latexToUnicode("x^{2y}"))
        assertEquals("x^(2Q)", latexToUnicode("x^{2Q}"))
        assertEquals("∑ᵢ₌₁ⁿ i", latexToUnicode("\\sum_{i=1}^{n} i"))
        assertEquals("f′(x)", latexToUnicode("f^\\prime(x)"))
        assertEquals("x ∈ ℝ", latexToUnicode("x \\in \\mathbb{R}"))
        assertEquals("{1, 2}", latexToUnicode("\\{1, 2\\}"))
        assertEquals("speed (m/s)", latexToUnicode("\\text{speed} \\left(m/s\\right)"))
        assertEquals("a = 1\nb = 2", latexToUnicode("\\begin{aligned} a &= 1 \\\\ b &= 2 \\end{aligned}"))
        assertEquals("\\unknown x", latexToUnicode("\\unknown x"))
        assertEquals("x^Q", latexToUnicode("x^Q"))
    }

    @Test
    fun inline_math_becomes_math_runs_but_prices_stay_text() {
        val runs = parseMarkdown("Energy \$E = mc^2\$ costs \$5 or \$10 and \\(\\pi r^2\\).")
        val decorated = toAnnotatedString(runs).text
        assertEquals("Energy E = mc² costs \$5 or \$10 and π r².", decorated)
        assertEquals("cash \$5-\$10", toAnnotatedString(parseMarkdown("cash \$5-\$10")).text)
        assertEquals("not \$ math\$", toAnnotatedString(parseMarkdown("not \$ math\$")).text)
        assertEquals("escaped \$x\$", toAnnotatedString(parseMarkdown("escaped \\\$x\\\$")).text)
        assertEquals("code \$x\$", toAnnotatedString(parseMarkdown("code `\$x\$`")).text)
    }

    @Test
    fun display_math_parses_as_its_own_block() {
        val blocks = parseMarkdownBlocks("Before\n\n\$\$\n\\int_0^1 x\\,dx\n\$\$\n\nAfter \$\$x\$\$ inline")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Math("\\int_0^1 x\\,dx"), blocks[1])
        assertEquals(MarkdownBlock.Math("a^2", closed = true), parseMarkdownBlocks("\$\$a^2\$\$").single())
        assertEquals(MarkdownBlock.Math("x", closed = false), parseMarkdownBlocks("\\[\nx").single())
        assertTrue(parseMarkdownBlocks("\$\$100 off today").single() is MarkdownBlock.Paragraph)
        assertTrue(hasMarkdownBlockSyntax("Look:\n\$\$\nx\n\$\$"))
    }
}
