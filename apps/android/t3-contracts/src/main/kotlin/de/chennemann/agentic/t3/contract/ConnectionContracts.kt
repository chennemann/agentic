package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable

/**
 * A stretch where the server's event loop could not run, so it answered nothing and clients may have dropped.
 * Sent in a config snapshot for a few minutes after it happened; it describes the host at connect time.
 */
@Serializable
data class ServerEventLoopStall(
    val detectedAt: String,
    val durationMs: Long,
    /** One-minute load average; absent on hosts without one. */
    val loadAverage: Double? = null,
    val cpuCount: Int
)

/** Body of the HTTP 426 a server returns for a WebSocket upgrade with another orchestration protocol. */
@Serializable
data class OrchestrationProtocolIncompatible(
    val code: String,
    val message: String? = null,
    val orchestrationProtocolVersion: Int? = null
)
