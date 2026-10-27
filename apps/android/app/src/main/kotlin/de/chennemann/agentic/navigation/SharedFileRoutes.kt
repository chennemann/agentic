package de.chennemann.agentic.navigation

import de.chennemann.agentic.domain.attachments.SharedFileSource
import de.chennemann.agentic.domain.attachments.opensInSharedViewer
import de.chennemann.agentic.domain.orchestration.FileLinkTarget

internal fun SharedFileSource.route(): SharedFileRoute = when (this) {
    is SharedFileSource.Attachment -> SharedFileRoute(name = name, attachmentId = attachmentId, mimeType = mimeType)
    is SharedFileSource.HostPath -> SharedFileRoute(name = name, threadId = threadId, path = path)
}

internal fun SharedFileRoute.source(): SharedFileSource? = when {
    attachmentId != null -> SharedFileSource.Attachment(attachmentId, name, mimeType)
    threadId != null && path != null -> SharedFileSource.HostPath(threadId, path)
    else -> null
}

/** Files the workspace text viewer cannot show open in the shared-file viewer; text keeps its line target. */
internal fun fileLinkRoute(threadId: String, target: FileLinkTarget): AppRoute =
    if (opensInSharedViewer(target.path)) {
        SharedFileSource.HostPath(threadId, target.path).route()
    } else {
        WorkspaceFilesRoute(threadId, target.path, target.line, target.column)
    }
