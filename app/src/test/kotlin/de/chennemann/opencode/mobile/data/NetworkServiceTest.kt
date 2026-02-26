package de.chennemann.opencode.mobile.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkServiceTest {
    @Test
    fun startsWithCurrentConnectivityAndZeroChanges() {
        val source = FakeConnectivitySource(initial = true)

        val service = NetworkService(source)

        assertTrue(service.online.value)
        assertEquals(0L, service.changed.value)
    }

    @Test
    fun updatesConnectivityAcrossAllCallbackTypesAndIncrementsChanged() {
        val source = FakeConnectivitySource(initial = false)
        val service = NetworkService(source)

        source.emitAvailable(connected = true)
        assertTrue(service.online.value)
        assertEquals(1L, service.changed.value)

        source.emitCapabilitiesChanged(connected = true)
        assertTrue(service.online.value)
        assertEquals(2L, service.changed.value)

        source.emitLost(connected = false)
        assertFalse(service.online.value)
        assertEquals(3L, service.changed.value)

        source.emitUnavailable(connected = false)
        assertFalse(service.online.value)
        assertEquals(4L, service.changed.value)
    }

    @Test
    fun incrementsChangedEvenWhenConnectivityValueDoesNotChange() {
        val source = FakeConnectivitySource(initial = true)
        val service = NetworkService(source)

        source.emitCapabilitiesChanged(connected = true)
        source.emitCapabilitiesChanged(connected = true)

        assertTrue(service.online.value)
        assertEquals(2L, service.changed.value)
    }
}

private class FakeConnectivitySource(initial: Boolean) : ConnectivitySource {
    private var connected = initial
    private var callback: ConnectivitySource.Callback? = null

    override fun register(callback: ConnectivitySource.Callback) {
        this.callback = callback
    }

    override fun isConnected(): Boolean {
        return connected
    }

    fun emitAvailable(connected: Boolean) {
        this.connected = connected
        callback?.onAvailable()
    }

    fun emitLost(connected: Boolean) {
        this.connected = connected
        callback?.onLost()
    }

    fun emitCapabilitiesChanged(connected: Boolean) {
        this.connected = connected
        callback?.onCapabilitiesChanged()
    }

    fun emitUnavailable(connected: Boolean) {
        this.connected = connected
        callback?.onUnavailable()
    }
}
