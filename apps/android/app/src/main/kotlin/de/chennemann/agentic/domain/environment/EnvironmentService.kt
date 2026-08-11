package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.auth.PairingException
import de.chennemann.agentic.data.auth.PairingTarget
import de.chennemann.agentic.data.auth.PairingUrlParser
import de.chennemann.agentic.data.t3.AndroidClientMetadata
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.REQUIRED_T3_SCOPES
import de.chennemann.agentic.data.t3.T3RpcClient
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor

data class PairingPreview(
    val target: PairingTarget,
    val descriptor: ExecutionEnvironmentDescriptor,
)

fun interface EnvironmentSelector {
    suspend fun select(environmentId: String)
}

fun interface EnvironmentRemover {
    suspend fun remove(environmentId: String)
}

fun interface EnvironmentCacheRemover {
    suspend fun clear(environmentId: String)
}

class EnvironmentService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val metadataClient: EnvironmentMetadataClient,
    private val authClient: EnvironmentAuthClient,
    private val rpc: T3RpcClient,
    private val clientMetadata: AndroidClientMetadata,
) : EnvironmentSelector {
    suspend fun inspect(
        value: String,
        requireCredential: Boolean,
    ): PairingPreview {
        val target = PairingUrlParser.parse(value, requireCredential)
        val descriptor = metadataClient.environmentDescriptor(target.baseUrl)
        PairingUrlParser.validateEnvironment(target, descriptor.environmentId)
        return PairingPreview(target, descriptor)
    }

    suspend fun pair(preview: PairingPreview) {
        val bootstrapCredential = preview.target.bootstrapCredential ?: throw PairingException.MissingCredential()
        val exchanged = authClient.exchangeToken(
            preview.target.baseUrl,
            bootstrapCredential,
            clientMetadata,
        )
        val exchangedScopes = exchanged.scope.split(' ').filter(String::isNotBlank).toSet()
        if (!exchangedScopes.containsAll(REQUIRED_T3_SCOPES.split(' '))) {
            throw PairingException.Invalid()
        }
        val session = authClient.session(preview.target.baseUrl, exchanged.accessToken)
        if (!session.authenticated || !session.scopes.toSet().containsAll(REQUIRED_T3_SCOPES.split(' '))) {
            throw PairingException.Invalid()
        }
        val config = rpc.serverConfig(preview.target.baseUrl, exchanged.accessToken)
        if (config.environment.environmentId != preview.descriptor.environmentId) {
            throw PairingException.EnvironmentMismatch()
        }
        credentials.write(preview.descriptor.environmentId, exchanged.accessToken)
        environments.save(preview.target.baseUrl, preview.descriptor, makeActive = true)
    }

    override suspend fun select(environmentId: String) {
        environments.select(environmentId)
    }
}

class RegisteredEnvironmentRemover(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val caches: EnvironmentCacheRemover,
) : EnvironmentRemover {
    override suspend fun remove(environmentId: String) {
        require(environments.environments.value.any { it.id == environmentId }) {
            "Environment is no longer registered."
        }
        val wasActive = environments.activeEnvironment.value?.id == environmentId
        credentials.remove(environmentId)
        environments.remove(environmentId)
        caches.clear(environmentId)
        if (wasActive) {
            environments.environments.value.firstOrNull()?.let { environments.select(it.id) }
        }
    }

}
