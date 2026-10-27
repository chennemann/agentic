package de.chennemann.agentic.ui.search

import de.chennemann.agentic.data.auth.CredentialStore
import de.chennemann.agentic.data.t3.ThreadSearchRpcClient
import de.chennemann.agentic.domain.connection.ConnectionController
import de.chennemann.agentic.domain.connection.ConnectionState
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.SavedEnvironment
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.orchestration.ProjectionSource
import de.chennemann.agentic.domain.orchestration.ProjectionState
import de.chennemann.agentic.domain.orchestration.Reduction
import de.chennemann.agentic.domain.orchestration.ThreadActions
import de.chennemann.agentic.domain.search.ThreadMessageAuthor
import de.chennemann.agentic.domain.search.ThreadMessageMatch
import de.chennemann.agentic.domain.search.ThreadMessageSearch
import de.chennemann.agentic.domain.search.ThreadMessageSearchResult
import de.chennemann.agentic.domain.search.ThreadSearchService
import de.chennemann.agentic.domain.search.canSearchThreadMessages
import de.chennemann.agentic.t3.contract.DispatchResult
import de.chennemann.agentic.t3.contract.ExecutionEnvironmentDescriptor
import de.chennemann.agentic.t3.contract.OrchestrationProject
import de.chennemann.agentic.t3.contract.OrchestrationSearchThreadsResult
import de.chennemann.agentic.t3.contract.OrchestrationShellSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationShellStreamItem
import de.chennemann.agentic.t3.contract.OrchestrationThreadDetailSnapshot
import de.chennemann.agentic.t3.contract.OrchestrationThreadLineage
import de.chennemann.agentic.t3.contract.OrchestrationThreadSearchMatch
import de.chennemann.agentic.t3.contract.OrchestrationThreadShell
import de.chennemann.agentic.t3.contract.OrchestrationThreadStreamItem
import de.chennemann.agentic.t3.contract.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadSearchTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() = Dispatchers.setMain(dispatcher)

    @AfterEach fun cleanup() = Dispatchers.resetMain()

    private val shell = OrchestrationShellSnapshot(
        projects = listOf(project("p1", "Agentic"), project("p2", "Website")),
        threads = listOf(
            thread("exact", "p1", "Search", "2026-10-20T00:00:00Z"),
            thread("prefix", "p1", "Search ranking tweaks", "2026-10-21T00:00:00Z"),
            thread("word", "p2", "Fix thread search", "2026-10-23T00:00:00Z"),
            thread("inside", "p2", "Research notes", "2026-10-24T00:00:00Z"),
            thread("project", "p1", "Unrelated", "2026-10-24T00:00:00Z"),
            thread("archived", "p1", "Search archive", "2026-10-24T00:00:00Z").copy(archivedAt = "2026-10-24T00:00:00Z"),
            thread("child", "p1", "Search subagent", "2026-10-24T00:00:00Z")
                .copy(lineage = OrchestrationThreadLineage("exact", "subagent", "exact")),
            thread("message", "p2", "Deploy pipeline", "2026-10-24T00:00:00Z"),
        ),
        snapshotSequence = 1,
    )

    @Test
    fun `titles rank whole, prefix, word start, then inside a word, without archived threads or sub-agents`() {
        val results = rankThreadTitleMatches("search", shell)
        assertEquals(listOf("exact", "prefix", "word", "inside"), results.map { it.threadId })
        assertEquals(listOf(0..5), results[0].titleHighlights)
        assertEquals(listOf(2..7), results[3].titleHighlights)
    }

    @Test
    fun `every word must match the title or the project name`() {
        assertEquals(listOf("project"), rankThreadTitleMatches("agentic unrelated", shell).map { it.threadId })
        assertTrue(rankThreadTitleMatches("search missing", shell).isEmpty())
    }

    @Test
    fun `message matches skip threads already listed and threads the shell does not show`() {
        val matches = listOf(
            match("word", "the search box"),
            match("message", "we need search in the drawer"),
            match("message", "duplicate hit"),
            match("archived", "search the archive"),
            match("unknown", "search elsewhere"),
        )
        val results = presentMessageMatches("search", matches, shell, setOf("word"))
        assertEquals(listOf("message"), results.map { it.threadId })
        assertEquals("You", results[0].snippet?.author)
        assertEquals(listOf(8..13), results[0].snippet?.highlights)
        assertEquals("Website", results[0].projectLabel)
    }

    @Test
    fun `highlights merge overlapping words`() {
        assertEquals(listOf(0..7), highlightRanges("Research", listOf("research", "search")))
    }

    @Test
    fun `message search needs a word of three letters`() {
        assertFalse(canSearchThreadMessages("a b"))
        assertFalse(canSearchThreadMessages("ab"))
        assertTrue(canSearchThreadMessages("ab cde"))
    }

    @Test
    fun `typing debounces message search and only the settled query reaches the server`() = runTest(dispatcher) {
        val search = FakeSearch { query -> ThreadMessageSearchResult.Matches(listOf(match("message", "$query in a message"))) }
        val vm = ThreadSearchViewModel(FakeRepository(shell), search, FakeThreads(), dispatcher, debounceMillis = 150)
        vm.changeQuery("fai")
        runCurrent()
        advanceTimeBy(100)
        vm.changeQuery("failing")
        advanceUntilIdle()
        assertEquals(listOf("failing"), search.queries)
        assertTrue(vm.state.value.titleResults.isEmpty())
        assertEquals(listOf("message"), vm.state.value.messageResults.map { it.threadId })
        assertEquals("failing in a message", vm.state.value.messageResults.single().snippet?.text)
        assertEquals("failing", search.lastQuery.value)
    }

    @Test
    fun `an unreachable environment keeps title matches and explains the missing messages`() = runTest(dispatcher) {
        val search = FakeSearch { ThreadMessageSearchResult.Unavailable("offline") }
        val vm = ThreadSearchViewModel(FakeRepository(shell), search, FakeThreads(), dispatcher, debounceMillis = 0)
        vm.changeQuery("search")
        advanceUntilIdle()
        assertEquals("offline", vm.state.value.notice)
        assertEquals(4, vm.state.value.titleResults.size)
        assertFalse(vm.state.value.searchingMessages)
    }

    @Test
    fun `search reopens with the last query and opening a result selects the thread`() = runTest(dispatcher) {
        val search = FakeSearch { ThreadMessageSearchResult.Matches(emptyList()) }
        search.remember("deploy")
        val threads = FakeThreads()
        val vm = ThreadSearchViewModel(FakeRepository(shell), search, threads, dispatcher, debounceMillis = 0)
        advanceUntilIdle()
        assertEquals("deploy", vm.state.value.query)
        assertEquals(listOf("message"), vm.state.value.titleResults.map { it.threadId })
        var opened = false
        vm.open("message") { opened = true }
        advanceUntilIdle()
        assertEquals(listOf("message"), threads.selected)
        assertTrue(opened)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `service skips the server while offline and maps server matches when live`() = runTest(dispatcher) {
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Backoff(1_000, "offline"))
        val rpc = FakeRpc()
        val service = ThreadSearchService(FakeEnvironments(), FakeCredentials(), FakeConnection(connection), rpc)
        assertTrue(service.search("pipeline") is ThreadMessageSearchResult.Unavailable)
        assertTrue(rpc.queries.isEmpty())
        connection.value = ConnectionState.Live
        val result = service.search("  pipeline  ") as ThreadMessageSearchResult.Matches
        assertEquals(listOf("pipeline"), rpc.queries)
        assertEquals(ThreadMessageAuthor.AGENT, result.matches.single().author)
        assertEquals("m1", result.matches.single().messageId)
    }

    @Test
    fun `service gives up on a server that does not answer`() = runTest(dispatcher) {
        val rpc = FakeRpc(hang = true)
        val service = ThreadSearchService(FakeEnvironments(), FakeCredentials(), FakeConnection(MutableStateFlow(ConnectionState.Live)), rpc, timeoutMillis = 1_000)
        assertTrue(service.search("pipeline") is ThreadMessageSearchResult.Unavailable)
    }

    private fun project(id: String, title: String) =
        OrchestrationProject(id, title, "/work/$id", createdAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z")

    private fun thread(id: String, projectId: String, title: String, at: String) = OrchestrationThreadShell(
        id = id,
        projectId = projectId,
        title = title,
        createdAt = at,
        updatedAt = at,
        latestUserMessageAt = at,
    )

    private fun match(threadId: String, snippet: String) = ThreadMessageMatch(
        threadId = threadId,
        projectId = "p2",
        author = ThreadMessageAuthor.USER,
        snippet = snippet,
        messageCreatedAt = null,
        messageId = null,
    )
}

private class FakeSearch(private val answer: suspend (String) -> ThreadMessageSearchResult) : ThreadMessageSearch {
    val queries = mutableListOf<String>()
    override val lastQuery = MutableStateFlow("")
    override fun remember(query: String) {
        lastQuery.value = query
    }
    override suspend fun search(query: String): ThreadMessageSearchResult {
        queries += query
        return answer(query)
    }
}

private class FakeThreads : ThreadActions {
    val selected = mutableListOf<String?>()
    override suspend fun selectProject(projectId: String?) = Unit
    override suspend fun selectThread(threadId: String?) {
        selected += threadId
    }
    override suspend fun rename(threadId: String, title: String): DispatchResult = error("unused")
    override suspend fun archive(threadId: String): DispatchResult = error("unused")
    override suspend fun unarchive(threadId: String): DispatchResult = error("unused")
    override suspend fun settle(threadId: String): DispatchResult = error("unused")
    override suspend fun unsettle(threadId: String): DispatchResult = error("unused")
}

private class FakeRepository(shell: OrchestrationShellSnapshot) : OrchestrationRepository {
    override val clientConfig = MutableStateFlow(ProjectionState<ServerConfig>())
    override val shell = MutableStateFlow(ProjectionState(shell, 1, ProjectionSource.LIVE))
    override val focusedThread = MutableStateFlow(ProjectionState<OrchestrationThreadDetailSnapshot>())
    override val focusedThreadId = MutableStateFlow<String?>(null)
    override val selectedProjectId = MutableStateFlow<String?>(null)
    override suspend fun loadCached(environmentId: String) = Unit
    override suspend fun setClientConfig(environmentId: String, config: ServerConfig, source: ProjectionSource) = Unit
    override suspend fun applyShellItem(environmentId: String, item: OrchestrationShellStreamItem) = Reduction.Ignored(shell.value)
    override suspend fun focusThread(environmentId: String, threadId: String?) = Unit
    override suspend fun selectProject(environmentId: String, projectId: String?) = Unit
    override suspend fun applyThreadItem(environmentId: String, threadId: String, item: OrchestrationThreadStreamItem) =
        Reduction.Ignored(focusedThread.value)
    override suspend fun clearEnvironment(environmentId: String) = Unit
}

private val searchEnvironment = SavedEnvironment("env", "Server", "http://localhost", "linux", "x64", "1", true, null)

private class FakeEnvironments : EnvironmentRepository {
    override val environments = MutableStateFlow(listOf(searchEnvironment))
    override val activeEnvironment = MutableStateFlow<SavedEnvironment?>(searchEnvironment)
    override suspend fun save(baseUrl: String, descriptor: ExecutionEnvironmentDescriptor, makeActive: Boolean) = Unit
    override suspend fun select(environmentId: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
    override suspend fun markConnected(environmentId: String, connectedAt: Long) = Unit
}

private class FakeCredentials : CredentialStore {
    override suspend fun read(environmentId: String) = "test-token"
    override suspend fun write(environmentId: String, bearerToken: String) = Unit
    override suspend fun remove(environmentId: String) = Unit
}

private class FakeConnection(override val state: StateFlow<ConnectionState>) : ConnectionController {
    override fun wake() = Unit
    override fun retryPendingCommands() = Unit
}

private class FakeRpc(private val hang: Boolean = false) : ThreadSearchRpcClient {
    val queries = mutableListOf<String>()
    override suspend fun searchThreads(baseUrl: String, bearerToken: String, query: String, limit: Int): OrchestrationSearchThreadsResult {
        queries += query
        if (hang) awaitCancellation()
        return OrchestrationSearchThreadsResult(
            listOf(OrchestrationThreadSearchMatch("message", "p2", "assistant", "the pipeline failed", messageId = "m1")),
        )
    }
}
