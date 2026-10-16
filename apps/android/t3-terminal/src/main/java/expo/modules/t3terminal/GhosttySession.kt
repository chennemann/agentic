package expo.modules.t3terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity-independent native state. Every JNI access shares this lifetime lock. */
class GhosttySession(cols: Int, rows: Int) : AutoCloseable {
    private val lock = Any()
    private var nativeHandle = GhosttyBridge.nativeCreate(
        cols,
        rows,
        1,
        1,
        0xFFD1D5DA.toInt(),
        0xFF24292E.toInt(),
        0xFF009FFF.toInt(),
        intArrayOf()
    ).also { check(it != 0L) { "Could not create terminal" } }
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    internal val handle: Long get() = synchronized(lock) { nativeHandle }

    internal fun <T> withHandle(block: (Long) -> T): T = synchronized(lock) { block(nativeHandle) }

    /** Feed bytes once; the owner decides whether replies belong to live output or replay. */
    fun feed(data: String): ByteArray = synchronized(lock) {
        if (nativeHandle == 0L) return@synchronized byteArrayOf()
        GhosttyBridge.nativeFeed(nativeHandle, data.toByteArray(Charsets.UTF_8)).also {
            mutableRevision.value++
        }
    }

    internal fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int): ByteArray = synchronized(lock) {
        if (nativeHandle == 0L) return@synchronized byteArrayOf()
        GhosttyBridge.nativeResize(nativeHandle, cols, rows, cellWidth, cellHeight)
    }

    fun visibleText(): String = synchronized(lock) {
        if (nativeHandle == 0L) return@synchronized ""
        val frame = TerminalFrame.decode(GhosttyBridge.nativeSnapshot(nativeHandle)) ?: return@synchronized ""
        (0 until frame.rows).joinToString("\n") { row ->
            (0 until frame.cols).joinToString("") { col -> frame.cellText[row * frame.cols + col] }.trimEnd()
        }
    }

    override fun close() = synchronized(lock) {
        if (nativeHandle != 0L) {
            GhosttyBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
}
