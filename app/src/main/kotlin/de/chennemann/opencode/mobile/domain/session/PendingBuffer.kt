package de.chennemann.opencode.mobile.domain.session

data class PendingClaim(
    val id: String,
    val sort: String,
)

class PendingBuffer {
    private val value = linkedMapOf<String, MutableList<MessageState>>()
    private val pass = PassCounter()

    fun add(key: String, message: MessageState, keep: Int) {
        value.getOrPut(key) { mutableListOf() }.add(message)
        pass.set(key, message.id, keep)
    }

    fun list(key: String): List<MessageState>? {
        return value[key]
            ?.toList()
            ?.takeIf { it.isNotEmpty() }
    }

    fun remove(key: String, id: String) {
        val list = value[key] ?: return
        if (list.removeAll { it.id == id }) {
            pass.remove(key, id)
        }
        if (list.isEmpty()) {
            value.remove(key)
            pass.clear(key)
        }
    }

    fun claim(key: String, text: String): PendingClaim? {
        val list = value[key] ?: return null
        if (list.isEmpty()) return null
        val target = text.trim()
        val index = if (target.isBlank()) {
            0
        } else {
            list.indexOfFirst { it.text.trim() == target }
                .let { found -> if (found >= 0) found else if (list.size == 1) 0 else -1 }
        }
        if (index < 0) return null
        val removed = list.removeAt(index)
        pass.remove(key, removed.id)
        if (list.isEmpty()) {
            value.remove(key)
            pass.clear(key)
        }
        return PendingClaim(removed.id, removed.sort)
    }

    fun trim(key: String): Boolean {
        val list = value[key] ?: return false
        if (list.isEmpty()) return false
        val filtered = list.filter { pass.consume(key, it.id) }
        if (filtered.isEmpty()) {
            value.remove(key)
            pass.clear(key)
            return true
        }
        if (filtered.size == list.size) return false
        value[key] = filtered.toMutableList()
        return true
    }

    fun clear(key: String) {
        value.remove(key)
        pass.clear(key)
    }
}
