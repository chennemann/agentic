package de.chennemann.agentic.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.TerminalContext
import de.chennemann.agentic.domain.orchestration.TerminalSessions
import de.chennemann.agentic.domain.orchestration.nextTerminalId
import de.chennemann.agentic.domain.orchestration.preferredTerminal
import de.chennemann.agentic.t3.contract.TerminalAttachEvent
import de.chennemann.agentic.t3.contract.TerminalMetadataEvent
import de.chennemann.agentic.t3.contract.TerminalSessionSnapshot
import de.chennemann.agentic.t3.contract.TerminalSummary
import de.chennemann.agentic.t3.contract.ProjectScript
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TerminalUiState(
    val terminals: List<TerminalSummary> = emptyList(),
    val selectedId: String? = null,
    val output: String = "",
    val status: String = "loading",
    val errorMessage: String? = null,
)

class TerminalViewModel(private val sessions: TerminalSessions) : ViewModel() {
    private val mutableState = MutableStateFlow(TerminalUiState())
    val state = mutableState.asStateFlow()
    private var context: TerminalContext? = null
    private var inventoryJob: Job? = null
    private var attachJob: Job? = null
    private var pendingScript: ProjectScript? = null

    fun load(threadId: String, script: ProjectScript? = null) {
        pendingScript = script
        if (context?.threadId == threadId) {
            launchPendingScript()
            return
        }
        inventoryJob?.cancel()
        inventoryJob = viewModelScope.launch {
            runCatching { sessions.context(threadId) }
                .onSuccess { loaded ->
                    context = loaded
                    observeInventory(loaded)
                }
                .onFailure(::fail)
        }
    }

    private suspend fun observeInventory(loaded: TerminalContext) {
        runCatching {
            sessions.inventory(loaded).collect { event ->
                val current = mutableState.value.terminals.associateBy { it.terminalId }.toMutableMap()
                when (event) {
                    is TerminalMetadataEvent.Snapshot -> {
                        current.clear()
                        event.terminals.forEach { current[it.terminalId] = it }
                    }
                    is TerminalMetadataEvent.Upsert -> if (event.terminal.threadId == loaded.threadId) {
                        current[event.terminal.terminalId] = event.terminal
                    }
                    is TerminalMetadataEvent.Remove -> if (event.threadId == loaded.threadId) current.remove(event.terminalId)
                }
                val terminals = current.values.sortedBy { terminalNumber(it.terminalId) }
                val selected = mutableState.value.selectedId?.takeIf(current::containsKey) ?: preferredTerminal(terminals)
                val selectionChanged = selected != mutableState.value.selectedId
                mutableState.value = mutableState.value.copy(terminals = terminals, selectedId = selected, status = "ready")
                if (pendingScript != null) {
                    launchPendingScript()
                } else if (selected != null && (attachJob == null || selectionChanged)) {
                    select(selected)
                }
            }
        }.onFailure(::fail)
    }

    fun create() {
        val loaded = context ?: return
        viewModelScope.launch {
            val id = nextTerminalId(mutableState.value.terminals)
            runCatching { sessions.open(loaded, id) }.onSuccess { select(id, it) }.onFailure(::fail)
        }
    }

    fun select(terminalId: String, initial: TerminalSessionSnapshot? = null) {
        val loaded = context ?: return
        attachJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedId = terminalId,
            output = initial?.history.orEmpty(),
            status = initial?.status ?: mutableState.value.status,
            errorMessage = null,
        )
        attachJob = viewModelScope.launch {
            runCatching {
                sessions.attach(loaded, terminalId).collect(::applyEvent)
            }.onFailure(::fail)
        }
    }

    fun send(text: String) {
        val loaded = context ?: return
        val id = mutableState.value.selectedId ?: return
        if (text.isEmpty()) return
        viewModelScope.launch { runCatching { sessions.write(loaded, id, "$text\r") }.onFailure(::fail) }
    }

    fun clear() = mutate { loaded, id -> sessions.clear(loaded, id) }
    fun restart() = mutate { loaded, id -> applySnapshot(sessions.restart(loaded, id)) }
    fun close() = mutate { loaded, id -> sessions.close(loaded, id) }

    private fun launchPendingScript() {
        val loaded = context ?: return
        val script = pendingScript ?: return
        pendingScript = null
        viewModelScope.launch {
            runCatching { sessions.launchScript(loaded, mutableState.value.terminals, script) }
                .onSuccess { (terminalId, snapshot) -> select(terminalId, snapshot) }
                .onFailure(::fail)
        }
    }

    private fun mutate(block: suspend (TerminalContext, String) -> Unit) {
        val loaded = context ?: return
        val id = mutableState.value.selectedId ?: return
        viewModelScope.launch { runCatching { block(loaded, id) }.onFailure(::fail) }
    }

    private fun applyEvent(event: TerminalAttachEvent) {
        when (event) {
            is TerminalAttachEvent.Snapshot -> applySnapshot(event.snapshot)
            is TerminalAttachEvent.Restarted -> applySnapshot(event.snapshot)
            is TerminalAttachEvent.Output -> append(event.data)
            is TerminalAttachEvent.Cleared -> mutableState.value = mutableState.value.copy(output = "")
            is TerminalAttachEvent.Exited -> mutableState.value = mutableState.value.copy(status = "exited (${event.exitCode ?: event.exitSignal ?: "unknown"})")
            is TerminalAttachEvent.Closed -> mutableState.value = mutableState.value.copy(output = "", status = "closed")
            is TerminalAttachEvent.Error -> mutableState.value = mutableState.value.copy(errorMessage = event.message)
            is TerminalAttachEvent.Activity -> mutableState.value = mutableState.value.copy(status = event.label)
        }
    }

    private fun applySnapshot(snapshot: TerminalSessionSnapshot) {
        mutableState.value = mutableState.value.copy(output = snapshot.history.takeLast(MaxTail), status = snapshot.status)
    }

    private fun append(text: String) {
        mutableState.value = mutableState.value.copy(output = (mutableState.value.output + text).takeLast(MaxTail))
    }

    private fun fail(cause: Throwable) {
        mutableState.value = mutableState.value.copy(errorMessage = cause.message ?: "Terminal operation failed.", status = "error")
    }

    private fun terminalNumber(id: String) = id.substringAfter("term-", "999999").toIntOrNull() ?: Int.MAX_VALUE

    private companion object { const val MaxTail = 512 * 1024 }
}
