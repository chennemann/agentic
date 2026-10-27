package de.chennemann.agentic.ui.components

import androidx.lifecycle.ViewModel
import de.chennemann.agentic.domain.connection.ConnectionSupervisor
import de.chennemann.agentic.domain.connection.HostFreezeNotice
import kotlinx.coroutines.flow.StateFlow

class HostNoticeViewModel(
    private val supervisor: ConnectionSupervisor
) : ViewModel() {
    val notice: StateFlow<HostFreezeNotice?> = supervisor.hostNotice

    fun dismiss() = supervisor.dismissHostNotice()
}
