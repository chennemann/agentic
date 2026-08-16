package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.TerminalSummary
import de.chennemann.agentic.t3.contract.ProjectScript
import de.chennemann.agentic.t3.contract.ProjectScriptIcon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalSessionServiceTest {
    @Test
    fun `primary running terminal wins provider-neutral selection`() {
        val terminals = listOf(
            terminal("vendor-shell", "running"),
            terminal("term-1", "running"),
            terminal("term-2", "exited"),
        )

        assertEquals("term-1", preferredTerminal(terminals))
    }

    @Test
    fun `first running terminal recovers when primary is unavailable`() {
        val terminals = listOf(terminal("term-1", "exited"), terminal("term-3", "running"))

        assertEquals("term-3", preferredTerminal(terminals))
    }

    @Test
    fun `new session fills first available bounded convention id`() {
        assertEquals("term-2", nextTerminalId(listOf(terminal("term-1", "running"), terminal("term-3", "running"))))
    }

    @Test
    fun `advertised script reuses primary terminal when no session is live`() {
        val launch = projectScriptLaunch(
            terminals = listOf(terminal("term-1", "exited"), terminal("term-2", "error")),
            script = ProjectScript("dev", "Dev", "pnpm dev", ProjectScriptIcon.DEBUG, false),
        )

        assertEquals("term-1", launch.terminalId)
        assertEquals(true, launch.restart)
        assertEquals("pnpm dev\r", launch.initialInput)
    }

    @Test
    fun `advertised script allocates next terminal when another session is live`() {
        val launch = projectScriptLaunch(
            terminals = listOf(terminal("term-1", "running"), terminal("term-3", "exited")),
            script = ProjectScript("test", "Test", "./gradlew test", ProjectScriptIcon.TEST, false),
        )

        assertEquals("term-2", launch.terminalId)
        assertEquals(false, launch.restart)
        assertEquals("./gradlew test\r", launch.initialInput)
    }

    private fun terminal(id: String, status: String) = TerminalSummary(
        threadId = "thread-1",
        terminalId = id,
        cwd = "/workspace",
        status = status,
        label = id,
        updatedAt = "2026-08-15T00:00:00Z",
    )
}
