package de.chennemann.agentic.domain.orchestration

import de.chennemann.agentic.t3.contract.ModelSelection
import de.chennemann.agentic.t3.contract.ProviderContinuation
import de.chennemann.agentic.t3.contract.ProviderInstance
import de.chennemann.agentic.t3.contract.ThreadSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadModelLockTest {
    private val codex = ProviderInstance("codex", displayName = "Codex", driver = "codex")
    private val codexPersonal = ProviderInstance("codex-personal", driver = "codex")
    private val codexShared = ProviderInstance(
        "codex-shared",
        driver = "codex",
        continuation = ProviderContinuation("shared"),
    )
    private val claude = ProviderInstance("claude", driver = "claudeAgent")
    private val providers = listOf(codex, codexPersonal, codexShared, claude)

    @Test
    fun `threads that have not started may use any model`() {
        assertNull(ThreadModelLock.of(providers, ModelSelection("codex", "gpt"), session = null, started = false))
    }

    @Test
    fun `started threads may switch models within their driver but not across drivers`() {
        val lock = requireNotNull(ThreadModelLock.of(providers, ModelSelection("codex", "gpt"), null, started = true))

        assertEquals("Codex", lock.providerLabel)
        assertTrue(lock.allows(codex, "gpt-mini"))
        assertTrue(lock.allows(codexPersonal, "gpt"))
        assertFalse(lock.allows(codexShared, "gpt"))
        assertFalse(lock.allows(claude, "sonnet"))
    }

    @Test
    fun `the running session decides which instance the thread is bound to`() {
        val session = ThreadSession(
            threadId = "thread",
            status = "ready",
            providerName = "claudeAgent",
            providerInstanceId = "claude",
            updatedAt = "now",
        )

        val lock = requireNotNull(ThreadModelLock.of(providers, ModelSelection("codex", "gpt"), session, started = true))

        assertEquals("claude", lock.instanceId)
        assertTrue(lock.allows(claude, "opus"))
        assertFalse(lock.allows(codex, "gpt"))
    }

    @Test
    fun `providers that require a new thread keep only the current model`() {
        val pinned = codex.copy(requiresNewThreadForModelChange = true)

        val lock = requireNotNull(
            ThreadModelLock.of(listOf(pinned, codexPersonal), ModelSelection("codex", "gpt"), null, started = true),
        )

        assertTrue(lock.allows(pinned, "gpt"))
        assertFalse(lock.allows(pinned, "gpt-mini"))
        assertFalse(lock.allows(codexPersonal, "gpt"))
    }
}
