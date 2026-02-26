package de.chennemann.opencode.mobile.domain.session

class PassCounter {
    private val value = linkedMapOf<String, MutableMap<String, Int>>()

    fun set(key: String, id: String, pass: Int) {
        value.getOrPut(key) { linkedMapOf() }[id] = pass
    }

    fun remove(key: String, id: String) {
        val map = value[key] ?: return
        map.remove(id)
        if (map.isEmpty()) {
            value.remove(key)
        }
    }

    fun clear(key: String) {
        value.remove(key)
    }

    fun consume(key: String, id: String): Boolean {
        val map = value[key] ?: return false
        val pass = map[id] ?: return false
        if (pass <= 0) {
            map.remove(id)
            if (map.isEmpty()) {
                value.remove(key)
            }
            return false
        }
        map[id] = pass - 1
        if (map[id] == 0) {
            map.remove(id)
        }
        if (map.isEmpty()) {
            value.remove(key)
        }
        return true
    }
}
