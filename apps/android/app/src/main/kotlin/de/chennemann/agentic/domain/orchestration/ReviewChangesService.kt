package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ReviewDiffRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ReviewSection(val id: String, val label: String, val from: Int, val to: Int, val allTurns: Boolean = false)
data class ReviewSnapshot(
    val title: String,
    val sections: List<ReviewSection>,
    val selectedId: String?,
    val diff: String
)

fun interface ReviewChanges {
    suspend fun load(threadId: String, sectionId: String?): ReviewSnapshot
}

class ReviewChangesService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: ReviewDiffRpcClient
) : ReviewChanges {
    override suspend fun load(threadId: String, sectionId: String?): ReviewSnapshot {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        require(repository.clientConfig.value.value?.environment?.environmentId == environment.id) {
            "The environment is not ready yet."
        }
        val detail = requireNotNull(repository.focusedThread.value.value?.thread?.takeIf { it.id == threadId }) {
            "Open the thread again before reviewing its changes."
        }
        val sections = reviewSections(detail.checkpoints.filterIsInstance<JsonObject>())
        val selected = sections.firstOrNull { it.id == sectionId } ?: sections.firstOrNull()
            ?: return ReviewSnapshot(detail.title, emptyList(), null, "")
        val token = requireNotNull(credentials.read(environment.id)) { "Pair the environment again to review changes." }
        val result = if (selected.allTurns) {
            rpc.fullThreadDiff(environment.baseUrl, token, threadId, selected.to)
        } else {
            rpc.turnDiff(environment.baseUrl, token, threadId, selected.from, selected.to)
        }
        check(environments.activeEnvironment.value?.id == environment.id) {
            "The selected environment changed. Reopen changes."
        }
        check(
            result.threadId == threadId &&
                result.toTurnCount == selected.to &&
                result.fromTurnCount in 0..result.toTurnCount &&
                (selected.allTurns || result.fromTurnCount == selected.from)
        ) {
            "The server returned a different change range. Refresh and try again."
        }
        return ReviewSnapshot(detail.title, sections, selected.id, result.diff)
    }
}

internal fun reviewSections(checkpoints: List<JsonObject>): List<ReviewSection> {
    val counts = checkpoints.filter { it["status"]?.jsonPrimitive?.contentOrNull == "ready" }
        .mapNotNull { it["checkpointTurnCount"]?.jsonPrimitive?.intOrNull }
        .filter { it > 0 }.distinct().sortedDescending()
    if (counts.isEmpty()) return emptyList()
    return counts.map { ReviewSection("turn:$it", "Turn $it", it - 1, it) } +
        ReviewSection("all", "All turns", 0, counts.first(), allTurns = true)
}
