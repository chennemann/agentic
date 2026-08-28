package de.chennemann.agentic.data.auth

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.security.KeyChain
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

interface ClientCertificateStore {
    fun aliasFor(baseUrl: String): String?

    fun setAlias(baseUrl: String, alias: String?)
}

class AndroidClientCertificateStore(
    context: Context,
) : ClientCertificateStore {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun aliasFor(baseUrl: String): String? = clientCertificateEndpointKey(baseUrl)?.let {
        preferences.getString(it, null)
    }

    override fun setAlias(baseUrl: String, alias: String?) {
        val key = requireNotNull(clientCertificateEndpointKey(baseUrl)) {
            "Client certificates require an HTTPS server URL."
        }
        preferences.edit().apply {
            if (alias == null) remove(key) else putString(key, alias)
        }.apply()
    }

    private companion object {
        const val PreferencesName = "t3-client-certificates"
    }
}

class AndroidClientCertificateSelector {
    suspend fun choose(
        activity: Activity,
        serverUrl: String,
        preselectedAlias: String?,
    ): String? = suspendCancellableCoroutine { continuation ->
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                if (continuation.isActive) continuation.resume(alias)
            },
            null,
            null,
            Uri.parse(serverUrl),
            preselectedAlias,
        )
    }
}

internal fun clientCertificateEndpointKey(baseUrl: String): String? {
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    val port = uri.port.takeIf { it >= 0 } ?: 443
    return "${uri.host.lowercase()}:$port"
}
