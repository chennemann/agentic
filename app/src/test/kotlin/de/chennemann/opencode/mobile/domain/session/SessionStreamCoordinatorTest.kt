package de.chennemann.opencode.mobile.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionStreamCoordinatorTest {
    private class StubLog : LogGateway {
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

    private class StubConn : ConnectionGateway {
        override val status: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected("1")).asStateFlow()
        override val endpoint: StateFlow<String> = MutableStateFlow("http://localhost").asStateFlow()
        override val found: StateFlow<String?> = MutableStateFlow(null).asStateFlow()

        override fun start(scope: CoroutineScope) {}

        override suspend fun setUrl(next: String) {}

        override suspend fun refresh(loading: Boolean) {}
    }

    private class StubNet : ConnectivityGateway {
        private val onlineState = MutableStateFlow(true)
        private val changedState = MutableStateFlow(0L)

        override val online: StateFlow<Boolean> = onlineState.asStateFlow()
        override val changed: StateFlow<Long> = changedState.asStateFlow()

        fun markChanged() {
            changedState.value += 1
        }
    }

    @Test
    fun retriesOnlyAfterNetworkChangeAndDelay() = runTest {
        val net = StubNet()
        val feed = object : StreamGateway {
            var calls = 0

            override suspend fun streamEvents(
                lastEventId: String?,
                onRawEvent: suspend (String) -> Unit,
                onEvent: suspend (SessionStreamEvent) -> Unit,
            ): String? {
                calls += 1
                if (calls == 1) throw IllegalStateException("first failure")
                awaitCancellation()
            }

            override suspend fun streamCursor(): String? = null

            override suspend fun setStreamCursor(value: String?) {}
        }
        val coordinator = SessionStreamCoordinator(
            conn = StubConn(),
            feed = feed,
            net = net,
            log = StubLog(),
        )
        val job = coordinator.start(backgroundScope) {}

        runCurrent()
        assertEquals(1, feed.calls)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, feed.calls)

        net.markChanged()
        runCurrent()
        assertEquals(1, feed.calls)

        advanceTimeBy(3000)
        runCurrent()
        assertEquals(2, feed.calls)

        job.cancel()
    }

    @Test
    fun passesInitialCursorAndPersistsUpdatedCursorForRetry() = runTest {
        val net = StubNet()
        val cursors = mutableListOf<String?>()
        val stored = mutableListOf<String?>()
        val feed = object : StreamGateway {
            var calls = 0

            override suspend fun streamEvents(
                lastEventId: String?,
                onRawEvent: suspend (String) -> Unit,
                onEvent: suspend (SessionStreamEvent) -> Unit,
            ): String? {
                calls += 1
                cursors += lastEventId
                if (calls == 1) {
                    onEvent(event("e1"))
                    throw IllegalStateException("stream failed")
                }
                awaitCancellation()
            }

            override suspend fun streamCursor(): String? = "seed"

            override suspend fun setStreamCursor(value: String?) {
                stored += value
            }
        }
        val coordinator = SessionStreamCoordinator(
            conn = StubConn(),
            feed = feed,
            net = net,
            log = StubLog(),
        )
        val job = coordinator.start(backgroundScope) {}

        runCurrent()
        assertEquals(listOf("seed"), cursors)
        assertEquals(listOf("e1"), stored)

        net.markChanged()
        runCurrent()
        advanceTimeBy(3000)
        runCurrent()

        assertEquals(listOf("seed", "e1"), cursors)

        job.cancel()
    }

    @Test
    fun persistsCursorBeforeEventCallbackAndPreservesOrder() = runTest {
        val order = mutableListOf<String>()
        val feed = object : StreamGateway {
            override suspend fun streamEvents(
                lastEventId: String?,
                onRawEvent: suspend (String) -> Unit,
                onEvent: suspend (SessionStreamEvent) -> Unit,
            ): String? {
                onRawEvent("raw")
                onEvent(event("e1"))
                onEvent(event(null))
                awaitCancellation()
            }

            override suspend fun streamCursor(): String? = null

            override suspend fun setStreamCursor(value: String?) {
                order += "persist:$value"
            }
        }
        val coordinator = SessionStreamCoordinator(
            conn = StubConn(),
            feed = feed,
            net = StubNet(),
            log = StubLog(),
        )
        val job = coordinator.start(backgroundScope) { event ->
            order += "callback:${event.id}"
        }

        runCurrent()
        assertEquals(listOf("persist:e1", "callback:e1", "callback:null"), order)

        job.cancel()
    }

    private fun event(id: String?): SessionStreamEvent {
        return SessionStreamEvent(
            directory = "repo",
            type = "server.heartbeat",
            properties = JsonObject(emptyMap()),
            id = id,
            retry = null,
        )
    }
}
