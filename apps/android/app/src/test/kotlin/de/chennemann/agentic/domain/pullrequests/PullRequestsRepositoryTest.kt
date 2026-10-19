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
private fun listJson(host: String, cursor: String, truncated: Boolean = true) = json("""{
  "viewers":{"github.com":"viewer-login"},"providers":[{"host":"$host","configured":true,"searchesOnHost":true}],
  "entries":[{"host":"$host","projectId":"project","projectTitle":"Project","repository":"owner/repo","number":42,
  "title":"Review this change","url":"https://$host/owner/repo/pull/42","headBranch":"feature","baseBranch":"main","state":"open",
  "isDraft":false,"mergeability":"mergeable","additions":2,"deletions":1,"updatedAt":"2026-10-19T10:00:00Z"}],
  "errors":[],"truncated":$truncated,"nextCursors":{"$host owner/repo":"$cursor"}}
""")
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
    override fun pullRequestRefreshes(baseUrl: String, token: String) = emptyFlow<Long>()
    override suspend fun pullRequestDiff(baseUrl: String, token: String, input: JsonObject) = json("""{"patch":"","truncated":false,"nextCursor":null}""")
    override suspend fun pullRequestCall(baseUrl: String, token: String, method: String, input: JsonObject): JsonElement {
        calls += method to input
        return when (method) {
            "pullRequests.list" -> pendingList?.await() ?: list
            "pullRequests.routing" -> json("""{"accountId":"account-42"}""")
            "pullRequests.detail" -> detailJson
            "pullRequests.linkedThreads" -> json("""{"threads":[]}""")
            "pullRequests.activity" -> { check(!activityFails); json("""{"comments":[],"commentCount":0,"commentsTruncated":false,"reviewThreads":[],"commits":[]}""") }
            "pullRequests.comment" -> { check(!commentFails); JsonNull }
            "pullRequests.invalidate" -> { check(!invalidateFails); assertNotNull(input["reference"]); JsonNull }
            else -> JsonNull
        }
    }
}
