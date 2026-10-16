package de.chennemann.agentic.data.t3

import de.chennemann.agentic.t3.contract.ProviderUpdatedResult
import de.chennemann.agentic.t3.contract.WorkspaceEntriesResult

interface ComposerRpcClient {
    suspend fun searchComposerPaths(baseUrl: String, token: String, cwd: String, query: String): WorkspaceEntriesResult

    suspend fun composerProviders(
        baseUrl: String,
        token: String,
        instanceId: String,
        cwd: String
    ): ProviderUpdatedResult

    suspend fun uploadPastedText(baseUrl: String, token: String, name: String, text: String): String
}
