package de.chennemann.agentic.data.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClientCertificatesTest {
    @Test
    fun `endpoint key normalizes HTTPS host and default port`() {
        assertEquals("example.test:443", clientCertificateEndpointKey("https://EXAMPLE.test/path"))
        assertEquals("example.test:8443", clientCertificateEndpointKey("https://example.test:8443/"))
    }

    @Test
    fun `endpoint key rejects non-HTTPS and invalid URLs`() {
        assertNull(clientCertificateEndpointKey("http://example.test/"))
        assertNull(clientCertificateEndpointKey("not a URL"))
    }
}
