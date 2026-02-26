package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface ConnectionGateway {
    val status: StateFlow<ConnectionState>
    val endpoint: StateFlow<String>
    val found: StateFlow<String?>

    fun start(scope: CoroutineScope)

    suspend fun setUrl(next: String)

    suspend fun refresh(loading: Boolean = true)
}
