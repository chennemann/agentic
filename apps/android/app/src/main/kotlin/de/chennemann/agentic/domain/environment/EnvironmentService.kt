package de.chennemann.agentic.domain.environment

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.auth.PairingException
import de.chennemann.agentic.data.auth.PairingTarget
import de.chennemann.agentic.data.auth.PairingUrlParser
import de.chennemann.agentic.data.t3.AndroidClientMetadata
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.EnvironmentConfigClient
import de.chennemann.agentic.data.t3.EnvironmentMetadataClient
import de.chennemann.agentic.data.t3.REQUIRED_T3_SCOPES
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.T3_PORTABLE_PROTOCOL_VERSION

data class PairingPreview(
    val target: PairingTarget,
    val descriptor: ExecutionEnvironmentDescriptor,
)

fun interface EnvironmentSelector {
    suspend fun select(environmentId: String)
}

class EnvironmentService(
    private val environments: EnvironmentRepository,
    private val orchestration: OrchestrationRepository,
    private val credentials: CredentialStore,
    private val metadataClient: EnvironmentMetadataClient,
    private val authClient: EnvironmentAuthClient,
    private val configClient: EnvironmentConfigClient,
    private val clientMetadata: AndroidClientMetadata,
) : EnvironmentSelector {
    suspend fun inspect(
        value: String,
        requireCredential: Boolean,
    ): PairingPreview {
        val target = PairingUrlParser.parse(value, requireCredential)
        val descriptor = metadataClient.environmentDescriptor(target.baseUrl)
        PairingUrlParser.validateEnvironment(target, descriptor.environmentId)
        validateProtocol(descriptor)
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
        val config = configClient.clientConfig(preview.target.baseUrl, exchanged.accessToken)
        if (
            config.protocolVersion != T3_PORTABLE_PROTOCOL_VERSION ||
            config.environment.environmentId != preview.descriptor.environmentId
        ) {
            throw PairingException.EnvironmentMismatch()
        }
        credentials.write(preview.descriptor.environmentId, exchanged.accessToken)
        environments.save(preview.target.baseUrl, preview.descriptor, makeActive = true)
    }

    override suspend fun select(environmentId: String) {
        environments.select(environmentId)
    }

    suspend fun remove(environmentId: String) {
        credentials.remove(environmentId)
        orchestration.clearEnvironment(environmentId)
        environments.remove(environmentId)
    }

    private fun validateProtocol(descriptor: ExecutionEnvironmentDescriptor) {
        if (descriptor.capabilities.portableClientProtocol != T3_PORTABLE_PROTOCOL_VERSION) {
            throw UnsupportedProtocolException()
        }
    }
}

class UnsupportedProtocolException : IllegalStateException(
    "This server does not advertise T3 portable client protocol v1.",
)
