package de.chennemann.agentic.data.v2

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import de.chennemann.agentic.di.DispatcherProvider
import de.chennemann.agentic.domain.v2.message.LocalMessage
import de.chennemann.agentic.domain.v2.message.LocalMessageRole
import de.chennemann.agentic.domain.v2.message.LocalMessageToolCall
import de.chennemann.agentic.domain.v2.message.LocalToolCallStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun upsertMessageWithToolCallsPersistsAndMapsSessionReadModel() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightMessageRepository(db(), lanes(main, worker))
        val message = LocalMessage(
            id = "msg-1:0",
            serverUrl = "https://example.test",
            sessionId = "session-1",
            remoteId = "msg-1",
            stepIndex = 0,
            role = LocalMessageRole.ASSISTANT,
            sortKey = "r-0001",
            text = "Answer",
            createdAt = 10,
            completedAt = 20,
        )
        val firstCall = LocalMessageToolCall(
            id = "call-a",
            messageId = message.id,
            toolName = "bash",
            title = "Shell",
            status = LocalToolCallStatus.COMPLETED,
            target = "ls",
            startedAt = 20,
            completedAt = 25,
        )
        val secondCall = LocalMessageToolCall(
            id = "call-b",
            messageId = message.id,
            toolName = "read",
            title = "Read",
            status = LocalToolCallStatus.COMPLETED,
            target = "README.md",
            startedAt = 30,
            completedAt = 35,
        )

        val write = async {
            repository.upsertMessageWithToolCalls(message, listOf(secondCall, firstCall))
        }
        advanceUntilIdle()
        write.await()

        val read = async {
            repository.observeMessagesWithToolCalls(
                serverUrl = "https://example.test",
                sessionId = "session-1",
            ).first()
        }
        advanceUntilIdle()
        val rows = read.await()

        assertEquals(1, rows.size)
        assertEquals(message, rows.single().message)
        assertEquals(
            listOf("call-a", "call-b"),
            rows.single().toolCalls.map { it.id },
        )
    }

    @Test
    fun deleteMessagesBySessionOnlyRemovesTargetScope() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val worker = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightMessageRepository(db(), lanes(main, worker))
        val server = "https://example.test"
        val messageA = LocalMessage(
            id = "msg-a:0",
            serverUrl = server,
            sessionId = "session-a",
            remoteId = "msg-a",
            stepIndex = 0,
            role = LocalMessageRole.ASSISTANT,
            sortKey = "r-0001",
            text = "A",
            completedAt = 20,
        )
        val messageB = LocalMessage(
            id = "msg-b:0",
            serverUrl = server,
            sessionId = "session-b",
            remoteId = "msg-b",
            stepIndex = 0,
            role = LocalMessageRole.USER,
            sortKey = "r-0001",
            text = "B",
            completedAt = 40,
        )

        val seed = async {
            repository.upsertMessageWithToolCalls(
                messageA,
                listOf(
                    LocalMessageToolCall(
                        id = "call-a",
                        messageId = messageA.id,
                        toolName = "bash",
                        title = "Shell",
                    ),
                ),
            )
            repository.upsertMessageWithToolCalls(
                messageB,
                listOf(
                    LocalMessageToolCall(
                        id = "call-b",
                        messageId = messageB.id,
                        toolName = "read",
                        title = "Read",
                    ),
                ),
            )
        }
        advanceUntilIdle()
        seed.await()

        val delete = async {
            repository.deleteMessagesBySession(server, "session-a")
        }
        advanceUntilIdle()
        delete.await()

        val readA = async {
            repository.observeMessagesWithToolCalls(server, "session-a").first()
        }
        val readB = async {
            repository.observeMessagesWithToolCalls(server, "session-b").first()
        }
        advanceUntilIdle()

        assertTrue(readA.await().isEmpty())
        val remaining = readB.await()
        assertEquals(1, remaining.size)
        assertEquals("msg-b:0", remaining.single().message.id)
        assertEquals(listOf("call-b"), remaining.single().toolCalls.map { it.id })
    }

    private fun db(): AgenticDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.synchronous().create(driver)
        return AgenticDb(driver)
    }

    private fun lanes(main: TestDispatcher, worker: TestDispatcher): DispatcherProvider {
        return object : DispatcherProvider {
            override val io = worker
            override val default = worker
            override val mainImmediate = main
        }
    }
}
