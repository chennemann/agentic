package de.chennemann.agentic.ui.terminal

import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalEmulatorFactory

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

class ConnectBotTerminalDisplayFactory : TerminalDisplayFactory {
    override suspend fun create(
        history: String,
        cols: Int,
        rows: Int,
        onInput: (String) -> Unit,
        onResize: (Int, Int) -> Unit
    ): TerminalDisplay = ConnectBotTerminalDisplay(cols, rows, onInput, onResize).also {
        try {
            it.append(history)
            it.finishReplay()
        } catch (cause: Exception) {
            it.detach()
            throw cause
        }
    }
}

class ConnectBotTerminalDisplay(
    cols: Int,
    rows: Int,
    private var onInput: ((String) -> Unit)?,
    private var onResize: ((Int, Int) -> Unit)?
) : TerminalDisplay {
    private var replaying = true
    private val inputDecoder = TerminalInputDecoder()
    val emulator = TerminalEmulatorFactory.create(
        initialCols = cols,
        initialRows = rows,
        onKeyboardInput = { bytes ->
            if (!replaying) inputDecoder.decode(bytes).takeIf(String::isNotEmpty)?.let { onInput?.invoke(it) }
        },
        onResize = { size -> onResize?.invoke(size.columns, size.rows) }
    )

    // Parsing is synchronous and thread safe; large output bursts must not block rendering.
    override suspend fun append(data: String) = withContext(Dispatchers.Default) {
        emulator.writeInput(data.toByteArray(Charsets.UTF_8))
    }

    override suspend fun clear() = append("\u001b[2J\u001b[3J\u001b[H")

    fun finishReplay() {
        // Drain historical device-query replies before allowing replies to the live program.
        Handler(Looper.getMainLooper()).post { replaying = false }
    }

    override fun detach() {
        onInput = null
        onResize = null
        emulator.close()
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
