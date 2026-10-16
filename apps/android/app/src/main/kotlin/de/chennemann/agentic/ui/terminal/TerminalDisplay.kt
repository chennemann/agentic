package de.chennemann.agentic.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import expo.modules.t3terminal.GhosttySession
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retained screen state consumes each output chunk once, including while off screen. */
interface TerminalDisplay {
    suspend fun append(data: String)
    suspend fun clear()
    fun detach()
}

fun interface TerminalDisplayFactory {
    suspend fun create(
        history: String,
        cols: Int,
        rows: Int,
        onInput: (String) -> Unit,
        onResize: (Int, Int) -> Unit
    ): TerminalDisplay
}

class GhosttyTerminalDisplayFactory : TerminalDisplayFactory {
    override suspend fun create(
        history: String,
        cols: Int,
        rows: Int,
        onInput: (String) -> Unit,
        onResize: (Int, Int) -> Unit
    ): TerminalDisplay = GhosttyTerminalDisplay(cols, rows, onInput, onResize).also {
        try {
            // Replay updates screen state but must not answer historical device queries.
            withContext(Dispatchers.Default) { it.session.feed(history) }
        } catch (cause: Exception) {
            it.detach()
            throw cause
        }
    }
}

class GhosttyTerminalDisplay(
    cols: Int,
    rows: Int,
    private var onInput: ((String) -> Unit)?,
    private var onResize: ((Int, Int) -> Unit)?
) : TerminalDisplay {
    val session = GhosttySession(cols, rows)
    private val inputDecoder = TerminalInputDecoder()
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    override suspend fun append(data: String) {
        val response = withContext(Dispatchers.Default) { session.feed(data) }
        withContext(Dispatchers.Main.immediate) { reply(response) }
    }

    override suspend fun clear() = append("\u001b[2J\u001b[3J\u001b[H")

    fun reply(bytes: ByteArray) {
        if (bytes.isNotEmpty()) inputDecoder.decode(bytes).takeIf(String::isNotEmpty)?.let { onInput?.invoke(it) }
    }

    fun input(data: String) {
        var encoded = if (ctrl) applyTerminalCtrlModifier(data) else data
        if (alt) encoded = "\u001b$encoded"
        ctrl = false
        alt = false
        onInput?.invoke(encoded)
    }

    fun paste(text: String) {
        onInput?.invoke(encodeTerminalPaste(text))
    }
    fun resize(cols: Int, rows: Int) {
        onResize?.invoke(cols, rows)
    }

    override fun detach() {
        onInput = null
        onResize = null
        session.close()
    }
}

/** Native paste callbacks may divide a UTF-8 code point between batches. */
internal class TerminalInputDecoder {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(
        CodingErrorAction.REPLACE
    ).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = byteArrayOf()
    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining())
        decoder.decode(input, output, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }
}
