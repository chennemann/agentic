package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.ProviderInstance
import de.chennemann.agentic.t3.contract.ThreadSession

/**
 * The models a started thread may still switch to. T3 binds a started thread to the driver of its
 * session: a turn may move to another model, or to another instance of the same driver that can
 * resume the session, but never to a different driver. Providers may also forbid any model change.
 */
data class ThreadModelLock(
    val instanceId: String,
    val model: String,
    val providerLabel: String,
    private val driver: String?,
    private val continuationGroup: String?,
    private val requiresNewThread: Boolean,
) {
    fun allows(provider: ProviderInstance, model: String): Boolean {
        val sameInstance = provider.instanceId == instanceId
        if (sameInstance && model == this.model) return true
        if (requiresNewThread || provider.requiresNewThreadForModelChange) return false
        if (sameInstance) return true
        return driver != null &&
            provider.driver == driver &&
            provider.continuation?.groupKey == continuationGroup
    }

    companion object {
        /** Returns null while the thread has not started, when every model remains available. */
        fun of(
            providers: List<ProviderInstance>,
            modelSelection: ModelSelection?,
            session: ThreadSession?,
            started: Boolean,
        ): ThreadModelLock? {
            if (!started || modelSelection == null) return null
            val instanceId = session?.providerInstanceId ?: modelSelection.instanceId
            val provider = providers.firstOrNull { it.instanceId == instanceId }
            return ThreadModelLock(
                instanceId = instanceId,
                model = modelSelection.model,
                providerLabel = provider?.displayName?.takeIf(String::isNotBlank) ?: instanceId,
                driver = provider?.driver ?: session?.providerName,
                continuationGroup = provider?.continuation?.groupKey,
                requiresNewThread = provider?.requiresNewThreadForModelChange == true,
            )
        }
    }
}
