package com.v2rayez.app

import com.v2rayez.app.data.fronting.DomainFrontDialer
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainFrontDialerTest {

    @Test
    fun emptyResponseIsEmpty() {
        assertEquals(
            DomainFrontDialer.TlsProbeResult.EMPTY,
            DomainFrontDialer.classifyTlsProbeResponse(byteArrayOf())
        )
        assertEquals(
            DomainFrontDialer.TlsProbeResult.EMPTY,
            DomainFrontDialer.classifyTlsProbeResponse(null)
        )
    }

    @Test
    fun tlsAlertIsRejected() {
        // Typical 7-byte TLS Alert record
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28)
        assertEquals(
            DomainFrontDialer.TlsProbeResult.ALERT,
            DomainFrontDialer.classifyTlsProbeResponse(alert)
        )
    }

    @Test
    fun tlsHandshakeAcceptedWhenLargeEnough() {
        val handshake = ByteArray(48) { 0 }
        handshake[0] = 0x16
        handshake[1] = 0x03
        handshake[2] = 0x03
        assertEquals(
            DomainFrontDialer.TlsProbeResult.HANDSHAKE,
            DomainFrontDialer.classifyTlsProbeResponse(handshake)
        )
    }

    @Test
    fun tinyHandshakeRecordNotAccepted() {
        val tiny = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x01, 0x01)
        assertEquals(
            DomainFrontDialer.TlsProbeResult.NON_HANDSHAKE,
            DomainFrontDialer.classifyTlsProbeResponse(tiny)
        )
    }
}
