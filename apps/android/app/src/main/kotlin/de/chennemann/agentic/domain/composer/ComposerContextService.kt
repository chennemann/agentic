package de.chennemann.agentic.domain.composer

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ComposerRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.t3.contract.ProviderSkill
import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.UploadChatAttachment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

data class ComposerSuggestion(
    val id: String,
    val kind: String,
    val label: String,
    val detail: String,
    val value: String
)

data class PreparedComposerContext(val attachments: List<UploadChatAttachment>, val records: List<JsonObject>)

interface ComposerContextActions {
    fun maxPasteBytes(): Long

    fun inlineContextAvailable(): Boolean

    suspend fun suggestions(kind: String, query: String, instanceId: String?): List<ComposerSuggestion>

    suspend fun prepare(environmentId: String, document: ComposerDocument): PreparedComposerContext
}

class ComposerContextService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: ComposerRpcClient
) : ComposerContextActions {
    private val skillCache = mutableMapOf<Triple<String, String, String>, List<ProviderSkill>>()

    override fun maxPasteBytes(): Long {
        val active = environments.activeEnvironment.value ?: return 0
        val config =
            repository.clientConfig.value.value?.takeIf { it.environment.environmentId == active.id } ?: return 0
        val capabilities = config.environment.capabilities
        if (!capabilities.attachmentUploads || !capabilities.inlineMessageContext) return 0
        return capabilities.fileAttachments?.maxUploadBytes?.coerceIn(0, 50L * 1024 * 1024) ?: 0
    }

    override fun inlineContextAvailable(): Boolean {
        val active = environments.activeEnvironment.value ?: return false
        return repository.clientConfig.value.value?.takeIf { it.environment.environmentId == active.id }
            ?.environment?.capabilities?.inlineMessageContext == true
    }

    override suspend fun suggestions(kind: String, query: String, instanceId: String?): List<ComposerSuggestion> {
        val context = context()
        val result = if (kind == "path") {
            rpc.searchComposerPaths(context.baseUrl, context.token, context.cwd, query).entries.map {
                ComposerSuggestion("path:${it.path}", "path", it.path.substringAfterLast('/'), it.path, it.path)
            }
        } else {
            val provider = repository.clientConfig.value.value?.providers?.firstOrNull { it.instanceId == instanceId }
                ?: return emptyList()
            val key = Triple(context.environmentId, provider.instanceId, context.cwd)
            val skills = provider.workspaceSnapshots.firstOrNull { it.cwd == context.cwd }?.skills
                ?: skillCache[key]
                ?: rpc.composerProviders(context.baseUrl, context.token, provider.instanceId, context.cwd).providers
                    .firstOrNull { it.instanceId == provider.instanceId }?.let { refreshed ->
                        refreshed.workspaceSnapshots.firstOrNull { it.cwd == context.cwd }?.skills
                            ?: refreshed.skills.mapNotNull {
                                runCatching { T3Json.decodeFromJsonElement<ProviderSkill>(it) }.getOrNull()
                            }
                    }.orEmpty().also { skillCache[key] = it }
            skills.filter { it.enabled && it.userInvocable != false }.distinctBy { it.name.lowercase() }
                .filter {
                    it.name.contains(query, true) ||
                        it.displayName.orEmpty().contains(query, true) ||
                        it.shortDescription.orEmpty().contains(query, true)
                }
                .sortedWith(
                    compareBy<ProviderSkill> { !it.name.startsWith(query, true) }.thenBy {
                        it.displayName
                            ?: it.name
                    }
                )
                .take(20).map {
                    ComposerSuggestion(
                        "skill:${it.name}",
                        "skill",
                        it.displayName ?: it.name,
                        it.shortDescription ?: it.description.orEmpty(),
                        it.name
                    )
                }
        }
        require(context.environmentId == environments.activeEnvironment.value?.id && context.cwd == context().cwd) {
            "The workspace changed. Search again."
        }
        return result
    }

    override suspend fun prepare(environmentId: String, document: ComposerDocument): PreparedComposerContext {
        val context = context()
        require(context.environmentId == environmentId) { "The environment changed. Send the draft again." }
        val retained = document.retained()
        require(retained.records.size <= COMPOSER_CONTEXT_MAX_RECORDS) { "Remove some context items before sending." }
        require(retained.records.isEmpty() || inlineContextAvailable()) {
            "This environment does not support inline context."
        }
        val ids = mutableMapOf<String, String>()
        val attachments = retained.pastes.map { file ->
            require(file.text.isNotEmpty()) { "Pasted text is unavailable. Remove its chip and paste it again." }
            val size = file.text.toByteArray(Charsets.UTF_8).size.toLong()
            require(size <= maxPasteBytes()) {
                "This environment cannot upload this pasted text. Remove it or use a smaller paste."
            }
            val id = rpc.uploadPastedText(context.baseUrl, context.token, file.name, file.text)
            require(environmentId == environments.activeEnvironment.value?.id) {
                "The environment changed during upload."
            }
            ids[file.id] = id
            UploadChatAttachment(
                type = "file",
                id = id,
                name = file.name,
                mimeType = "text/plain",
                sizeBytes = size,
                source = buildJsonObject { put("_tag", "pasted-text") }
            )
        }
        val records = retained.records.map { record ->
            val replacement = ids[record.string("attachmentId")]
            if (replacement == null) record else JsonObject(record + ("attachmentId" to JsonPrimitive(replacement)))
        }
        return PreparedComposerContext(attachments, records)
    }

    private suspend fun context(): Context {
        val active = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val config = requireNotNull(
            repository.clientConfig.value.value?.takeIf {
                it.environment.environmentId ==
                    active.id
            }
        ) { "The environment is not ready." }
        val shell = requireNotNull(repository.shell.value.value) { "The workspace is unavailable." }
        val thread = shell.threads.firstOrNull { it.id == repository.focusedThreadId.value }
        val project = requireNotNull(
            shell.projects.firstOrNull {
                it.id ==
                    (thread?.projectId ?: repository.selectedProjectId.value)
            }
        ) { "Choose a project first." }
        val cwd = thread?.worktreePath ?: project.workspaceRoot
        require(cwd.isNotBlank() && config.environment.environmentId == active.id)
        return Context(
            active.id,
            active.baseUrl,
            requireNotNull(credentials.read(active.id)) {
                "Pair the environment again."
            },
            cwd
        )
    }

    private data class Context(val environmentId: String, val baseUrl: String, val token: String, val cwd: String)
}
