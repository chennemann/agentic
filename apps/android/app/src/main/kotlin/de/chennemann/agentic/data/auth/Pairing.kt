package de.chennemann.agentic.data.auth

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairingTarget(
    val baseUrl: String,
    val bootstrapCredential: String?,
    val expectedEnvironmentId: String? = null,
    val requiresCleartextConfirmation: Boolean = false,
)

sealed class PairingException(
    message: String,
) : IllegalArgumentException(message) {
    class Invalid : PairingException("The pairing link or server URL is invalid.")

    class MissingCredential : PairingException("The pairing link is missing its token.")

    class PublicCleartext : PairingException("Public T3 environments require HTTPS.")

    class EnvironmentMismatch : PairingException("The pairing link belongs to a different environment.")
}

object PairingUrlParser {
    fun parse(
        rawValue: String,
        requireCredential: Boolean,
    ): PairingTarget {
        val outer = parseUri(rawValue)
        val hostedHost = outer.queryParameters()["host"]?.trim().orEmpty()
        val backend = if (hostedHost.isNotEmpty()) parseUri(hostedHost) else outer
        if (backend.scheme.lowercase() !in setOf("http", "https")) throw PairingException.Invalid()
        if (backend.host.isNullOrBlank()) throw PairingException.Invalid()
        if (!backend.userInfo.isNullOrBlank()) throw PairingException.Invalid()

        val outerParams = outer.queryParameters() + outer.fragmentParameters()
        val backendParams = backend.queryParameters() + backend.fragmentParameters()
        val credential = (
            outerParams["token"]
                ?: backendParams["token"]
            )?.trim()?.takeIf(String::isNotEmpty)
        if (requireCredential && credential == null) throw PairingException.MissingCredential()
        val expectedEnvironmentId = (
            outerParams["environmentId"]
                ?: outerParams["environment_id"]
                ?: backendParams["environmentId"]
                ?: backendParams["environment_id"]
            )?.trim()?.takeIf(String::isNotEmpty)

        val normalized = URI(
            backend.scheme.lowercase(),
            null,
            backend.host,
            backend.port,
            "/",
            null,
            null,
        )
        val cleartext = normalized.scheme == "http"
        if (cleartext && !isPrivateEndpoint(normalized.host)) throw PairingException.PublicCleartext()
        return PairingTarget(
            baseUrl = normalized.toASCIIString(),
            bootstrapCredential = credential,
            expectedEnvironmentId = expectedEnvironmentId,
            requiresCleartextConfirmation = cleartext,
        )
    }

    fun validateEnvironment(
        target: PairingTarget,
        actualEnvironmentId: String,
    ) {
        if (
            target.expectedEnvironmentId != null &&
            target.expectedEnvironmentId != actualEnvironmentId
        ) {
            throw PairingException.EnvironmentMismatch()
        }
    }

    private fun parseUri(value: String): URI {
        val trimmed = value.trim().removePrefix("//")
        if (trimmed.isEmpty()) throw PairingException.Invalid()
        val normalized = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching { URI(normalized) }.getOrElse { throw PairingException.Invalid() }
    }

    private fun URI.queryParameters(): Map<String, String> = parseParameters(rawQuery)

    private fun URI.fragmentParameters(): Map<String, String> = parseParameters(rawFragment)

    private fun parseParameters(value: String?): Map<String, String> = value
        ?.split('&')
        ?.mapNotNull { entry ->
            val (key, encodedValue) = entry.split('=', limit = 2).let {
                it.firstOrNull().orEmpty() to it.getOrElse(1) { "" }
            }
            if (key.isBlank()) {
                null
            } else {
                decode(key) to decode(encodedValue)
            }
        }
        ?.toMap()
        .orEmpty()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun isPrivateEndpoint(host: String): Boolean {
        val lower = host.lowercase()
        if (
            lower == "localhost" ||
            lower.endsWith(".localhost") ||
            lower.endsWith(".local") ||
            lower.endsWith(".lan") ||
            lower.endsWith(".internal") ||
            lower.endsWith(".ts.net")
        ) {
            return true
        }
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }
        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            return bytes[0] == 100 && bytes[1] in 64..127
        }
        return false
    }
}
