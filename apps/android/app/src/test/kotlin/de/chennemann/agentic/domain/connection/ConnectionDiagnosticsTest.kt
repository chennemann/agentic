package de.chennemann.agentic.domain.connection

import de.chennemann.agentic.data.t3.NetworkFailure
import de.chennemann.agentic.data.t3.T3TransportException
import de.chennemann.agentic.data.t3.isUpgradeRequired
import de.chennemann.agentic.data.t3.networkFailureOf
import de.chennemann.agentic.t3.contract.ServerEventLoopStall
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionDiagnosticsTest {
    @Test fun `transport failures keep their network cause`() {
        assertEquals(NetworkFailure.UNRESOLVED_HOST, networkFailureOf(IOException("wrapped", UnknownHostException("studio"))))
        assertEquals(NetworkFailure.REFUSED, networkFailureOf(ConnectException("Connection refused")))
        assertEquals(NetworkFailure.TIMEOUT, networkFailureOf(SocketTimeoutException("timeout")))
        assertEquals(NetworkFailure.UNREACHABLE, networkFailureOf(IOException("reset")))
    }

    @Test fun `a refused websocket upgrade is recognised as a protocol mismatch`() {
        assertTrue(IOException("upgrade", ProtocolException("Expected HTTP 101 response but was '426 Upgrade Required'")).isUpgradeRequired())
        assertFalse(ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'").isUpgradeRequired())
        assertFalse(IOException("port 14260 closed").isUpgradeRequired())
    }

    @Test fun `failure messages point at what to check`() {
        assertEquals(
            "Studio closed the connection.",
            connectionFailureMessage(T3TransportException.Network(NetworkFailure.DISCONNECTED), "Studio")
        )
        assertTrue(connectionFailureMessage(T3TransportException.Http(502), "Studio").contains("proxy"))
        assertEquals(
            "Studio runs an older T3 Code. Update T3 Code on Studio to connect.",
            protocolMismatchMessage("Studio", 1)
        )
        assertTrue(protocolMismatchMessage("Studio", null).contains("different T3 protocol versions"))
    }

    @Test fun `freeze notices blame the cpu only when it was overloaded`() {
        val busy = hostFreezeNotice("env", "Studio", ServerEventLoopStall("2026-10-27T10:00:00Z", 8_600, 1.5, 8))!!
        assertEquals("Studio froze for 9s", busy.title)
        assertEquals("T3 Code on Studio was too busy to answer.", busy.description)
        assertEquals("env:2026-10-27T10:00:00Z", busy.key)
        assertEquals(
            "T3 Code on Studio was too busy to answer.",
            hostFreezeNotice("env", "Studio", ServerEventLoopStall("t", 1_000, null, 8))!!.description
        )
        assertNull(hostFreezeNotice("env", "Studio", null))
    }
}
