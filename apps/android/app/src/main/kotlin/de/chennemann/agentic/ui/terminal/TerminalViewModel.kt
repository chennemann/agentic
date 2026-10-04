package de.chennemann.agentic.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.TerminalContext
import de.chennemann.agentic.domain.orchestration.TerminalSessions
import de.chennemann.agentic.domain.orchestration.nextTerminalId
import de.chennemann.agentic.domain.orchestration.preferredTerminal
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import de.chennemann.agentic.t3.contract.TerminalMetadataEvent
import de.chennemann.agentic.t3.contract.TerminalSessionSnapshot
import de.chennemann.agentic.t3.contract.TerminalSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TerminalTab(val id: String, val label: String)

data class TerminalUiState(
    val terminals: List<TerminalTab> = emptyList(),
    val selectedId: String? = null,
    val display: TerminalDisplay? = null,
    val status: String = "Connecting",
    val canInput: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null
)

class TerminalViewModel(private val sessions: TerminalSessions, private val displays: TerminalDisplayFactory) :
    ViewModel() {
    private val mutableState = MutableStateFlow(TerminalUiState())
    val state = mutableState.asStateFlow()
    private var context: TerminalContext? = null
    private var threadId: String? = null
    private var inventoryJob: Job? = null
    private var attachJob: Job? = null
    private var operationJob: Job? = null
    private var pendingScript: ProjectScript? = null
    private var inventory = emptyList<TerminalSummary>()
    private var generation = 0L
    private var screenGeneration = 0L
    private var sequence: Long? = null
    private var cols = 80
    private var rows = 24
    private val input = Channel<Input>(64)
    private val resizeRequests = Channel<Resize>(Channel.CONFLATED)
    private data class Resize(
        val generation: Long,
        val context: TerminalContext,
        val id: String,
        val cols: Int,
        val rows: Int
    )
    private data class Input(val generation: Long, val context: TerminalContext, val id: String, val data: String)

    init {
        // Never cancel an in-flight resize: a later request must reach the server after it finishes.
        viewModelScope.launch {
            for (request in resizeRequests) {
                delay(75)
                var latest = request
                while (true) latest = resizeRequests.tryReceive().getOrNull() ?: break
                if (latest.generation != generation || !mutableState.value.canInput) continue
                try {
                    sessions.resize(latest.context, latest.id, latest.cols, latest.rows)
                } catch (cause: Exception) {
                    if (cause is CancellationException) throw cause
                    if (latest.generation ==
                        generation
                    ) {
                        fail(cause, "Could not resize the terminal. Reconnect to retry.")
                    }
                }
            }
        }
        // One writer preserves keyboard and paste ordering across suspending RPC calls.
        viewModelScope.launch {
            for (item in input) {
                if (item.generation != generation || !mutableState.value.canInput) continue
                try {
                    sessions.write(item.context, item.id, item.data)
                } catch (cause: Exception) {
                    if (cause is CancellationException) throw cause
                    if (item.generation == generation) {
                        mutableState.value =
                            mutableState.value.copy(
                                canInput = false,
                                errorMessage = "Input could not be delivered. Reconnect before typing again."
                            )
                    }
                }
            }
        }
    }

    fun load(threadId: String, script: ProjectScript? = null) {
        if (this.threadId == threadId) return
        this.threadId = threadId
        pendingScript = script
        inventoryJob?.cancel()
        operationJob?.cancel()
        clearSelection()
        context = null
        inventory = emptyList()
        mutableState.value = TerminalUiState()
        connectInventory(threadId)
    }

    private fun connectInventory(id: String, reconnectSelected: String? = null) {
        inventoryJob?.cancel()
        inventoryJob = viewModelScope.launch {
            try {
                val loaded = sessions.context(id)
                context = loaded
                reconnectSelected?.let { select(it) }
                sessions.inventory(loaded).collect { event ->
                    val current = inventory.associateBy { it.terminalId }.toMutableMap()
                    when (event) {
                        is TerminalMetadataEvent.Snapshot -> {
                            current.clear()
                            event.terminals.filter { it.threadId == loaded.threadId }.forEach {
                                current[it.terminalId] =
                                    it
                            }
                        }
                        is TerminalMetadataEvent.Upsert -> if (event.terminal.threadId ==
                            loaded.threadId
                        ) {
                            current[event.terminal.terminalId] = event.terminal
                        }
                        is TerminalMetadataEvent.Remove -> if (event.threadId ==
                            loaded.threadId
                        ) {
                            current.remove(event.terminalId)
                        }
                    }
                    inventory =
                        current.values.sortedBy {
                            it.terminalId.substringAfter("term-", "").toIntOrNull()
                                ?: Int.MAX_VALUE
                        }
                    mutableState.value =
                        mutableState.value.copy(
                            terminals = inventory.map {
                                TerminalTab(it.terminalId, it.label.ifBlank { it.terminalId })
                            }
                        )
                    val script = pendingScript
                    if (script != null) {
                        pendingScript = null
                        operation {
                            val (terminalId, snapshot) = sessions.launchScript(loaded, inventory, script)
                            select(terminalId, snapshot)
                        }
                    } else if (!mutableState.value.busy) {
                        val selected = mutableState.value.selectedId
                        if (selected == null || selected !in current) {
                            val preferred = preferredTerminal(inventory)
                            if (preferred != null) {
                                select(preferred)
                            } else {
                                clearSelection()
                                mutableState.value = mutableState.value.copy(status = "No sessions")
                            }
                        }
                    }
                }
            } catch (cause: Exception) {
                fail(cause, "Session list disconnected. Reconnect to refresh it.")
            }
        }
    }

    fun reconnect() {
        val id = threadId ?: return
        val selected = mutableState.value.selectedId
        attachJob?.cancel()
        mutableState.value = mutableState.value.copy(canInput = false, errorMessage = null, status = "Connecting")
        connectInventory(id, selected)
    }

    fun create() {
        val loaded = context ?: return
        operation {
            val id = nextTerminalId(inventory)
            select(id, sessions.open(loaded, id))
        }
    }

    fun select(terminalId: String, initial: TerminalSessionSnapshot? = null) {
        val loaded = context ?: return
        clearSelection()
        val selectedGeneration = generation
        mutableState.value =
            mutableState.value.copy(selectedId = terminalId, status = "Connecting", errorMessage = null)
        attachJob = viewModelScope.launch {
            try {
                if (initial != null) applySnapshot(initial)
                sessions.attach(loaded, terminalId).collect { event ->
                    if (selectedGeneration == generation) applyEvent(event, loaded.threadId, terminalId)
                }
                if (selectedGeneration == generation && mutableState.value.canInput) {
                    mutableState.value =
                        mutableState.value.copy(
                            canInput = false,
                            status = "Disconnected",
                            errorMessage = "Terminal stream ended. Reconnect to resume."
                        )
                }
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                if (selectedGeneration == generation) {
                    mutableState.value = mutableState.value.copy(canInput = false, status = "Disconnected")
                    fail(cause, "Terminal disconnected. Reconnect to resume.")
                }
            }
        }
    }

    fun send(data: String) {
        val loaded = context ?: return
        val state = mutableState.value
        val id = state.selectedId ?: return
        if (!state.canInput || data.isEmpty()) return
        // Server limit: 65,536 UTF-16 code units. Keep chunks smaller and preserve surrogate pairs.
        var offset = 0
        while (offset < data.length) {
            var end = (offset + 16 * 1024).coerceAtMost(data.length)
            if (end < data.length && data[end - 1].isHighSurrogate()) end--
            if (!input.trySend(Input(generation, loaded, id, data.substring(offset, end))).isSuccess) {
                mutableState.value =
                    state.copy(
                        canInput = false,
                        errorMessage = "Terminal input is backed up. Reconnect before typing again."
                    )
                return
            }
            offset = end
        }
    }

    private fun resize(columns: Int, lines: Int) {
        cols = columns.coerceIn(1, 1000)
        rows = lines.coerceIn(1, 500)
        val loaded = context ?: return
        val id = mutableState.value.selectedId ?: return
        resizeRequests.trySend(Resize(generation, loaded, id, cols, rows))
    }

    fun clear() = mutate { loaded, id -> sessions.clear(loaded, id) }
    fun restart() = mutate { loaded, id ->
        // Attach events are authoritative; an RPC response must not replace newer streamed output.
        val snapshot = sessions.restart(loaded, id)
        if (attachJob?.isActive != true) select(id, snapshot)
    }
    fun close() = mutate { loaded, id ->
        sessions.close(loaded, id)
        if (mutableState.value.selectedId == id) {
            inventory = inventory.filterNot { it.terminalId == id }
            clearSelection()
            mutableState.value = mutableState.value.copy(
                terminals = inventory.map { TerminalTab(it.terminalId, it.label.ifBlank { it.terminalId }) },
                status = "No sessions"
            )
            preferredTerminal(inventory)?.let { select(it) }
        }
    }

    private fun mutate(block: suspend (TerminalContext, String) -> Unit) {
        val loaded = context ?: return
        val id = mutableState.value.selectedId ?: return
        operation { block(loaded, id) }
    }

    private fun operation(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, errorMessage = null)
        operationJob = viewModelScope.launch {
            try {
                block()
            } catch (cause: Exception) {
                fail(cause, "Terminal operation failed. Reconnect and try again.")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private suspend fun applyEvent(event: TerminalAttachEvent, thread: String, id: String) {
        val identity = when (event) {
            is TerminalAttachEvent.Snapshot -> event.snapshot.threadId to event.snapshot.terminalId
            is TerminalAttachEvent.Restarted -> event.threadId to event.terminalId
            is TerminalAttachEvent.Output -> event.threadId to event.terminalId
            is TerminalAttachEvent.Cleared -> event.threadId to event.terminalId
            is TerminalAttachEvent.Exited -> event.threadId to event.terminalId
            is TerminalAttachEvent.Closed -> event.threadId to event.terminalId
            is TerminalAttachEvent.Error -> event.threadId to event.terminalId
            is TerminalAttachEvent.Activity -> event.threadId to event.terminalId
        }
        if (identity != thread to id) return
        if (event is TerminalAttachEvent.Snapshot) {
            applySnapshot(event.snapshot)
            return
        }
        val next = when (event) {
            is TerminalAttachEvent.Output -> event.sequence
            is TerminalAttachEvent.Restarted -> event.sequence
            is TerminalAttachEvent.Cleared -> event.sequence
            is TerminalAttachEvent.Exited -> event.sequence
            is TerminalAttachEvent.Closed -> event.sequence
            is TerminalAttachEvent.Error -> event.sequence
            is TerminalAttachEvent.Activity -> event.sequence
            is TerminalAttachEvent.Snapshot -> null
        }
        if (next != null && sequence != null && next <= sequence!!) return
        check(next == null || sequence == null || next == sequence!! + 1) { "Terminal stream sequence gap" }
        if (next != null) sequence = next
        when (event) {
            is TerminalAttachEvent.Restarted -> applySnapshot(event.snapshot)
            is TerminalAttachEvent.Output -> mutableState.value.display?.append(event.data)
            is TerminalAttachEvent.Cleared -> mutableState.value.display?.clear()
            is TerminalAttachEvent.Exited ->
                mutableState.value =
                    mutableState.value.copy(
                        canInput = false,
                        status = "Exited (${event.exitCode ?: event.exitSignal ?: "unknown"})"
                    )
            is TerminalAttachEvent.Closed ->
                mutableState.value =
                    mutableState.value.copy(canInput = false, status = "Closed")
            is TerminalAttachEvent.Error ->
                mutableState.value =
                    mutableState.value.copy(
                        errorMessage = "The terminal reported an error. Reconnect or restart the session."
                    )
            is TerminalAttachEvent.Activity -> Unit // Process titles are not lifecycle states.
            is TerminalAttachEvent.Snapshot -> Unit
        }
    }

    private suspend fun applySnapshot(snapshot: TerminalSessionSnapshot) {
        sequence = snapshot.sequence
        replaceDisplay(snapshot.history)
        mutableState.value = mutableState.value.copy(
            status = if (snapshot.status ==
                "exited"
            ) {
                "Exited (${snapshot.exitCode ?: snapshot.exitSignal ?: "unknown"})"
            } else {
                snapshot.status
            },
            canInput = snapshot.status == "running",
            errorMessage = null
        )
        resize(cols, rows)
    }

    private suspend fun replaceDisplay(history: String) {
        mutableState.value.display?.detach()
        val selectedGeneration = generation
        val selectedScreen = ++screenGeneration
        val display = displays.create(
            history,
            cols,
            rows,
            onInput = { if (generation == selectedGeneration && screenGeneration == selectedScreen) send(it) },
            onResize = { columns, lines ->
                if (generation == selectedGeneration &&
                    screenGeneration == selectedScreen
                ) {
                    resize(columns, lines)
                }
            }
        )
        mutableState.value = mutableState.value.copy(display = display)
    }

    private fun clearSelection() {
        generation++
        attachJob?.cancel()
        attachJob = null
        mutableState.value.display?.detach()
        sequence = null
        mutableState.value = mutableState.value.copy(selectedId = null, display = null, canInput = false)
    }

    private fun fail(cause: Exception, message: String) {
        if (cause is CancellationException) throw cause
        mutableState.value = mutableState.value.copy(errorMessage = message)
    }

    override fun onCleared() {
        mutableState.value.display?.detach()
        input.close()
        resizeRequests.close()
    }
}
