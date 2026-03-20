package de.chennemann.agentic.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface MdnsGateway {
    fun discover(): Flow<MdnsEntry>
}

data class MdnsEntry(
    val name: String,
    val host: String,
    val port: Int,
) {
    val url: String = "http://${host(host)}:$port"

    private fun host(value: String): String {
        if (!value.contains(':')) return value
        val clean = value.substringBefore('%')
        if (clean.startsWith("[") && clean.endsWith("]")) return clean
        return "[$clean]"
    }
}

class MdnsService(
    private val context: Context,
) : MdnsGateway {
    override fun discover(): Flow<MdnsEntry> = callbackFlow {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager?
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val lock = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager?)
            ?.createMulticastLock("opencode-mdns")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith("opencode-")) return
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress?.let {
                                if (it.contains('.')) it else null
                            }
                                ?: serviceInfo.host?.hostName
                                ?: serviceInfo.host?.hostAddress?.substringBefore('%')
                                ?: return
                            trySend(
                                MdnsEntry(
                                    name = serviceInfo.serviceName,
                                    host = host,
                                    port = serviceInfo.port,
                                )
                            )
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }

        manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            lock?.release()
        }
    }
}
