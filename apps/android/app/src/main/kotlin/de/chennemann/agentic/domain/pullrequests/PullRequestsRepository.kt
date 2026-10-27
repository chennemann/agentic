package de.chennemann.agentic.domain.pullrequests

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.EnvironmentAuthClient
import de.chennemann.agentic.data.t3.PullRequestsRpcClient
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.t3.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

/** Ephemeral host snapshots; T3 and the source-control host remain authoritative. */
data class PullRequestsSnapshot(
    val environmentId: String? = null,
    val filter: String = "open",
    val involvement: String = "all",
    val query: String = "",
    val list: PullRequestListResult? = null,
    val loading: Boolean = false,
    val limit: Int = 50,
    val listError: String? = null,
    val ref: PullRequestRef? = null,
    val detail: PullRequestDetail? = null,
    val activity: PullRequestActivity? = null,
    val threads: List<PullRequestLinkedThread> = emptyList(),
    val patch: String = "",
    val nextDiffCursor: String? = null,
    val diffTruncated: Boolean = false,
    val diffLoaded: Boolean = false,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val activityError: String? = null,
    val diffError: String? = null,
    val canWrite: Boolean = false,
    val writing: Boolean = false,
    val actionError: String? = null,
    val filters: PullRequestListFilterState = PullRequestListFilterState(),
    val stats: PullRequestStatsCache = PullRequestStatsCache(),
    val filterOptions: PullRequestFilterOptions? = null,
    val filterOptionsLoading: Boolean = false,
    val filterOptionsError: String? = null,
    val breakdown: PullRequestChangeBreakdown? = null,
    val labelCandidates: PullRequestLabelCandidateList? = null,
    val labelsLoading: Boolean = false,
    val labelsError: String? = null
)

class PullRequestsRepository(
    private val environments: EnvironmentRepository,
    private val credentials: CredentialStore,
    private val auth: EnvironmentAuthClient,
    private val rpc: PullRequestsRpcClient
) {
    private val mutable = MutableStateFlow(PullRequestsSnapshot())
    val state = mutable.asStateFlow()
    private var listRevision = 0L
    private var detailRevision = 0L
    private var filterRevision = 0L
    private var labelsRevision = 0L

    fun selectEnvironment(id: String) {
        if (id == mutable.value.environmentId) return
        listRevision++
        detailRevision++
        mutable.value = PullRequestsSnapshot(environmentId = id)
    }

    fun clear() {
        listRevision++
        detailRevision++
        mutable.value = PullRequestsSnapshot()
    }

    fun configure(filter: String, involvement: String, query: String, filters: PullRequestListFilterState = mutable.value.filters) {
        val current = mutable.value
        if (current.filter != filter || current.involvement != involvement || current.query != query || current.filters != filters) {
            listRevision++
            mutable.value = current.copy(filter = filter, involvement = involvement, query = query, filters = filters, list = null, limit = 50)
        }
    }

    suspend fun refreshes(): Flow<Long> {
        val (environment, token) = connection()
        return rpc.pullRequestRefreshes(environment.baseUrl, token)
    }

    suspend fun invalidate() {
        val (environment, token) = connection()
        val ref = mutable.value.ref
        rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.invalidate", buildJsonObject {
            ref?.let { put("reference", it.payload()) }
        })
    }

    suspend fun list(filter: String, involvement: String, query: String, more: Boolean = false, filters: PullRequestListFilterState = mutable.value.filters) {
        val initial = mutable.value
        val id = initial.environmentId ?: return
        val revision = ++listRevision
        val limit = if (more && initial.list?.nextCursors.isNullOrEmpty()) (initial.limit + 50).coerceAtMost(500) else initial.limit
        mutable.value = initial.copy(filter = filter, involvement = involvement, query = query, filters = filters, loading = true, listError = null, limit = limit,
            list = initial.list.takeIf { more || (initial.filter == filter && initial.involvement == involvement && initial.query == query && initial.filters == filters) })
        try {
            val (environment, token) = connection(id)
            val result = T3Json.decodeFromJsonElement<PullRequestListResult>(rpc.pullRequestCall(
                environment.baseUrl, token, "pullRequests.list", buildJsonObject {
                    put("state", filter)
                    put("involvement", involvement)
                    filters.toJson()?.let { put("filters", it) }
                    put("limit", limit)
                    query.trim().takeIf { it.isNotEmpty() }?.let { put("query", it.take(200)) }
                    if (more) initial.list?.nextCursors?.takeIf { it.isNotEmpty() }?.let {
                        put("cursors", JsonObject(it.mapValues { entry -> JsonPrimitive(entry.value) }))
                    }
                }
            ))
            if (revision == listRevision && id == mutable.value.environmentId) {
                val entries = if (more && initial.list?.nextCursors?.isNotEmpty() == true) (initial.list.entries + result.entries)
                    .distinctBy { it.ref().key }.sortedByDescending { it.updatedAt } else result.entries
                val (stats, missing) = mutable.value.stats.withListed(entries)
                mutable.value = mutable.value.copy(list = result.copy(entries = entries), loading = false, stats = stats)
                if (missing.isNotEmpty()) loadStats(id, revision, missing)
            }
        } catch (cause: CancellationException) { throw cause
        } catch (cause: Exception) {
            if (revision == listRevision) mutable.value = mutable.value.copy(loading = false, listError = failure(cause))
        }
    }

    /** GitHub listings omit line counts; they arrive in a second read so rows show first. */
    private suspend fun loadStats(id: String, revision: Long, entries: List<PullRequestEntry>) {
        try {
            val (environment, token) = connection(id)
            val result = T3Json.decodeFromJsonElement<PullRequestListStatsResult>(rpc.pullRequestCall(
                environment.baseUrl, token, "pullRequests.listStats", buildJsonObject {
                    put("refs", JsonArray(entries.map { it.ref().payload() }))
                }
            ))
            if (revision == listRevision && id == mutable.value.environmentId) {
                mutable.value = mutable.value.copy(stats = mutable.value.stats.withAnswered(result.stats))
            }
        } catch (cause: CancellationException) { throw cause
        } catch (_: Exception) { /* Rows keep their listed counts when stats are unavailable. */ }
    }

    /** Searches the host for labels or authors no loaded row carries. */
    suspend fun filterOptions(kind: String, query: String) {
        val id = mutable.value.environmentId ?: return
        val revision = ++filterRevision
        mutable.value = mutable.value.copy(filterOptionsLoading = true, filterOptionsError = null)
        try {
            val (environment, token) = connection(id)
            val result = T3Json.decodeFromJsonElement<PullRequestFilterOptions>(rpc.pullRequestCall(
                environment.baseUrl, token, "pullRequests.filterOptions", buildJsonObject {
                    put("kind", kind)
                    query.trim().takeIf { it.isNotEmpty() }?.let { put("query", it.take(100)) }
                }
            ))
            if (revision == filterRevision && id == mutable.value.environmentId) {
                mutable.value = mutable.value.copy(filterOptions = result, filterOptionsLoading = false)
            }
        } catch (cause: CancellationException) { throw cause
        } catch (cause: Exception) {
            if (revision == filterRevision) mutable.value = mutable.value.copy(filterOptionsLoading = false, filterOptionsError = failure(cause))
        }
    }

    fun clearFilterOptions() {
        filterRevision++
        mutable.value = mutable.value.copy(filterOptions = null, filterOptionsLoading = false, filterOptionsError = null)
    }

    suspend fun open(ref: PullRequestRef) = coroutineScope {
        val id = mutable.value.environmentId ?: return@coroutineScope
        val revision = ++detailRevision
        var pinned = ref
        val previous = mutable.value.takeIf { it.ref?.key == ref.key }
        mutable.value = mutable.value.copy(ref = pinned, detail = previous?.detail, activity = previous?.activity, threads = previous?.threads.orEmpty(), patch = "",
            diffLoaded = false, nextDiffCursor = null, diffTruncated = false, detailLoading = true, detailError = null,
            activityError = null, diffError = null, actionError = null, canWrite = previous?.canWrite == true, breakdown = previous?.breakdown,
            labelCandidates = previous?.labelCandidates, labelsError = null)
        try {
            val (environment, token) = connection(id)
            val session = async { auth.session(environment.baseUrl, token) }
            val routing = rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.routing", ref.payload()).jsonObject
            pinned = ref.copy(expectedAccountId = routing.getValue("accountId").jsonPrimitive.content)
            if (revision != detailRevision) return@coroutineScope
            mutable.value = mutable.value.copy(ref = pinned)
            val detail = T3Json.decodeFromJsonElement<PullRequestDetail>(rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.detail", pinned.payload()))
            val canWrite = session.await().grants("source-control:write")
            if (revision != detailRevision) return@coroutineScope
            mutable.value = mutable.value.copy(detail = detail, detailLoading = false, canWrite = canWrite)
            val activity = async {
                try {
                    val result = T3Json.decodeFromJsonElement<PullRequestActivity>(rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.activity", pinned.payload()))
                    if (revision == detailRevision) mutable.value = mutable.value.copy(activity = result)
                } catch (cause: CancellationException) { throw cause
                } catch (cause: Exception) { if (revision == detailRevision) mutable.value = mutable.value.copy(activityError = failure(cause)) }
            }
            val threads = async {
                try {
                    val result = T3Json.decodeFromJsonElement<PullRequestLinkedThreads>(rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.linkedThreads", pinned.payload()))
                    if (revision == detailRevision) mutable.value = mutable.value.copy(threads = result.threads)
                } catch (cause: CancellationException) { throw cause
                } catch (_: Exception) { /* Linked conversations do not block the host detail. */ }
            }
            val breakdown = async {
                try {
                    val result = T3Json.decodeFromJsonElement<PullRequestChangeBreakdown?>(rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.changeBreakdown", pinned.payload()))
                    if (revision == detailRevision) mutable.value = mutable.value.copy(breakdown = result)
                } catch (cause: CancellationException) { throw cause
                } catch (_: Exception) { /* The split is optional beside the totals. */ }
            }
            activity.await()
            threads.await()
            breakdown.await()
        } catch (cause: CancellationException) { throw cause
        } catch (cause: Exception) {
            if (revision == detailRevision) mutable.value = mutable.value.copy(detailLoading = false, detailError = failure(cause))
        }
    }

    fun close() {
        detailRevision++
        labelsRevision++
        mutable.value = mutable.value.copy(ref = null, detail = null, activity = null, threads = emptyList(), actionError = null,
            breakdown = null, labelCandidates = null, labelsLoading = false, labelsError = null)
    }

    /** Re-reads only the checks of the open change request; false when the host reports none. */
    suspend fun refreshChecks(): Boolean {
        val initial = mutable.value
        val ref = initial.ref ?: return false
        if (initial.detail == null) return true
        val revision = detailRevision
        val (environment, token) = connection()
        val checks = T3Json.decodeFromJsonElement<PullRequestChecks?>(rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.checks", ref.payload()))
            ?: return false
        if (revision == detailRevision && ref.key == mutable.value.ref?.key) {
            mutable.value = mutable.value.copy(detail = resolveDetailChecks(mutable.value.detail, checks, checksAreNewer = true))
        }
        return true
    }

    suspend fun labelCandidates() {
        val ref = mutable.value.ref ?: return
        val revision = ++labelsRevision
        mutable.value = mutable.value.copy(labelsLoading = true, labelsError = null)
        try {
            val (environment, token) = connection()
            val result = T3Json.decodeFromJsonElement<PullRequestLabelCandidateList>(rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.labelCandidates", ref.payload()))
            if (revision == labelsRevision && ref.key == mutable.value.ref?.key) mutable.value = mutable.value.copy(labelCandidates = result, labelsLoading = false)
        } catch (cause: CancellationException) { throw cause
        } catch (cause: Exception) {
            if (revision == labelsRevision) mutable.value = mutable.value.copy(labelsLoading = false, labelsError = failure(cause))
        }
    }

    internal fun updateActivity(transform: (PullRequestActivity) -> PullRequestActivity) {
        mutable.value.activity?.let { mutable.value = mutable.value.copy(activity = transform(it)) }
    }

    suspend fun diff(more: Boolean = false) {
        val initial = mutable.value
        val ref = initial.ref ?: return
        val revision = detailRevision
        try {
            val (environment, token) = connection()
            val result = T3Json.decodeFromJsonElement<PullRequestDiff>(rpc.pullRequestDiff(environment.baseUrl, token,
                ref.payload { if (more) initial.nextDiffCursor?.let { put("cursor", it) } }))
            if (revision == detailRevision) mutable.value = mutable.value.copy(
                patch = (if (more) initial.patch else "") + result.patch, diffTruncated = result.truncated,
                nextDiffCursor = result.nextCursor, diffLoaded = true, diffError = null)
        } catch (cause: CancellationException) { throw cause
        } catch (cause: Exception) {
            if (revision == detailRevision) mutable.value = mutable.value.copy(diffLoaded = true, diffError = failure(cause))
        }
    }

    internal suspend fun connection(id: String? = mutable.value.environmentId): Pair<SavedEnvironment, String> {
        val environment = requireNotNull(environments.environments.value.firstOrNull { it.id == id }) { "Select a connected environment." }
        val token = credentials.read(environment.id) ?: throw T3TransportException.Authentication(401)
        return environment to token
    }

    internal suspend fun mutate(method: String, payload: JsonObject): Boolean {
        check(!mutable.value.writing) { "An action is already in progress." }
        check(mutable.value.canWrite) { "Pair this environment again with source-control:write permission." }
        val id = mutable.value.environmentId
        val ref = mutable.value.ref ?: return false
        var completed = false
        mutable.value = mutable.value.copy(writing = true, actionError = null)
        try {
            val (environment, token) = connection(id)
            rpc.pullRequestCall(environment.baseUrl, token, method, payload)
            completed = true
            rpc.pullRequestCall(environment.baseUrl, token, "pullRequests.invalidate", buildJsonObject { put("reference", ref.payload()) })
            if (id == mutable.value.environmentId && ref == mutable.value.ref) open(ref)
        } catch (cause: CancellationException) { throw cause
        } catch (cause: Exception) {
            if (id == mutable.value.environmentId) mutable.value = mutable.value.copy(actionError = if (completed) "The action completed, but refreshing failed. Refresh before taking another action." else failure(cause))
        } finally {
            if (id == mutable.value.environmentId) mutable.value = mutable.value.copy(writing = false)
        }
        return completed
    }
}

internal fun PullRequestRef.payload(fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("projectId", projectId)
    put("repository", repository)
    put("number", number)
    put("host", host)
    expectedAccountId?.let { put("expectedAccountId", it) }
    fields()
}

internal fun failure(cause: Exception): String = when (cause) {
    is T3TransportException -> cause.message ?: "T3 request failed."
    else -> "Pull requests could not be loaded. Check the environment connection and source-control sign-in."
}

class PullRequestsService(private val repository: PullRequestsRepository) {
    suspend fun comment(body: String): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(detail.capabilities.comment && detail.viewerPermissions.comment)
        require(body.isNotBlank() && body.length <= 65_536)
        return repository.mutate("pullRequests.comment", requireNotNull(state.ref).payload { put("body", body) })
    }

    suspend fun review(verdict: String, body: String): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(verdict in detail.capabilities.review.verdicts && verdict in detail.viewerPermissions.verdicts)
        require(body.length <= 65_536)
        return repository.mutate("pullRequests.submitReview", requireNotNull(state.ref).payload {
            put("verdict", verdict); put("body", body); put("comments", JsonArray(emptyList()))
        })
    }

    suspend fun action(action: String, method: String? = null): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(action in detail.capabilities.actions && action in detail.viewerPermissions.actions)
        if (action == "merge" || action == "enable-auto-merge") {
            check(method in detail.capabilities.mergeMethods)
            check(when (method) { "merge" -> detail.mergeCapabilities.merge; "squash" -> detail.mergeCapabilities.squash; "rebase" -> detail.mergeCapabilities.rebase; else -> false })
        }
        if (action == "update-branch") check(method in detail.capabilities.updateMethods && method in detail.viewerPermissions.updateMethods)
        return repository.mutate("pullRequests.runAction", requireNotNull(state.ref).payload {
            put("action", action)
            if (action == "merge" || action == "enable-auto-merge") method?.let { put("mergeMethod", it) }
            if (action == "update-branch") method?.let { put("updateMethod", it) }
        })
    }

    suspend fun resolve(threadId: String, resolved: Boolean): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(detail.capabilities.review.resolve && detail.viewerPermissions.resolve)
        check(state.activity?.reviewThreads?.firstOrNull { it.id == threadId }?.canResolve != false)
        return repository.mutate("pullRequests.setThreadResolution", requireNotNull(state.ref).payload { put("threadId", threadId); put("resolved", resolved) })
    }

    suspend fun reply(threadId: String, body: String): Boolean {
        val state = repository.state.value
        check(requireNotNull(state.detail).capabilities.review.reply && state.detail.viewerPermissions.comment)
        require(body.isNotBlank() && body.length <= 65_536)
        return repository.mutate("pullRequests.replyToThread", requireNotNull(state.ref).payload { put("threadId", threadId); put("body", body) })
    }

    /** Sends only the fields that changed; the host keeps the rest. */
    suspend fun update(title: String, body: String): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(detail.canEditChangeRequest())
        val nextTitle = title.trim().takeIf { it != detail.title }
        val nextBody = body.takeIf { it != detail.body }
        require(nextTitle == null || (nextTitle.isNotEmpty() && nextTitle.length <= 1024))
        require(nextBody == null || nextBody.length <= 65_536)
        if (nextTitle == null && nextBody == null) return true
        return repository.mutate("pullRequests.update", requireNotNull(state.ref).payload {
            nextTitle?.let { put("title", it) }
            nextBody?.let { put("body", it) }
        })
    }

    suspend fun updateComment(commentId: String, body: String): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        val activity = requireNotNull(state.activity)
        val comment = activity.comments.firstOrNull { it.id == commentId }
        val threadComment = activity.reviewThreads.flatMap { it.comments }.firstOrNull { it.id == commentId }
        val kind = comment?.kind ?: if (threadComment != null) "review-comment" else null
        requireNotNull(kind)
        check(detail.canEditComment(kind, comment?.author?.login ?: threadComment?.author?.login, comment?.canEdit ?: threadComment?.canEdit))
        require(body.isNotBlank() && body.length <= 65_536)
        return repository.mutate("pullRequests.updateComment", requireNotNull(state.ref).payload {
            put("commentId", commentId); put("kind", kind); put("body", body)
        })
    }

    /** A null subject reacts to the change request itself. The tap shows before the host answers. */
    suspend fun react(subjectId: String?, content: String, reacted: Boolean): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(detail.capabilities.reactions && content in PullRequestReactionOrder)
        val previous = state.activity
        repository.updateActivity { it.withViewerReaction(subjectId, content, reacted) }
        val completed = try {
            repository.mutate("pullRequests.setReaction", requireNotNull(state.ref).payload {
                subjectId?.let { put("subjectId", it) }
                put("content", content); put("reacted", reacted)
            })
        } catch (cause: Exception) {
            previous?.let { activity -> repository.updateActivity { activity } }
            throw cause
        }
        if (!completed) previous?.let { activity -> repository.updateActivity { activity } }
        return completed
    }

    suspend fun setLabel(name: String, applied: Boolean): Boolean {
        val state = repository.state.value
        val detail = requireNotNull(state.detail)
        check(detail.canManageLabels())
        require(name.isNotBlank())
        val completed = repository.mutate("pullRequests.setLabels", requireNotNull(state.ref).payload {
            put("labels", JsonArray(listOf(JsonPrimitive(name)))); put("applied", applied)
        })
        repository.labelCandidates()
        return completed
    }
}
