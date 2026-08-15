package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.WorkspaceFilesRpcClient
import de.chennemann.agentic.domain.environment.EnvironmentRepository

data class WorkspaceFileEntry(val path: String, val isDirectory: Boolean)

data class WorkspaceFileListing(
    val cwd: String,
    val entries: List<WorkspaceFileEntry>,
    val truncated: Boolean,
)

sealed interface WorkspaceFilePreview {
    val path: String

    data class Text(
        override val path: String,
        val contents: String,
        val byteLength: Long,
        val truncated: Boolean,
        val markdown: Boolean,
    ) : WorkspaceFilePreview

    data class Image(
        override val path: String,
        val dataUrl: String,
        val mimeType: String,
    ) : WorkspaceFilePreview

    data class Unsupported(override val path: String, val reason: String) : WorkspaceFilePreview
}

interface WorkspaceFilesBrowser {
    suspend fun list(threadId: String): WorkspaceFileListing
    suspend fun preview(threadId: String, relativePath: String): WorkspaceFilePreview
}

class WorkspaceFilesService(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val repository: OrchestrationRepository,
    private val rpc: WorkspaceFilesRpcClient,
) : WorkspaceFilesBrowser {
    override suspend fun list(threadId: String): WorkspaceFileListing {
        val context = context(threadId)
        val result = rpc.listEntries(context.baseUrl, context.token, context.cwd)
        return WorkspaceFileListing(
            cwd = context.cwd,
            entries = result.entries.map { WorkspaceFileEntry(it.path, it.kind == "directory") },
            truncated = result.truncated,
        )
    }

    override suspend fun preview(threadId: String, relativePath: String): WorkspaceFilePreview {
        require(isSafeRelativePath(relativePath)) { "This file path is invalid." }
        val context = context(threadId)
        val type = previewType(relativePath)
        if (type is PreviewType.Unsupported) return WorkspaceFilePreview.Unsupported(relativePath, type.reason)
        if (type is PreviewType.Image) {
            val separator = if ('\\' in context.cwd && '/' !in context.cwd) "\\" else "/"
            val absolutePath = context.cwd.trimEnd('/', '\\') + separator + relativePath.replace('/', separator.single())
            return WorkspaceFilePreview.Image(
                path = relativePath,
                dataUrl = rpc.loadWorkspaceImage(context.baseUrl, context.token, threadId, absolutePath, type.mimeType),
                mimeType = type.mimeType,
            )
        }
        val result = rpc.readFile(context.baseUrl, context.token, context.cwd, relativePath)
        return WorkspaceFilePreview.Text(
            path = result.relativePath,
            contents = result.contents,
            byteLength = result.byteLength,
            truncated = result.truncated,
            markdown = relativePath.substringAfterLast('.', "").lowercase() in setOf("md", "mdx"),
        )
    }

    private suspend fun context(threadId: String): WorkspaceContext {
        val environment = requireNotNull(environments.activeEnvironment.value) { "Select an environment first." }
        val config = repository.clientConfig.value.value
        require(config?.environment?.environmentId == environment.id) { "The environment is not ready yet." }
        require(config.environment.capabilities.workspaceFiles) { "Workspace files are not supported by this server." }
        val shell = requireNotNull(repository.shell.value.value) { "The workspace is unavailable." }
        val thread = requireNotNull(shell.threads.firstOrNull { it.id == threadId }) { "The thread is no longer available." }
        val project = requireNotNull(shell.projects.firstOrNull { it.id == thread.projectId }) { "The project is no longer available." }
        val cwd = thread.worktreePath ?: project.workspaceRoot
        require(cwd.isNotBlank()) { "This thread does not have an active workspace path." }
        val token = requireNotNull(credentials.read(environment.id)) {
            "The environment credential is unavailable. Pair the environment again."
        }
        return WorkspaceContext(environment.baseUrl, token, cwd)
    }
}

private data class WorkspaceContext(val baseUrl: String, val token: String, val cwd: String)

private sealed interface PreviewType {
    data class Image(val mimeType: String) : PreviewType
    data object Text : PreviewType
    data class Unsupported(val reason: String) : PreviewType
}

private fun previewType(path: String): PreviewType = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> PreviewType.Image("image/png")
    "jpg", "jpeg" -> PreviewType.Image("image/jpeg")
    "gif" -> PreviewType.Image("image/gif")
    "webp" -> PreviewType.Image("image/webp")
    "avif", "ico", "svg", "pdf", "html", "htm" -> PreviewType.Unsupported(
        "This file type cannot be previewed safely on this Android client.",
    )
    else -> PreviewType.Text
}

private fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || Regex("^[A-Za-z]:").containsMatchIn(path)) return false
    var depth = 0
    path.replace('\\', '/').split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (--depth < 0) return false
            else -> depth++
        }
    }
    return depth > 0
}
