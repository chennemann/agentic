package de.chennemann.agentic.domain.pullrequests

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.*
import de.chennemann.agentic.domain.environment.*
import de.chennemann.agentic.t3.contract.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PullRequestsRepositoryTest {
    @Test
    fun `pagination keeps host identities distinct and sends opaque cursors`() = runTest {
        val rpc = FakePullRequestsRpc()
        val repository = repository(rpc)
        repository.list("open", "all", "")
        rpc.list = listJson("enterprise.example", "page-two", false)
        repository.list("open", "all", "", more = true)
        assertEquals(2, repository.state.value.list?.entries?.size)
        assertEquals("page-one", rpc.calls.last().second["cursors"]?.jsonObject?.get("github.com owner/repo")?.jsonPrimitive?.content)
    }

    @Test
    fun `switching environments discards a late response from the previous environment`() = runTest {
        val rpc = FakePullRequestsRpc()
        val repository = repository(rpc)
        val pending = CompletableDeferred<JsonElement>()
        rpc.pendingList = pending
        val old = async { repository.list("open", "all", "") }
        kotlinx.coroutines.yield()
        repository.selectEnvironment("two")
        rpc.pendingList = null
        repository.list("merged", "all", "new")
        pending.complete(listJson("wrong.example", "", false))
        old.await()
        assertEquals("two", repository.state.value.environmentId)
        assertEquals("github.com", repository.state.value.list?.entries?.single()?.host)
        assertEquals("merged", repository.state.value.filter)
    }

    @Test
    fun `detail pins the routed account id rather than the viewer login and survives activity failure`() = runTest {
        val rpc = FakePullRequestsRpc().apply { activityFails = true }
        val repository = repository(rpc)
        repository.list("open", "all", "")
        repository.open(ref)
        assertEquals("Review this change", repository.state.value.detail?.title)
        assertEquals("account-42", rpc.calls.first { it.first == "pullRequests.detail" }.second["expectedAccountId"]?.jsonPrimitive?.content)
        assertNotNull(repository.state.value.activityError)
        assertTrue(repository.state.value.canWrite)
    }

    @Test
    fun `reviews need both the host capability and the viewer grant`() = runTest {
        val rpc = FakePullRequestsRpc()
        val repository = repository(rpc)
        repository.open(ref)
        val result = runCatching { PullRequestsService(repository).review("request-changes", "Needs work") }
        assertTrue(result.isFailure)
        assertFalse(rpc.calls.any { it.first == "pullRequests.submitReview" })
    }

    @Test
    fun `failed comment is reported without sending an invalidation or claiming success`() = runTest {
        val rpc = FakePullRequestsRpc().apply { commentFails = true }
        val repository = repository(rpc)
        repository.open(ref)
        assertFalse(PullRequestsService(repository).comment("Please check this"))
        assertNotNull(repository.state.value.actionError)
        assertFalse(repository.state.value.writing)
        assertFalse(rpc.calls.any { it.first == "pullRequests.invalidate" })
    }

    @Test
    fun `successful comment is not treated as failed when the subsequent refresh fails`() = runTest {
        val rpc = FakePullRequestsRpc().apply { invalidateFails = true }
        val repository = repository(rpc)
        repository.open(ref)
        assertTrue(PullRequestsService(repository).comment("Looks good"))
        assertTrue(repository.state.value.actionError?.startsWith("The action completed") == true)
        assertEquals(1, rpc.calls.count { it.first == "pullRequests.comment" })
    }

    @Test
    fun `write permission is not inferred from orchestration operate`() = runTest {
        val rpc = FakePullRequestsRpc()
        val repository = repository(rpc, write = false)
        repository.open(ref)
        assertFalse(repository.state.value.canWrite)
        assertTrue(runCatching { PullRequestsService(repository).comment("hello") }.isFailure)
        assertFalse(rpc.calls.any { it.first == "pullRequests.comment" })
    }

    @Test
    fun `list filters are sent to the host and rows without counts read their stats`() = runTest {
        val rpc = FakePullRequestsRpc().apply { list = listJson("github.com", "", false, additions = 0) }
        val repository = repository(rpc)
        repository.list("open", "all", "  fix ", filters = PullRequestListFilterState(labels = listOf("bug"), author = "me"))
        val input = rpc.calls.first { it.first == "pullRequests.list" }.second
        assertEquals("fix", input["query"]?.jsonPrimitive?.content)
        assertEquals("bug", input["filters"]?.jsonObject?.get("labels")?.jsonArray?.single()?.jsonArray?.single()?.jsonPrimitive?.content)
        val stats = rpc.calls.single { it.first == "pullRequests.listStats" }.second
        assertEquals(42, stats["refs"]?.jsonArray?.single()?.jsonObject?.get("number")?.jsonPrimitive?.int)
        val entry = repository.state.value.list!!.entries.single()
        assertEquals(7, repository.state.value.stats.fill(listOf(entry)).single().additions)
    }

    @Test
    fun `a pushed row refreshes its diff stats on the next listing`() = runTest {
        val rpc = FakePullRequestsRpc().apply { list = listJson("github.com", "", false, additions = 0) }
        val repository = repository(rpc)
        repository.list("open", "all", "")
        repository.list("open", "all", "")
        assertEquals(1, rpc.calls.count { it.first == "pullRequests.listStats" })
        rpc.list = listJson("github.com", "", false, additions = 0, updatedAt = "2026-10-20T10:00:00Z")
        repository.list("open", "all", "")
        assertEquals(2, rpc.calls.count { it.first == "pullRequests.listStats" })
    }

    @Test
    fun `live checks replace the detail checks until the host reports none`() = runTest {
        val rpc = FakePullRequestsRpc()
        val repository = repository(rpc)
        repository.open(ref)
        rpc.checks = json("""{"state":"open","checks":[{"name":"ci","status":"success","description":null,"url":null}]}""")
        assertTrue(repository.refreshChecks())
        assertEquals("success", repository.state.value.detail?.checks?.single()?.status)
        assertEquals("account-42", rpc.calls.last { it.first == "pullRequests.checks" }.second["expectedAccountId"]?.jsonPrimitive?.content)
        rpc.checks = JsonNull
        assertFalse(repository.refreshChecks())
    }

    @Test
    fun `editing a change request sends only the changed fields`() = runTest {
        val rpc = FakePullRequestsRpc().apply { detail = writableDetailJson }
        val repository = repository(rpc)
        repository.open(ref)
        assertTrue(PullRequestsService(repository).update("Review this change", "New description"))
        val input = rpc.calls.single { it.first == "pullRequests.update" }.second
        assertNull(input["title"])
        assertEquals("New description", input["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun `comment edits name the comment kind and labels change one at a time`() = runTest {
        val rpc = FakePullRequestsRpc().apply { detail = writableDetailJson }
        val repository = repository(rpc)
        repository.open(ref)
        assertTrue(PullRequestsService(repository).updateComment("r1", "Updated"))
        assertEquals("review-comment", rpc.calls.single { it.first == "pullRequests.updateComment" }.second["kind"]?.jsonPrimitive?.content)
        assertTrue(PullRequestsService(repository).setLabel("bug", applied = true))
        val labels = rpc.calls.single { it.first == "pullRequests.setLabels" }.second
        assertEquals("bug", labels["labels"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals(true, labels["applied"]?.jsonPrimitive?.boolean)
        assertTrue(repository.state.value.labelCandidates?.candidates?.single()?.isApplied == true)
    }

    @Test
    fun `a failed reaction puts the host's reactions back`() = runTest {
        val rpc = FakePullRequestsRpc().apply { detail = writableDetailJson; reactionFails = true }
        val repository = repository(rpc)
        repository.open(ref)
        assertFalse(PullRequestsService(repository).react(null, "heart", reacted = true))
        assertEquals(listOf(1), repository.state.value.activity?.reactions?.map { it.count })
        assertNull(rpc.calls.single { it.first == "pullRequests.setReaction" }.second["subjectId"])
    }

    private fun repository(rpc: FakePullRequestsRpc, write: Boolean = true) = PullRequestsRepository(
        object : EnvironmentRepository {
            override val environments = MutableStateFlow(listOf(environment("one"), environment("two")))
            override val activeEnvironment = MutableStateFlow(environment("one"))
            override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
            override suspend fun select(environmentId: String) = Unit
            override suspend fun remove(environmentId: String) = Unit
            override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
        },
        object : CredentialStore {
            override suspend fun read(environmentId: String) = "access-token"
            override suspend fun write(environmentId: String, bearerToken: String) = Unit
            override suspend fun remove(environmentId: String) = Unit
        },
        object : EnvironmentAuthClient {
            override suspend fun exchangeToken(baseUrl: String, bootstrapCredential: String, metadata: AndroidClientMetadata): TokenExchangeResponse = error("Unused")
            override suspend fun session(baseUrl: String, bearerToken: String) = AuthSession(true,
                ServerAuthDescriptor("remote", emptyList(), emptyList(), "session"), scopes = listOf("orchestration:operate"),
                permissions = if (write) listOf("source-control:write") else emptyList())
        }, rpc
    ).apply { selectEnvironment("one") }

    private fun environment(id: String) = SavedEnvironment(id, id, "https://$id.example/", "linux", "x64", "1", id == "one", null)
}

private val ref = PullRequestRef("project", "owner/repo", 42, "github.com")
private fun json(value: String) = T3Json.parseToJsonElement(value)
private fun listJson(host: String, cursor: String, truncated: Boolean = true, additions: Int = 2, updatedAt: String = "2026-10-19T10:00:00Z") = json("""{
  "viewers":{"github.com":"viewer-login"},"providers":[{"host":"$host","configured":true,"searchesOnHost":true}],
  "entries":[{"host":"$host","projectId":"project","projectTitle":"Project","repository":"owner/repo","number":42,
  "title":"Review this change","url":"https://$host/owner/repo/pull/42","headBranch":"feature","baseBranch":"main","state":"open",
  "isDraft":false,"mergeability":"mergeable","additions":$additions,"deletions":${if (additions == 0) 0 else 1},"updatedAt":"$updatedAt"}],
  "errors":[],"truncated":$truncated,"nextCursors":{"$host owner/repo":"$cursor"}}
""")
private val writableDetailJson = json("""{
  "provider":"github","capabilities":{"diff":true,"comment":true,"actions":[],"mergeMethods":[],"search":true,"reactions":true,"labels":true,
  "edit":{"changeRequest":true,"comment":true},"review":{"inlineComment":true,"reply":true,"resolve":true,"verdicts":[]},
  "reviewers":{"request":false,"listCandidates":false}},
  "viewerPermissions":{"actions":[],"comment":true,"resolve":true,"verdicts":[],"requestReviewers":false},
  "projectId":"project","projectTitle":"Project","repository":"owner/repo","number":42,"title":"Review this change","body":"Description",
  "url":"https://github.com/owner/repo/pull/42","author":{"login":"viewer-login","name":null,"avatarUrl":null},"state":"open","isDraft":false,
  "mergeability":"mergeable","additions":2,"deletions":1,"changedFiles":1,"headBranch":"feature","baseBranch":"main","checks":[],"labels":[],
  "reviewers":[],"mergeCapabilities":{"merge":false,"squash":true,"rebase":false},"viewer":"viewer-login"}
""")
private val activityJson = json("""{"comments":[],"commentCount":0,"commentsTruncated":false,
  "reviewThreads":[{"id":"t1","path":"a.kt","line":1,"side":"right","isResolved":false,"isOutdated":false,
  "comments":[{"id":"r1","author":{"login":"viewer-login","name":null,"avatarUrl":null},"body":"Old","createdAt":"2026-10-19T10:00:00Z","url":null}]}],
  "commits":[],"reactions":[{"content":"heart","count":1,"actors":["sam"],"viewerHasReacted":false}]}""")
private val detailJson = json("""{
  "capabilities":{"diff":true,"comment":true,"actions":["merge","close"],"mergeMethods":["squash"],
  "review":{"inlineComment":true,"reply":true,"resolve":true,"verdicts":["approve","request-changes"]}},
  "viewerPermissions":{"actions":["close"],"comment":true,"resolve":true,"verdicts":["approve"]},
  "projectId":"project","projectTitle":"Project","repository":"owner/repo","number":42,"title":"Review this change","body":"Description",
  "url":"https://github.com/owner/repo/pull/42","state":"open","isDraft":false,"mergeability":"mergeable","additions":2,"deletions":1,
  "changedFiles":1,"headBranch":"feature","baseBranch":"main","checks":[],"labels":[],"reviewers":[],
  "mergeCapabilities":{"merge":false,"squash":true,"rebase":false}}
""")
private class FakePullRequestsRpc : PullRequestsRpcClient {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var list = listJson("github.com", "page-one")
    var pendingList: CompletableDeferred<JsonElement>? = null
    var activityFails = false
    var commentFails = false
    var invalidateFails = false
    var reactionFails = false
    var detail = detailJson
    var checks: JsonElement = JsonNull
    private var labelApplied = false
    override fun pullRequestRefreshes(baseUrl: String, token: String) = emptyFlow<Long>()
    override suspend fun pullRequestDiff(baseUrl: String, token: String, input: JsonObject) = json("""{"patch":"","truncated":false,"nextCursor":null}""")
    override suspend fun pullRequestCall(baseUrl: String, token: String, method: String, input: JsonObject): JsonElement {
        calls += method to input
        return when (method) {
            "pullRequests.list" -> pendingList?.await() ?: list
            "pullRequests.listStats" -> json("""{"stats":[{"projectId":"project","repository":"owner/repo","number":42,"additions":7,"deletions":3}]}""")
            "pullRequests.routing" -> json("""{"accountId":"account-42"}""")
            "pullRequests.detail" -> detail
            "pullRequests.checks" -> checks
            "pullRequests.linkedThreads" -> json("""{"threads":[]}""")
            "pullRequests.labelCandidates" -> json("""{"candidates":[{"name":"bug","color":null,"description":null,"isApplied":$labelApplied}],"truncated":false}""")
            "pullRequests.setLabels" -> { labelApplied = input["applied"]!!.jsonPrimitive.boolean; JsonNull }
            "pullRequests.setReaction" -> { check(!reactionFails); JsonNull }
            "pullRequests.activity" -> { check(!activityFails); activityJson }
            "pullRequests.comment" -> { check(!commentFails); JsonNull }
            "pullRequests.invalidate" -> { check(!invalidateFails); assertNotNull(input["reference"]); JsonNull }
            else -> JsonNull
        }
    }
}
