package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.flow.StateFlow

interface ConnectivityGateway {
    val online: StateFlow<Boolean>
    val changed: StateFlow<Long>
}
