package de.chennemann.agentic.data.t3

import android.content.Context
import android.security.KeyChain
import de.chennemann.agentic.data.auth.ClientCertificateStore
import de.chennemann.agentic.t3.contract.T3Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.net.URI
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

fun interface T3HttpClientProvider {
    fun client(baseUrl: String): HttpClient
}

class AndroidT3HttpClientProvider(
    private val context: Context,
    private val certificates: ClientCertificateStore,
) : T3HttpClientProvider {
    private val clients = ConcurrentHashMap<ClientIdentity, HttpClient>()
    private val trustManager by lazy(::systemTrustManager)

    override fun client(baseUrl: String): HttpClient {
        val identity = ClientIdentity(t3ClientOrigin(baseUrl), certificates.aliasFor(baseUrl))
        return clients.computeIfAbsent(identity) { createClient(it.certificateAlias) }
    }

    private fun createClient(certificateAlias: String?): HttpClient {
        val tls = certificateAlias?.let { alias ->
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                arrayOf(KeyChainClientKeyManager(context.applicationContext, alias)),
                arrayOf(trustManager),
                null,
            )
            sslContext.socketFactory
        }
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(T3Json)
            }
            install(WebSockets)
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                    if (tls != null) {
                        sslSocketFactory(tls, trustManager)
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                }
            }
        }
    }

    private data class ClientIdentity(
        val baseUrl: String,
        val certificateAlias: String?,
    )
}

private fun t3ClientOrigin(url: String): String {
    val uri = URI(url)
    return URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toASCIIString()
}

private class KeyChainClientKeyManager(
    private val context: Context,
    private val alias: String,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = compatibleAlias(keyType, issuers)

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = compatibleAlias(keyType, issuers)

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = compatibleAlias(keyType?.let { arrayOf(it) }, issuers)?.let { arrayOf(it) }

    override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? =
        requestedAlias
            ?.takeIf(alias::equals)
            ?.let { runCatching { KeyChain.getCertificateChain(context, it) }.getOrNull() }

    override fun getPrivateKey(requestedAlias: String?): PrivateKey? =
        requestedAlias
            ?.takeIf(alias::equals)
            ?.let { runCatching { KeyChain.getPrivateKey(context, it) }.getOrNull() }

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    private fun compatibleAlias(
        keyTypes: Array<out String>?,
        issuers: Array<out Principal>?,
    ): String? {
        val chain = getCertificateChain(alias) ?: return null
        val algorithm = chain.firstOrNull()?.publicKey?.algorithm ?: return null
        if (!keyTypes.isNullOrEmpty() && keyTypes.none { keyAlgorithmMatches(algorithm, it) }) return null
        if (!issuers.isNullOrEmpty() && chain.none { it.issuerX500Principal in issuers }) return null
        return alias
    }
}

internal fun keyAlgorithmMatches(
    algorithm: String,
    keyType: String,
): Boolean {
    val normalizedAlgorithm = algorithm.uppercase()
    val normalizedKeyType = keyType.uppercase().substringBefore('_')
    return normalizedAlgorithm == normalizedKeyType ||
        (normalizedAlgorithm == "RSASSA-PSS" && normalizedKeyType == "RSA")
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}
