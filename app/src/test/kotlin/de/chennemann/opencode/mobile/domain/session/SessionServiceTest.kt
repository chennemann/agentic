package de.chennemann.opencode.mobile.domain.session

import de.chennemann.opencode.mobile.di.CoroutineRolloutFlag
import de.chennemann.opencode.mobile.di.DispatcherProvider
import de.chennemann.opencode.mobile.domain.message.MessageDecorator
import de.chennemann.opencode.mobile.domain.message.MessagePartParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionServiceTest {
    private val fixtures = mutableListOf<Fixture>()

    @AfterEach
    fun tearDown() {
        fixtures.forEach { it.close() }
        fixtures.clear()
    }

    @Test
    fun startIsIdempotentAndLoadsInitialProjectSessionsAndCommands() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/alpha", name = "Alpha"))
        fx.proj.sessionsByDir["/repo/alpha"] = listOf(summary(id = "s1", directory = "/repo/alpha", updatedAt = 11))
        fx.cmd.commandsByDir["/repo/alpha"] = listOf(CommandState(name = "build"))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.proj.projectCalls > 0 }
        await { fx.service.state.value.selectedProject != null }
        await { fx.service.state.value.sessions.isNotEmpty() }
        await { fx.service.state.value.commands.isNotEmpty() }

        val state = fx.service.state.value
        assertEquals(1, fx.conn.startCalls)
        assertTrue(fx.proj.projectCalls >= 1)
        assertEquals("/repo/alpha", state.selectedProject)
        assertEquals(listOf("s1"), state.sessions.map { it.id })
        assertEquals(listOf("build"), state.commands.map { it.name })
        val calls = fx.proj.projectCalls

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.proj.projectCalls > calls }

        assertEquals(1, fx.conn.startCalls)
        assertEquals(calls + 1, fx.proj.projectCalls)
        fx.close()
    }

    @Test
    fun selectProjectLoadsWorkspaceAndSandboxSessionsExcludingArchived() = runTest {
        val fx = fixture(testScope = this)
        try {
            fx.proj.projects = listOf(
                project(id = "p1", worktree = "/repo/main", name = "Main", sandboxes = listOf("/repo/main-sb")),
                project(id = "p2", worktree = "/repo/other", name = "Other"),
            )
            fx.proj.sessionsByDir["/repo/main"] = listOf(
                summary(id = "a", directory = "/repo/main", updatedAt = 100),
                summary(id = "archived-main", directory = "/repo/main", archivedAt = 1, updatedAt = 90),
            )
            fx.proj.sessionsByDir["/repo/main-sb"] = listOf(
                summary(id = "b", directory = "/repo/main-sb", updatedAt = 200),
                summary(id = "archived-sb", directory = "/repo/main-sb", archivedAt = 1, updatedAt = 190),
            )
            fx.proj.sessionsByDir["/repo/other"] = listOf(
                summary(id = "o1", directory = "/repo/other", updatedAt = 300),
                summary(id = "o-arch", directory = "/repo/other", archivedAt = 1, updatedAt = 280),
            )

            fx.service.start(fx.scope)
            fx.service.loadProjects()
            await { fx.service.state.value.sessions.isNotEmpty() }

            assertEquals("/repo/main", fx.service.state.value.selectedProject)
            assertEquals(listOf("b", "a"), fx.service.state.value.sessions.map { it.id })

            assertEquals(listOf("o1"), fx.service.sessionsForProject("/repo/other").map { it.id })
        } finally {
            fx.close()
        }
    }

    @Test
    fun sendCommandHandlesSuccessAndFailure() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(summary(id = "s1", directory = "/repo/main", updatedAt = 10))
        fx.cmd.commandsByDir["/repo/main"] = listOf(CommandState(name = "build"))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }
        val session = fx.service.state.value.sessions.first()
        fx.service.openSession(session)
        drain()

        fx.service.send("/build --fast", "agent")
        await { fx.msg.commandCalls.size == 1 }
        assertEquals(1, fx.msg.commandCalls.size)
        assertEquals("build", fx.msg.commandCalls.first().name)
        assertEquals("--fast", fx.msg.commandCalls.first().arguments)

        fx.msg.sendCommandError = IllegalStateException("command failed")
        fx.service.send("/build --bad", "agent")
        await { fx.service.state.value.message == "command failed" }

        assertEquals("command failed", fx.service.state.value.message)
        fx.close()
    }

    @Test
    fun sendMessageOptimisticAddRollsBackOnFailure() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(summary(id = "s1", directory = "/repo/main", updatedAt = 10))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }
        val session = fx.service.state.value.sessions.first()
        fx.service.openSession(session)
        drain()

        val gate = CompletableDeferred<Unit>()
        fx.msg.sendMessageGate = gate
        fx.msg.sendMessageError = IllegalStateException("send failed")

        fx.service.send("hello world", "agent")
        await { fx.service.state.value.focusedMessages.isNotEmpty() }

        val optimistic = fx.service.state.value.focusedMessages
        assertEquals(1, optimistic.size)
        assertTrue(optimistic.first().id.startsWith("local-"))
        assertEquals("hello world", optimistic.first().text)

        gate.complete(Unit)
        await {
            fx.service.state.value.message == "send failed" &&
                fx.service.state.value.focusedMessages.none { it.id.startsWith("local-") }
        }

        assertTrue(fx.service.state.value.focusedMessages.none { it.id.startsWith("local-") })
        assertEquals("send failed", fx.service.state.value.message)
        fx.close()
    }

    @Test
    fun newSessionIsDeferredUntilFirstMessageSend() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.selectedProject == "/repo/main" }

        assertTrue(fx.service.createSessionAndFocus("/repo/main"))
        await { fx.service.state.value.selectedProject == "/repo/main" }

        assertNull(fx.service.state.value.focusedSession)
        assertTrue(fx.proj.createCalls.isEmpty())

        fx.service.send("hello from mobile", "agent")
        await { fx.msg.messageCalls.isNotEmpty() }

        assertEquals(1, fx.proj.createCalls.size)
        assertEquals("hello from mobile", fx.proj.createCalls.first().second)
        assertEquals("new-session", fx.msg.messageCalls.first().sessionId)
        assertEquals("hello from mobile", fx.msg.messageCalls.first().text)
        fx.close()
    }

    @Test
    fun slashNewClearsFocusWithoutCreatingSession() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(summary(id = "s1", directory = "/repo/main", updatedAt = 10))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }
        fx.service.openSession(fx.service.state.value.sessions.first())
        await { fx.service.state.value.focusedSession != null }

        fx.service.send("/new", "agent")
        await { fx.service.state.value.focusedSession == null }

        assertTrue(fx.proj.createCalls.isEmpty())
        assertTrue(fx.msg.messageCalls.isEmpty())
        fx.close()
    }

    @Test
    fun quickPinRollbackOnCacheFailure() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(summary(id = "s1", directory = "/repo/main", updatedAt = 10))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }
        val session = fx.service.state.value.sessions.first()

        val gate = CompletableDeferred<Unit>()
        fx.cache.quickPinGate = gate
        fx.cache.quickPinError = IllegalStateException("pin failed")

        fx.service.toggleSessionQuickPin(session, systemPinned = false)
        await { fx.service.state.value.quickPinInclude.contains("s1") }
        assertEquals(setOf("s1"), fx.service.state.value.quickPinInclude)

        gate.complete(Unit)
        await { fx.service.state.value.quickPinInclude.isEmpty() }

        assertTrue(fx.service.state.value.quickPinInclude.isEmpty())
        assertTrue(fx.service.state.value.quickPinExclude.isEmpty())
        assertEquals("pin failed", fx.service.state.value.message)
        fx.close()
    }

    @Test
    fun archiveFailureRestoresSessionsByReloading() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(
            summary(id = "s1", directory = "/repo/main", updatedAt = 50),
            summary(id = "s2", directory = "/repo/main", updatedAt = 40),
        )

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }
        val target = fx.service.state.value.sessions.first { it.id == "s1" }

        val gate = CompletableDeferred<Unit>()
        fx.proj.archiveGate = gate
        fx.proj.archiveError = IllegalStateException("archive failed")

        fx.service.archiveSession(target)
        await { fx.service.state.value.sessions.none { it.id == "s1" } }
        assertFalse(fx.service.state.value.sessions.any { it.id == "s1" })

        gate.complete(Unit)
        await { fx.service.state.value.sessions.any { it.id == "s1" } }

        assertTrue(fx.service.state.value.sessions.any { it.id == "s1" })
        assertTrue(fx.service.state.value.message == null)
        fx.close()
    }

    @Test
    fun streamEventsUpdateSessionStatusAndMessages() = runTest {
        val fx = fixture(testScope = this)
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(summary(id = "s1", directory = "/repo/main", updatedAt = 10))

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }

        val focused = fx.service.state.value.sessions.first()
        fx.service.openSession(focused)
        drain()

        fx.stream.emit(
            event(
                type = "message.updated",
                directory = "/repo/main",
                properties = buildJsonObject {
                    put("sessionID", focused.id)
                    put("id", "m1")
                    put("role", "assistant")
                    put("text", "hello from stream")
                    put("time", buildJsonObject {
                        put("created", "1")
                        put("completed", "2")
                    })
                },
            )
        )
        await { fx.service.state.value.focusedMessages.any { it.id == "m1" } }

        val message = fx.service.state.value.focusedMessages.firstOrNull { it.id == "m1" }
        assertNotNull(message)
        assertEquals("hello from stream", message?.text)

        fx.stream.emit(
            event(
                type = "session.status",
                directory = "/repo/main",
                properties = buildJsonObject {
                    put("sessionID", focused.id)
                    put("status", buildJsonObject { put("type", "busy") })
                },
            )
        )
        await { fx.service.state.value.quickProcessing.contains(focused.id) }
        assertTrue(fx.service.state.value.quickProcessing.contains(focused.id))

        fx.stream.emit(
            event(
                type = "session.idle",
                directory = "/repo/main",
                properties = buildJsonObject { put("sessionID", focused.id) },
            )
        )
        await { !fx.service.state.value.quickProcessing.contains(focused.id) }
        assertFalse(fx.service.state.value.quickProcessing.contains(focused.id))
        fx.close()
    }

    @Test
    fun syncStatusClearsStaleProcessingWhenSessionIsMissingFromStatusMap() = runTest {
        val fx = fixture(testScope = this)
        fx.conn.connect()
        fx.proj.projects = listOf(project(id = "p1", worktree = "/repo/main", name = "Main"))
        fx.proj.sessionsByDir["/repo/main"] = listOf(summary(id = "s1", directory = "/repo/main", updatedAt = 10))
        fx.msg.statusByDirectory["/repo/main"] = mapOf("s1" to "busy")

        fx.service.start(fx.scope)
        fx.service.loadProjects()
        await { fx.service.state.value.sessions.isNotEmpty() }

        val focused = fx.service.state.value.sessions.first()
        fx.service.openSession(focused)
        await { fx.msg.statusCalls.isNotEmpty() }

        fx.stream.emit(
            event(
                type = "session.status",
                directory = "/repo/main",
                properties = buildJsonObject {
                    put("sessionID", focused.id)
                    put("status", buildJsonObject { put("type", "busy") })
                },
            )
        )
        await { fx.service.state.value.quickProcessing.contains(focused.id) }

        fx.msg.statusByDirectory["/repo/main"] = emptyMap()
        val calls = fx.msg.statusCalls.size
        fx.service.focusSession(focused.id)

        await {
            fx.msg.statusCalls.size > calls &&
                !fx.service.state.value.quickProcessing.contains(focused.id)
        }
        assertFalse(fx.service.state.value.quickProcessing.contains(focused.id))
        fx.close()
    }

    private suspend fun drain() {
        repeat(20) { yield() }
    }

    private suspend fun await(check: () -> Boolean) {
        withTimeout(2000) {
            while (!check()) {
                yield()
            }
        }
    }

    private fun fixture(testScope: TestScope): Fixture {
        val conn = FakeConn()
        val proj = FakeProj()
        val cmd = FakeCmd()
        val msg = FakeMsg()
        val cache = FakeCache()
        val log = FakeLog()
        val stream = FakeStream()
        val net = FakeNet()
        val io = Dispatchers.Default
        val cpu = Dispatchers.Default
        val lanes = object : DispatcherProvider {
            override val io = io
            override val default = cpu
            override val mainImmediate = cpu
        }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val service = SessionService(
            conn = conn,
            proj = proj,
            cmd = cmd,
            msg = msg,
            cache = cache,
            log = log,
            parser = MessagePartParser(),
            decorator = MessageDecorator(),
            projector = FocusedMessageProjector(MessageDecorator()),
            planner = SessionSyncPlanner(),
            reducer = SessionEventReducer(),
            streamer = SessionStreamCoordinator(conn, stream, net, log),
            reconciler = ReconcileCoordinator(interval = 60_000),
            dispatchers = lanes,
            rollout = object : CoroutineRolloutFlag {
                override val useMigratedExecution = false
            },
        )
        return Fixture(conn, proj, cmd, msg, cache, stream, service, scope).also { fixtures += it }
    }

    private fun event(type: String, directory: String, properties: JsonObject): SessionStreamEvent {
        return SessionStreamEvent(
            directory = directory,
            type = type,
            properties = properties,
            id = "evt-${System.nanoTime()}",
            retry = null,
        )
    }

    private fun project(id: String, worktree: String, name: String, sandboxes: List<String> = emptyList()): SessionProject {
        return SessionProject(id = id, worktree = worktree, name = name, sandboxes = sandboxes)
    }

    private fun summary(
        id: String,
        directory: String,
        updatedAt: Long,
        archivedAt: Long? = null,
    ): SessionSummary {
        return SessionSummary(
            id = id,
            title = id,
            version = "1",
            directory = directory,
            updatedAt = updatedAt,
            archivedAt = archivedAt,
        )
    }

    private data class Fixture(
        val conn: FakeConn,
        val proj: FakeProj,
        val cmd: FakeCmd,
        val msg: FakeMsg,
        val cache: FakeCache,
        val stream: FakeStream,
        val service: SessionService,
        val scope: CoroutineScope,
    ) {
        fun close() {
            service.stop()
            scope.cancel()
        }
    }

    private class FakeConn : ConnectionGateway {
        private val statusFlow = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        private val endpointFlow = MutableStateFlow("http://localhost")
        private val foundFlow = MutableStateFlow<String?>(null)
        var startCalls = 0

        override val status: StateFlow<ConnectionState> = statusFlow.asStateFlow()
        override val endpoint: StateFlow<String> = endpointFlow.asStateFlow()
        override val found: StateFlow<String?> = foundFlow.asStateFlow()

        override fun start(scope: CoroutineScope) {
            startCalls += 1
        }

        override suspend fun setUrl(next: String) {
            endpointFlow.value = next
        }

        override suspend fun refresh(loading: Boolean) = Unit

        fun connect() {
            statusFlow.value = ConnectionState.Connected("1")
        }
    }

    private class FakeProj : ProjectGateway {
        var projects = emptyList<SessionProject>()
        var projectCalls = 0
        val sessionsByDir = linkedMapOf<String, List<SessionSummary>>()
        val createCalls = mutableListOf<Pair<String, String>>()
        var archiveError: Throwable? = null
        var archiveGate: CompletableDeferred<Unit>? = null

        override suspend fun projects(): List<SessionProject> {
            projectCalls += 1
            return projects
        }

        override suspend fun sessions(worktree: String, limit: Int?): List<SessionSummary> {
            val values = sessionsByDir[worktree].orEmpty()
            return if (limit == null) values else values.take(limit)
        }

        override suspend fun archiveSession(sessionId: String, directory: String) {
            archiveGate?.await()
            archiveError?.let { throw it }
        }

        override suspend fun renameSession(sessionId: String, directory: String, title: String) = Unit

        override suspend fun createSession(worktree: String, title: String): SessionSummary {
            createCalls += worktree to title
            return SessionSummary(
                id = "new-session",
                title = title,
                version = "1",
                directory = worktree,
                updatedAt = 0,
            )
        }
    }

    private class FakeCmd : CommandGateway {
        val commandsByDir = linkedMapOf<String, List<CommandState>>()

        override suspend fun commands(directory: String): List<CommandState> {
            return commandsByDir[directory].orEmpty()
        }
    }

    private class FakeMsg : MessageGateway {
        data class Call(
            val sessionId: String,
            val directory: String,
            val name: String,
            val arguments: String,
            val agent: String,
        )

        data class Send(
            val sessionId: String,
            val directory: String,
            val text: String,
            val agent: String,
        )

        val messagesBySession = linkedMapOf<String, List<SessionMessage>>()
        val updatedAtBySession = linkedMapOf<String, Long?>()
        val statusByDirectory = linkedMapOf<String, Map<String, String>>()
        val statusCalls = mutableListOf<String>()
        val messagesCalls = mutableListOf<Pair<String, Int?>>()
        val commandCalls = mutableListOf<Call>()
        val messageCalls = mutableListOf<Send>()
        var sendCommandError: Throwable? = null
        var sendMessageError: Throwable? = null
        var sendMessageGate: CompletableDeferred<Unit>? = null

        override suspend fun messages(sessionId: String, directory: String, limit: Int?): List<SessionMessage> {
            messagesCalls += sessionId to limit
            val values = messagesBySession[sessionId].orEmpty()
            return if (limit == null) values else values.take(limit)
        }

        override suspend fun updatedAt(sessionId: String, directory: String): Long? {
            return updatedAtBySession[sessionId]
        }

        override suspend fun status(directory: String): Map<String, String> {
            statusCalls += directory
            return statusByDirectory[directory].orEmpty()
        }

        override suspend fun sendMessage(sessionId: String, directory: String, text: String, agent: String) {
            messageCalls += Send(sessionId, directory, text, agent)
            sendMessageGate?.await()
            sendMessageError?.let { throw it }
        }

        override suspend fun sendCommand(sessionId: String, directory: String, name: String, arguments: String, agent: String) {
            commandCalls += Call(sessionId, directory, name, arguments, agent)
            sendCommandError?.let { throw it }
        }
    }

    private class FakeCache : SessionCacheGateway {
        private val favorites = linkedMapOf<String, MutableSet<String>>()
        private val hidden = linkedMapOf<String, MutableSet<String>>()
        private val projectSessions = linkedMapOf<Pair<String, String>, List<SessionState>>()
        private val messageFlows = linkedMapOf<Pair<String, String>, MutableStateFlow<List<MessageState>>>()
        var quickPins = SessionQuickPinCache()
        var quickPinError: Throwable? = null
        var quickPinGate: CompletableDeferred<Unit>? = null

        override suspend fun upsertSession(server: String, project: String?, session: SessionState) = Unit

        override suspend fun upsertSessionSnapshot(server: String, project: String?, session: SessionState) = Unit

        override suspend fun syncProjectSessions(server: String, project: String, sessions: List<SessionState>) {
            projectSessions[server to project] = sessions
        }

        override suspend fun listProjectSessions(server: String, project: String, limit: Int?): List<SessionState> {
            val values = projectSessions[server to project].orEmpty()
            return if (limit == null) values else values.take(limit)
        }

        override suspend fun deleteSession(server: String, sessionId: String) = Unit

        override fun recentSession(): RecentSessionCache? = null

        override fun projectFavorites(server: String): Set<String> {
            return favorites[server].orEmpty()
        }

        override suspend fun setProjectFavorite(server: String, worktree: String, favorite: Boolean) {
            val set = favorites.getOrPut(server) { linkedSetOf() }
            if (favorite) {
                set += worktree
                return
            }
            set -= worktree
        }

        override fun hiddenProjects(server: String): Set<String> {
            return hidden[server].orEmpty()
        }

        override suspend fun setProjectHidden(server: String, worktree: String, hidden: Boolean) {
            val set = this.hidden.getOrPut(server) { linkedSetOf() }
            if (hidden) {
                set += worktree
                return
            }
            set -= worktree
        }

        override fun sessionQuickPins(server: String): SessionQuickPinCache {
            return quickPins
        }

        override suspend fun setSessionQuickPins(server: String, include: Set<String>, exclude: Set<String>) {
            quickPinGate?.await()
            quickPinError?.let { throw it }
            quickPins = SessionQuickPinCache(include = include, exclude = exclude)
        }

        override suspend fun listMessages(server: String, sessionId: String): List<MessageState> {
            return messageFlows[server to sessionId]?.value.orEmpty()
        }

        override fun observeMessages(server: String, sessionId: String): Flow<List<MessageState>> {
            return messageFlows.getOrPut(server to sessionId) { MutableStateFlow(emptyList()) }
        }

        override suspend fun upsertMessage(server: String, sessionId: String, message: MessageState, updatedAt: Long) {
            val flow = messageFlows.getOrPut(server to sessionId) { MutableStateFlow(emptyList()) }
            flow.value = flow.value
                .filterNot { it.id == message.id }
                .plus(message)
                .sortedBy { it.sort }
        }

        override suspend fun deleteMessage(server: String, sessionId: String, messageId: String) {
            val flow = messageFlows.getOrPut(server to sessionId) { MutableStateFlow(emptyList()) }
            flow.value = flow.value.filterNot { it.id == messageId }
        }

        override suspend fun deleteSessionMessages(server: String, sessionId: String) {
            messageFlows[server to sessionId]?.value = emptyList()
        }
    }

    private class FakeLog : LogGateway {
        override fun log(
            level: LogLevel,
            unit: LogUnit,
            tag: String,
            event: String,
            message: String,
            context: Map<String, String>,
            error: Throwable?,
        ) {
        }
    }

    private class FakeNet : ConnectivityGateway {
        private val onlineFlow = MutableStateFlow(true)
        private val changedFlow = MutableStateFlow(0L)
        override val online: StateFlow<Boolean> = onlineFlow.asStateFlow()
        override val changed: StateFlow<Long> = changedFlow.asStateFlow()
    }

    private class FakeStream : StreamGateway {
        private var sink: (suspend (SessionStreamEvent) -> Unit)? = null

        override suspend fun streamEvents(
            lastEventId: String?,
            onRawEvent: suspend (String) -> Unit,
            onEvent: suspend (SessionStreamEvent) -> Unit,
        ): String? {
            sink = onEvent
            awaitCancellation()
        }

        override suspend fun streamCursor(): String? = null

        override suspend fun setStreamCursor(value: String?) = Unit

        suspend fun emit(event: SessionStreamEvent) {
            val callback = sink
            assertNotNull(callback)
            callback?.invoke(event)
        }
    }
}
