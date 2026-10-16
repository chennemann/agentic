package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable

@Serializable
data class ThreadTurnDiff(val threadId: String, val fromTurnCount: Int, val toTurnCount: Int, val diff: String)
