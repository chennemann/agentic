package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.t3.NetworkFailure
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.ORCHESTRATION_PROTOCOL_VERSION
import de.chennemann.agentic.t3.contract.ServerEventLoopStall
import kotlin.math.roundToInt

/** Why a host dropped, from the recent stall it reports when a client reconnects. */
data class HostFreezeNotice(
    val key: String,
    val title: String,
    val description: String
)

fun hostFreezeNotice(environmentId: String, hostLabel: String, stall: ServerEventLoopStall?): HostFreezeNotice? {
    stall ?: return null
    val load = stall.loadAverage
    return HostFreezeNotice(
        key = "$environmentId:${stall.detectedAt}",
        title = "$hostLabel froze for ${(stall.durationMs / 1000.0).roundToInt()}s",
        description = if (load != null && load > stall.cpuCount) {
            "Its CPU is overloaded (load ${load.roundToInt()} on ${stall.cpuCount} cores)."
        } else {
            "T3 Code on $hostLabel was too busy to answer."
        }
    )
}

/**
 * Explains an orchestration protocol mismatch in terms of what to update. [serverProtocolVersion] is null when the
 * server did not say, e.g. a refused upgrade without a readable body.
 */
fun protocolMismatchMessage(hostLabel: String, serverProtocolVersion: Int?): String = when {
    serverProtocolVersion != null && serverProtocolVersion > ORCHESTRATION_PROTOCOL_VERSION ->
        "$hostLabel runs a newer T3 Code than this version of Agentic supports. Update Agentic to connect."
    serverProtocolVersion != null ->
        "$hostLabel runs an older T3 Code. Update T3 Code on $hostLabel to connect."
    else ->
        "$hostLabel and Agentic use different T3 protocol versions. Update T3 Code on $hostLabel, or Agentic, to connect."
}

/** Null when the server speaks this client's protocol; servers predating negotiation report none and speak 1. */
fun ExecutionEnvironmentDescriptor.protocolMismatchMessage(): String? =
    if (supportsOrchestrationProtocol) null else protocolMismatchMessage(label, orchestrationProtocolVersion)

/**
 * A specific, actionable reason for a failed connection attempt. Only failures before the socket opened point at
 * the network path; a host that answered once and then went quiet is more often asleep or overloaded.
 */
fun connectionFailureMessage(cause: Throwable, hostLabel: String): String = when (cause) {
    is T3TransportException.Network -> when (cause.reason) {
        NetworkFailure.UNRESOLVED_HOST ->
            "Can't find $hostLabel. Check the server address, and that this phone's DNS or VPN (for example Tailscale) is connected."
        NetworkFailure.REFUSED ->
            "Nothing answered at $hostLabel's address. Check that T3 Code is running there and listening on that port."
        NetworkFailure.TIMEOUT ->
            "$hostLabel did not answer in time. It may be asleep, overloaded, or off the network."
        NetworkFailure.TLS ->
            "The secure connection to $hostLabel failed. Check the server certificate and the device's date and time."
        NetworkFailure.DISCONNECTED -> "$hostLabel closed the connection."
        NetworkFailure.UNREACHABLE ->
            "Can't reach $hostLabel. Check that it is online and reachable from this phone's network."
    }
    is T3TransportException.Unresponsive ->
        "$hostLabel stopped responding for ${cause.silentSeconds}s. It may be overloaded, asleep, or off the network."
    is T3TransportException.IncompatibleProtocol -> protocolMismatchMessage(hostLabel, cause.serverProtocolVersion)
    is T3TransportException.Http -> when (cause.statusCode) {
        404 -> "The server at $hostLabel's address does not look like T3 Code (HTTP 404). Check the address."
        502, 503, 504 ->
            "A proxy in front of $hostLabel answered HTTP ${cause.statusCode}. T3 Code may not be running behind it."
        else -> "$hostLabel answered HTTP ${cause.statusCode}."
    }
    is T3TransportException.InvalidResponse ->
        "$hostLabel sent a response Agentic does not understand. Updating T3 Code or Agentic may fix this."
    else -> cause.message ?: "Connection to $hostLabel interrupted."
}
