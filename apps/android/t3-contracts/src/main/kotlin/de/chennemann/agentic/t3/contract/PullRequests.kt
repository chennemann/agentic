package de.chennemann.agentic.t3.contract

import kotlinx.serialization.Serializable

@Serializable
data class PullRequestRef(
    val projectId: String,
    val repository: String,
    val number: Int,
    val host: String,
    val expectedAccountId: String? = null
) {
    val key: String get() = "$host/$repository/$number"
}

@Serializable
data class PullRequestActor(val login: String, val name: String? = null, val isBot: Boolean? = null)

@Serializable
data class PullRequestLabel(val name: String, val color: String? = null)

@Serializable
data class PullRequestEntry(
    val host: String,
    val projectId: String,
    val projectTitle: String,
    val repository: String,
    val number: Int,
    val title: String,
    val url: String,
    val author: PullRequestActor? = null,
    val headBranch: String,
    val baseBranch: String,
    val state: String,
    val isDraft: Boolean,
    val mergeability: String,
    val additions: Int,
    val deletions: Int,
    val updatedAt: String,
    val labels: List<PullRequestLabel> = emptyList(),
    val reviewDecision: String? = null,
    val checksState: String? = null,
    val provider: String? = null,
    val viewerReviewRequested: Boolean = false
) {
    fun ref() = PullRequestRef(projectId, repository, number, host)
}

@Serializable
data class PullRequestProvider(
    val host: String,
    val configured: Boolean,
    val searchesOnHost: Boolean,
    val detail: String? = null,
    val kind: String? = null
)

@Serializable
data class PullRequestProjectError(val projectId: String, val projectTitle: String, val message: String)

@Serializable
data class PullRequestListResult(
    val viewers: Map<String, String> = emptyMap(),
    val entries: List<PullRequestEntry>,
    val providers: List<PullRequestProvider>,
    val errors: List<PullRequestProjectError>,
    val truncated: Boolean,
    val nextCursors: Map<String, String> = emptyMap()
)

@Serializable
data class PullRequestReviewCapabilities(
    val inlineComment: Boolean,
    val reply: Boolean,
    val resolve: Boolean,
    val verdicts: List<String>
)

@Serializable
data class PullRequestEditCapabilities(val changeRequest: Boolean = false, val comment: Boolean = false)

@Serializable
data class PullRequestCapabilities(
    val diff: Boolean,
    val comment: Boolean,
    val actions: List<String>,
    val mergeMethods: List<String>,
    val review: PullRequestReviewCapabilities,
    val updateMethods: List<String> = emptyList(),
    val edit: PullRequestEditCapabilities? = null,
    val labels: Boolean = false,
    val reactions: Boolean = false,
    val search: Boolean = false
)

/** Unreported host permissions are granted, so optional grants stay nullable. */
@Serializable
data class PullRequestViewerPermissions(
    val actions: List<String>,
    val comment: Boolean,
    val resolve: Boolean,
    val verdicts: List<String>,
    val updateMethods: List<String> = emptyList(),
    val editChangeRequest: Boolean? = null,
    val labels: Boolean? = null
)

@Serializable
data class PullRequestMergeCapabilities(val merge: Boolean, val squash: Boolean, val rebase: Boolean)

@Serializable
data class PullRequestCheck(
    val name: String,
    val status: String,
    val description: String? = null,
    val url: String? = null,
    val required: Boolean? = null
)

@Serializable
data class PullRequestDetail(
    val capabilities: PullRequestCapabilities,
    val viewerPermissions: PullRequestViewerPermissions,
    val projectId: String,
    val projectTitle: String,
    val repository: String,
    val number: Int,
    val title: String,
    val body: String,
    val url: String,
    val author: PullRequestActor? = null,
    val state: String,
    val isDraft: Boolean,
    val mergeability: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val headBranch: String,
    val baseBranch: String,
    val checks: List<PullRequestCheck>,
    val labels: List<PullRequestLabel>,
    val reviewers: List<PullRequestActor>,
    val mergeCapabilities: PullRequestMergeCapabilities,
    val headSha: String? = null,
    val viewer: String? = null,
    val autoMergeEnabled: Boolean? = null,
    val provider: String? = null,
    val observedAt: Double? = null
)

@Serializable
data class PullRequestChecks(val state: String, val checks: List<PullRequestCheck>, val observedAt: Double? = null)

@Serializable
data class PullRequestReaction(
    val content: String,
    val count: Int,
    val actors: List<String> = emptyList(),
    val viewerHasReacted: Boolean
)

@Serializable
data class PullRequestComment(
    val id: String,
    val kind: String,
    val author: PullRequestActor? = null,
    val body: String,
    val createdAt: String,
    val url: String? = null,
    val path: String? = null,
    val reviewState: String? = null,
    val editedAt: String? = null,
    val reactions: List<PullRequestReaction>? = null,
    val canEdit: Boolean? = null
)

@Serializable
data class PullRequestThreadComment(
    val id: String,
    val author: PullRequestActor? = null,
    val body: String,
    val createdAt: String? = null,
    val editedAt: String? = null,
    val url: String? = null,
    val reactions: List<PullRequestReaction>? = null,
    val canEdit: Boolean? = null
)

@Serializable
data class PullRequestReviewThread(
    val id: String,
    val path: String,
    val line: Int? = null,
    val isResolved: Boolean,
    val isOutdated: Boolean,
    val comments: List<PullRequestThreadComment>,
    val nextCommentsCursor: String? = null,
    val canResolve: Boolean? = null
)

@Serializable
data class PullRequestCommit(val oid: String, val messageHeadline: String)

@Serializable
data class PullRequestActivity(
    val comments: List<PullRequestComment>,
    val commentCount: Int,
    val commentsTruncated: Boolean,
    val reviewThreads: List<PullRequestReviewThread>,
    val commits: List<PullRequestCommit>,
    val reviewThreadsTruncated: Boolean = false,
    val reactions: List<PullRequestReaction>? = null
)

@Serializable
data class PullRequestDiff(val patch: String, val truncated: Boolean, val nextCursor: String? = null)

@Serializable
data class PullRequestLinkedThread(
    val id: String,
    val projectId: String,
    val title: String,
    val archivedAt: String? = null
)

@Serializable
data class PullRequestLinkedThreads(val threads: List<PullRequestLinkedThread>)

@Serializable
data class PullRequestDiffStat(
    val projectId: String,
    val repository: String,
    val number: Int,
    val additions: Int,
    val deletions: Int
)

@Serializable
data class PullRequestListStatsResult(val stats: List<PullRequestDiffStat>)

@Serializable
data class PullRequestFilterOptions(
    val labels: List<PullRequestLabel> = emptyList(),
    val authors: List<PullRequestActor> = emptyList(),
    val truncated: Boolean = false
)

@Serializable
data class PullRequestLabelCandidate(
    val name: String,
    val color: String? = null,
    val description: String? = null,
    val isApplied: Boolean
)

@Serializable
data class PullRequestLabelCandidateList(val candidates: List<PullRequestLabelCandidate>, val truncated: Boolean)

@Serializable
data class PullRequestLineCount(val additions: Int = 0, val deletions: Int = 0)

/** Servers from before the docs split send no docs key; their docs lines sit in code. */
@Serializable
data class PullRequestChangeBreakdown(
    val code: PullRequestLineCount,
    val tests: PullRequestLineCount,
    val mocks: PullRequestLineCount,
    val generated: PullRequestLineCount,
    val docs: PullRequestLineCount = PullRequestLineCount()
)
