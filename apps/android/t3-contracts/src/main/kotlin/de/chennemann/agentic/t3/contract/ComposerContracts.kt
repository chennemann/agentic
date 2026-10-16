package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable

@Serializable
data class ProviderSkill(
    val name: String,
    val path: String,
    val enabled: Boolean,
    val displayName: String? = null,
    val description: String? = null,
    val shortDescription: String? = null,
    val userInvocable: Boolean? = null,
    val userInvocationOnly: Boolean? = null
)

@Serializable
data class ProviderWorkspaceSnapshot(
    val cwd: String,
    val skills: List<ProviderSkill> = emptyList(),
    val slashCommands: List<SlashCommand> = emptyList()
)

@Serializable
data class ProviderUpdatedResult(val providers: List<ProviderInstance>)

@Serializable
data class AttachmentUploadUrl(val attachmentId: String, val relativeUrl: String, val expiresAt: Long)

@Serializable
data class FileAttachmentCapability(val maxUploadBytes: Long)
