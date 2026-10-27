package de.chennemann.agentic.streamingmarkdown

/**
 * Renders TeX math as readable Unicode text. This is a fallback for a typesetter: symbols,
 * scripts, fractions and roots read naturally, and anything unknown stays visible as written.
 */
fun latexToUnicode(source: String): String = LatexText(source.trim()).render()
    .lines()
    .joinToString("\n") { it.replace(Regex(" {2,}"), " ").trim() }
    .trim()

/** Finds inline math closed by [delimiter] starting after [start], or null when it does not close. */
internal fun String.inlineMathEnd(start: Int, delimiter: String): Int? {
    if (delimiter == "$") {
        val first = getOrNull(start) ?: return null
        if (first.isWhitespace() || first == '$') return null
        var index = start
        while (index < length) {
            val character = this[index]
            when {
                character == '\n' -> return null
                character == '\\' -> index += 1
                character == '$' -> {
                    val closes = !this[index - 1].isWhitespace() && getOrNull(index + 1)?.isDigit() != true
                    if (closes) return index
                }
            }
            index += 1
        }
        return null
    }
    val end = indexOf(delimiter, start)
    return end.takeIf { it > start && substring(start, it).isNotBlank() }
}

private class LatexText(private val input: String) {
    private var index = 0

    fun render(): String = sequence(stopAtGroupEnd = false)

    private fun sequence(stopAtGroupEnd: Boolean): String = buildString {
        while (index < input.length) {
            val character = input[index]
            if (character == '}') {
                index += 1
                if (stopAtGroupEnd) return@buildString
                continue
            }
            append(token())
        }
    }

    private fun token(): String {
        val character = input[index]
        return when (character) {
            '\\' -> command()
            '{' -> {
                index += 1
                sequence(stopAtGroupEnd = true)
            }
            '^' -> {
                index += 1
                script(argument(), Superscripts, "^")
            }
            '_' -> {
                index += 1
                script(argument(), Subscripts, "_")
            }
            '&' -> {
                index += 1
                " "
            }
            '~' -> {
                index += 1
                " "
            }
            else -> {
                index += 1
                character.toString()
            }
        }
    }

    /** One argument of a command or script: a braced group, a command, or a single character. */
    private fun argument(): String {
        while (index < input.length && input[index] == ' ') index += 1
        if (index >= input.length) return ""
        return when (input[index]) {
            '{' -> {
                index += 1
                sequence(stopAtGroupEnd = true)
            }
            '\\' -> command()
            else -> input[index++].toString()
        }
    }

    private fun optionalArgument(): String? {
        if (input.getOrNull(index) != '[') return null
        val end = input.indexOf(']', index + 1).takeIf { it > 0 } ?: return null
        val value = LatexText(input.substring(index + 1, end)).render()
        index = end + 1
        return value
    }

    private fun command(): String {
        index += 1
        if (index >= input.length) return "\\"
        val first = input[index]
        if (!first.isLetter()) {
            index += 1
            return when (first) {
                '\\' -> "\n"
                ',', ':', ';', ' ' -> " "
                '!' -> ""
                '|' -> "‖"
                else -> first.toString()
            }
        }
        val start = index
        while (index < input.length && input[index].isLetter()) index += 1
        val name = input.substring(start, index)
        Symbols[name]?.let { return it }
        if (name in Functions) return name
        return when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val numerator = argument()
                val denominator = argument()
                "${grouped(numerator)}/${grouped(denominator)}"
            }
            "sqrt" -> {
                val degree = optionalArgument()
                val radicand = argument()
                degree?.let { script(it, Superscripts, "") }.orEmpty() + "√" + grouped(radicand)
            }
            "text", "textrm", "textit", "textbf", "textsf", "texttt", "mathrm", "mathit", "mathbf", "mathsf",
            "mathtt", "mathcal", "mathfrak", "mathscr", "boldsymbol", "bm", "operatorname", "mbox", "hbox",
            -> argument()
            "mathbb" -> argument().map { DoubleStruck[it] ?: it.toString() }.joinToString("")
            "bar", "overline" -> argument().map { "$it\u0304" }.joinToString("")
            "hat", "widehat" -> accent(argument(), '\u0302')
            "tilde", "widetilde" -> accent(argument(), '\u0303')
            "dot" -> accent(argument(), '\u0307')
            "ddot" -> accent(argument(), '\u0308')
            "vec" -> accent(argument(), '\u20D7')
            "left", "right", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "big", "Big", "bigg", "Bigg" -> {
                if (input.getOrNull(index) == '.') index += 1
                ""
            }
            "begin", "end" -> {
                argument()
                ""
            }
            "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "nonumber", "notag" -> ""
            "quad" -> "  "
            "qquad" -> "    "
            "bmod" -> " mod "
            "pmod" -> " (mod ${argument()})"
            else -> "\\$name"
        }
    }

    private fun accent(value: String, mark: Char): String = if (value.length == 1) "$value$mark" else value

    private fun grouped(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length <= 1 || trimmed.all { it.isLetterOrDigit() || it == '.' }) trimmed else "($trimmed)"
    }

    private fun script(value: String, map: Map<Char, Char>, marker: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.all { it == '′' }) return trimmed
        val mapped = trimmed.map { map[it] }
        if (mapped.all { it != null }) return mapped.joinToString("")
        return if (trimmed.length == 1) "$marker$trimmed" else "$marker($trimmed)"
    }
}

private val Symbols = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
    "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ",
    "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
    "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "div" to "÷", "cdot" to "·", "cdotp" to "·", "pm" to "±", "mp" to "∓", "ast" to "∗",
    "star" to "⋆", "circ" to "∘", "bullet" to "∙", "oplus" to "⊕", "otimes" to "⊗",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
    "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
    "supseteq" to "⊇", "mid" to "∣", "parallel" to "∥", "perp" to "⊥",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⟹", "iff" to "⟺",
    "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓", "longrightarrow" to "⟶", "longleftarrow" to "⟵",
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
    "bigcup" to "⋃", "bigcap" to "⋂",
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
    "emptyset" to "∅", "varnothing" to "∅", "neg" to "¬", "lnot" to "¬", "land" to "∧", "wedge" to "∧",
    "lor" to "∨", "vee" to "∨", "cup" to "∪", "cap" to "∩", "setminus" to "∖", "ldots" to "…", "dots" to "…",
    "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱", "prime" to "′", "angle" to "∠", "triangle" to "△",
    "degree" to "°", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
    "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊", "rfloor" to "⌋",
    "backslash" to "\\", "vert" to "|", "Vert" to "‖", "lvert" to "|", "rvert" to "|", "colon" to ":",
    "therefore" to "∴", "because" to "∵", "checkmark" to "✓", "dagger" to "†",
)

private val Functions = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "log", "ln", "lg", "exp", "lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "dim",
    "ker", "deg", "gcd", "arg", "Pr", "mod",
)

private val DoubleStruck = mapOf(
    'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'H' to "ℍ",
)

private val Superscripts = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
    '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'a' to 'ᵃ',
    'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ',
    'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ',
    's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    'T' to 'ᵀ',
)

private val Subscripts = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
    '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ',
    'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ',
    'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
)
