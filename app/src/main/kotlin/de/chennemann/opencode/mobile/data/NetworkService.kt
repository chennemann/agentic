package de.chennemann.opencode.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import de.chennemann.opencode.mobile.domain.session.ConnectivityGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkService internal constructor(
    private val source: ConnectivitySource,
) : ConnectivityGateway {
    constructor(context: Context) : this(AndroidConnectivitySource(context))

    private val connected = MutableStateFlow(source.isConnected())
    private val change = MutableStateFlow(0L)

    override val online: StateFlow<Boolean> = connected.asStateFlow()
    override val changed: StateFlow<Long> = change.asStateFlow()

    init {
        source.register(
            object : ConnectivitySource.Callback {
                override fun onAvailable() {
                    mark()
                }

                override fun onLost() {
                    mark()
                }

                override fun onCapabilitiesChanged() {
                    mark()
                }

                override fun onUnavailable() {
                    mark()
                }
            }
        )
    }

    private fun mark() {
        connected.value = source.isConnected()
        change.value = change.value + 1
    }
}

internal interface ConnectivitySource {
    fun register(callback: Callback)

    fun isConnected(): Boolean

    interface Callback {
        fun onAvailable()

        fun onLost()

        fun onCapabilitiesChanged()

        fun onUnavailable()
    }
}

private class AndroidConnectivitySource(context: Context) : ConnectivitySource {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override fun register(callback: ConnectivitySource.Callback) {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    callback.onAvailable()
                }

                override fun onLost(network: Network) {
                    callback.onLost()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    callback.onCapabilitiesChanged()
                }

                override fun onUnavailable() {
                    callback.onUnavailable()
                }
            }
        )
    }

    override fun isConnected(): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
