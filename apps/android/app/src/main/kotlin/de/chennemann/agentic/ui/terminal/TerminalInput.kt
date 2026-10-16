package de.chennemann.agentic.ui.terminal

// Port of apps/mobile/src/features/terminal/terminalInput.ts at t3-terminal/upstream.json's revision.
internal fun applyTerminalCtrlModifier(input: String): String {
    val first = input.firstOrNull() ?: return input
    val lower = first.lowercaseChar()
    return when {
        lower in 'a'..'z' -> (lower.code - 96).toChar().toString()
        first == '@' -> "\u0000"
        first == '[' -> "\u001b"
        first == '\\' -> "\u001c"
        first == ']' -> "\u001d"
        first == '^' -> "\u001e"
        first == '_' -> "\u001f"
        first == '?' -> "\u007f"
        else -> input
    }
}

/** Upstream mobile uses unbracketed paste: strip unsafe controls and send CR for newlines. */
internal fun encodeTerminalPaste(text: String): String = text.replace(
    Regex("[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]"),
    " "
).replace("\r\n", "\r").replace('\n', '\r')
