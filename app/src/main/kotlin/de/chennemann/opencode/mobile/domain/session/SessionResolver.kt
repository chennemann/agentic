package de.chennemann.opencode.mobile.domain.session

class SessionResolver(
    private val cooldown: Long = 5000,
) {
    private val seen = linkedMapOf<String, Long>()

    fun allow(sessionId: String, now: Long = System.currentTimeMillis()): Boolean {
        val value = seen[sessionId]
        if (value != null && now - value < cooldown) {
            return false
        }
        seen[sessionId] = now
        return true
    }
}
