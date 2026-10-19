package de.chennemann.agentic.ui.pullrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.pullrequests.PullRequestsRepository
import de.chennemann.agentic.domain.pullrequests.PullRequestsService
import de.chennemann.agentic.t3.contract.PullRequestDetail
import de.chennemann.agentic.ui.review.ReviewDocument
import de.chennemann.agentic.ui.review.parseReviewDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// UI values contain no wire payloads or transport clients.
data class PrEnvironmentUi(val id: String, val label: String)
data class PrRowUi(val id: String, val title: String, val subtitle: String, val status: String, val labels: String)
data class PrCheckUi(val name: String, val status: String, val description: String, val url: String?)
data class PrCommentUi(val id: String, val author: String, val body: String, val context: String)
data class PrThreadUi(val id: String, val path: String, val comments: List<PrCommentUi>, val resolved: Boolean, val canResolve: Boolean, val canReply: Boolean, val truncated: Boolean)
data class PrLinkedThreadUi(val id: String, val title: String)
data class PrActionUi(val id: String, val label: String, val action: String, val method: String? = null)
data class PrDetailUi(
    val title: String, val subtitle: String, val body: String, val url: String, val status: String,
    val checks: List<PrCheckUi>, val labels: String, val reviewers: String,
    val actions: List<PrActionUi>, val verdicts: List<String>, val canComment: Boolean, val canDiff: Boolean
)
data class PullRequestsUiState(
    val environments: List<PrEnvironmentUi> = emptyList(), val environmentId: String? = null,
    val filter: String = "open", val involvement: String = "all", val query: String = "",
    val rows: List<PrRowUi> = emptyList(), val loading: Boolean = false, val listError: String? = null,
    val warnings: List<String> = emptyList(), val more: Boolean = false,
    val selectedId: String? = null, val detail: PrDetailUi? = null,
    val detailLoading: Boolean = false, val detailError: String? = null,
    val comments: List<PrCommentUi> = emptyList(), val threads: List<PrThreadUi> = emptyList(),
    val commits: List<String> = emptyList(), val activityError: String? = null, val activityLoaded: Boolean = false,
    val activityTruncated: Boolean = false, val linkedThreads: List<PrLinkedThreadUi> = emptyList(),
    val document: ReviewDocument = ReviewDocument(), val diffLoaded: Boolean = false, val diffError: String? = null,
    val moreDiff: Boolean = false, val diffTruncated: Boolean = false,
    val writing: Boolean = false, val canWrite: Boolean = false, val actionError: String? = null, val submitted: Int = 0
)

class PullRequestsViewModel(
    private val environments: EnvironmentRepository,
    private val repository: PullRequestsRepository,
    private val service: PullRequestsService,
    private val conversations: de.chennemann.agentic.domain.pullrequests.PullRequestConversations
) : ViewModel() {
    private val localError = MutableStateFlow<String?>(null)
    private val submitted = MutableStateFlow(0)
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var diffJob: Job? = null
    private var watchJob: Job? = null
    private var actionJob: Job? = null
    val state = combine(repository.state, environments.environments, localError, submitted) { snapshot, environments, error, sent ->
        val detail = snapshot.detail
        val patch = snapshot.patch
        PullRequestsUiState(
            environments = environments.map { PrEnvironmentUi(it.id, it.label) }, environmentId = snapshot.environmentId,
            filter = snapshot.filter, involvement = snapshot.involvement, query = snapshot.query,
            rows = snapshot.list?.entries.orEmpty().filter { entry ->
                val hostSearch = snapshot.list?.providers?.firstOrNull { it.host == entry.host }?.searchesOnHost == true
                hostSearch || snapshot.query.isBlank() || "${entry.title} ${entry.repository} ${entry.author?.login}".contains(snapshot.query, ignoreCase = true)
            }.map { entry -> PrRowUi(entry.ref().key, entry.title,
                "${entry.repository} #${entry.number} - ${entry.author?.login ?: "Unknown author"}",
                listOfNotNull(if (entry.isDraft) "draft" else entry.state, entry.checksState, entry.reviewDecision).joinToString(" / "),
                entry.labels.joinToString { it.name }) },
            loading = snapshot.loading, listError = snapshot.listError,
            warnings = snapshot.list?.providers.orEmpty().filterNot { it.configured }.map { "${it.host}: ${it.detail ?: "Source control is not configured."}" } +
                snapshot.list?.errors.orEmpty().map { "${it.projectTitle}: ${it.message}" },
            more = snapshot.list?.let { it.nextCursors.isNotEmpty() || (it.truncated && snapshot.limit < 500) } == true,
            selectedId = snapshot.ref?.key, detail = detail?.toUi(snapshot.canWrite), detailLoading = snapshot.detailLoading,
            detailError = snapshot.detailError,
            comments = snapshot.activity?.comments.orEmpty().map { PrCommentUi(it.id, it.author?.login ?: "Unknown author", it.body, listOfNotNull(it.reviewState, it.path, it.createdAt).joinToString(" - ")) },
            threads = snapshot.activity?.reviewThreads.orEmpty().map { thread -> PrThreadUi(thread.id,
                "${thread.path}${thread.line?.let { ":$it" }.orEmpty()}${if (thread.isOutdated) " (outdated)" else ""}",
                thread.comments.map { PrCommentUi(it.id, it.author?.login ?: "Unknown author", it.body, "") }, thread.isResolved,
                snapshot.canWrite && detail?.capabilities?.review?.resolve == true && detail.viewerPermissions.resolve && thread.canResolve != false,
                snapshot.canWrite && detail?.capabilities?.review?.reply == true && detail.viewerPermissions.comment,
                thread.nextCommentsCursor != null) },
            commits = snapshot.activity?.commits.orEmpty().map { "${it.oid.take(8)} ${it.messageHeadline}" },
            activityError = snapshot.activityError, activityLoaded = snapshot.activity != null,
            activityTruncated = snapshot.activity?.let { it.commentsTruncated || it.reviewThreadsTruncated } == true,
            linkedThreads = snapshot.threads.filter { it.archivedAt == null }.map { PrLinkedThreadUi(it.id, it.title) },
            document = withContext(Dispatchers.Default) { parseReviewDiff(patch) }, diffLoaded = snapshot.diffLoaded,
            diffError = snapshot.diffError, moreDiff = snapshot.nextDiffCursor != null, diffTruncated = snapshot.diffTruncated,
            writing = snapshot.writing, canWrite = snapshot.canWrite,
            actionError = error ?: snapshot.actionError, submitted = sent
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PullRequestsUiState())

    init {
        viewModelScope.launch {
            environments.environments.collect { saved ->
                if (saved.isEmpty()) {
                    listJob?.cancel(); detailJob?.cancel(); diffJob?.cancel(); watchJob?.cancel()
                    repository.clear()
                    return@collect
                }
                val selected = repository.state.value.environmentId
                if (saved.none { it.id == selected }) saved.firstOrNull { it.active }?.let { selectEnvironment(it.id) }
                    ?: saved.firstOrNull()?.let { selectEnvironment(it.id) }
                else if (repository.state.value.list == null && !repository.state.value.loading) reload()
            }
        }
        repository.state.value.environmentId?.let { reload(); startWatch() }
    }

    fun selectEnvironment(id: String) {
        if (repository.state.value.writing) return
        detailJob?.cancel(); diffJob?.cancel(); listJob?.cancel()
        repository.selectEnvironment(id)
        localError.value = null
        reload()
        startWatch()
    }

    private fun startWatch() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                try {
                    repository.refreshes().distinctUntilChanged().drop(1).collect {
                        if (!repository.state.value.writing) reload()
                    }
                    delay(5_000)
                } catch (cause: CancellationException) { throw cause
                } catch (_: Exception) { delay(5_000) }
            }
        }
    }

    fun filter(value: String) = list(value, repository.state.value.involvement, repository.state.value.query)
    fun involvement(value: String) = list(repository.state.value.filter, value, repository.state.value.query)
    fun search(value: String) = list(repository.state.value.filter, repository.state.value.involvement, value, debounce = true)
    private fun list(filter: String, involvement: String, query: String, more: Boolean = false, debounce: Boolean = false) {
        listJob?.cancel()
        repository.configure(filter, involvement, query)
        listJob = viewModelScope.launch { if (debounce) delay(250); repository.list(filter, involvement, query, more) }
    }
    fun more() { val s = repository.state.value; list(s.filter, s.involvement, s.query, more = true) }
    fun refresh() {
        localError.value = null
        viewModelScope.launch {
            try { repository.invalidate(); reload()
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The refresh could not be completed. Check the environment connection." }
        }
    }
    private fun reload() {
        diffJob?.cancel()
        val s = repository.state.value
        list(s.filter, s.involvement, s.query)
        s.ref?.let { ref -> detailJob?.cancel(); detailJob = viewModelScope.launch { repository.open(ref) } }
    }
    fun open(id: String) {
        val ref = repository.state.value.list?.entries?.firstOrNull { it.ref().key == id }?.ref() ?: return
        localError.value = null
        detailJob?.cancel(); diffJob?.cancel()
        detailJob = viewModelScope.launch { repository.open(ref) }
    }
    fun back() { detailJob?.cancel(); diffJob?.cancel(); repository.close(); reload() }
    fun changes(more: Boolean = false) { diffJob?.cancel(); diffJob = viewModelScope.launch { repository.diff(more) } }
    fun comment(body: String) = act(clearDraft = true) { service.comment(body) }
    fun review(verdict: String, body: String) = act(clearDraft = true) { service.review(verdict, body) }
    fun action(id: String) {
        val option = state.value.detail?.actions?.firstOrNull { it.id == id } ?: return
        act { service.action(option.action, option.method) }
    }
    fun resolve(id: String, resolved: Boolean) = act { service.resolve(id, resolved) }
    fun reply(id: String, body: String) = act(clearDraft = true) { service.reply(id, body) }
    private fun act(clearDraft: Boolean = false, block: suspend () -> Boolean) {
        if (actionJob?.isActive == true) return
        localError.value = null
        actionJob = viewModelScope.launch {
            try { val completed = block(); if (clearDraft && completed) submitted.value++
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The action could not be completed. Refresh and check your permissions." }
        }
    }
    fun discuss(onOpened: () -> Unit) {
        viewModelScope.launch {
            try {
                val snapshot = repository.state.value
                val environmentId = snapshot.environmentId ?: return@launch
                val ref = snapshot.ref ?: return@launch
                val detail = snapshot.detail ?: return@launch
                conversations.discuss(environmentId, ref.projectId, detail.url)
                onOpened()
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The conversation draft could not be prepared." }
        }
    }
    fun openThread(id: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            try {
                val environmentId = repository.state.value.environmentId ?: return@launch
                val thread = repository.state.value.threads.firstOrNull { it.id == id } ?: return@launch
                conversations.open(environmentId, thread.projectId, thread.id)
                onOpened()
            } catch (cause: CancellationException) { throw cause
            } catch (_: Exception) { localError.value = "The linked conversation could not be opened." }
        }
    }
}

internal fun PullRequestDetail.toUi(canWrite: Boolean): PrDetailUi {
    val actions = if (!canWrite) emptyList() else capabilities.actions.intersect(viewerPermissions.actions.toSet()).flatMap { action ->
        when (action) {
            "merge", "enable-auto-merge" -> if (state != "open" || isDraft) emptyList() else capabilities.mergeMethods.filter {
                when (it) { "merge" -> mergeCapabilities.merge; "squash" -> mergeCapabilities.squash; "rebase" -> mergeCapabilities.rebase; else -> false }
            }.map { PrActionUi("$action:$it", "${if (action == "merge") "Merge" else "Auto-merge"} ($it)", action, it) }
            "update-branch" -> if (state != "open") emptyList() else capabilities.updateMethods.intersect(viewerPermissions.updateMethods.toSet()).map { PrActionUi("$action:$it", "Update branch ($it)", action, it) }
            "ready" -> if (isDraft && state == "open") listOf(PrActionUi(action, "Mark ready for review", action)) else emptyList()
            "draft" -> if (!isDraft && state == "open") listOf(PrActionUi(action, "Convert to draft", action)) else emptyList()
            "close" -> if (state == "open") listOf(PrActionUi(action, "Close pull request", action)) else emptyList()
            "reopen" -> if (state == "closed") listOf(PrActionUi(action, "Reopen pull request", action)) else emptyList()
            "disable-auto-merge" -> if (autoMergeEnabled == true) listOf(PrActionUi(action, "Disable auto-merge", action)) else emptyList()
            "delete-branch" -> if (state == "merged" || state == "closed") listOf(PrActionUi(action, "Delete branch", action)) else emptyList()
            "revert" -> if (state == "merged") listOf(PrActionUi(action, "Revert pull request", action)) else emptyList()
            "approve-workflows" -> listOf(PrActionUi(action, "Approve workflows", action))
            else -> emptyList()
        }
    }
    return PrDetailUi(title, "$repository #$number - $headBranch to $baseBranch", body, url,
        "${if (isDraft) "draft" else state} / $mergeability / +$additions -$deletions / $changedFiles files",
        checks.map { PrCheckUi(it.name, it.status, it.description.orEmpty(), it.url) },
        labels.joinToString { it.name }, reviewers.joinToString { it.login }, actions,
        if (canWrite) capabilities.review.verdicts.intersect(viewerPermissions.verdicts.toSet()).toList() else emptyList(),
        canWrite && capabilities.comment && viewerPermissions.comment, capabilities.diff)
}
