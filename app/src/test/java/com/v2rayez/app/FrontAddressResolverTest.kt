package com.v2rayez.app

import com.v2rayez.app.data.fronting.FrontAddressResolver
import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontAddressResolverTest {

    private fun server(
        host: String,
        sni: String = host,
        security: String = "TLS",
        streamSecurity: String = "tls",
        publicKey: String = ""
    ) = Server(
        id = "1",
        name = "t",
        country = "",
        countryCode = "",
        protocol = Protocol.VLESS,
        transport = "WS",
        security = security,
        sni = sni,
        address = "$host:443",
        pingMs = -1,
        signal = 0,
        group = ServerGroup.MANUAL,
        host = host,
        port = 443,
        streamSecurity = streamSecurity,
        publicKey = publicKey
    )

    @Test
    fun ipHostUsedDirectly() {
        val resolved = FrontAddressResolver.resolveForServer(server("1.2.3.4", sni = ""))
        assertNotNull(resolved)
        assertEquals("1.2.3.4", resolved!!.primary)
    }

    @Test
    fun cdnFrontKeepsConfiguredEdgeIps_likeEasySNI() {
        val config = DomainFrontConfig(
            enabled = true,
            frontAddress = "104.19.229.21",
            fallbackAddress = "104.19.230.21"
        )
        val (next, note) = FrontAddressResolver.withResolvedFronts(config, server("8.8.8.8", sni = ""))
        assertEquals("104.19.229.21", next.frontAddress)
        assertEquals("104.19.230.21", next.fallbackAddress)
        assertTrue(note!!.contains("CDN front"))
        assertFalse(FrontAddressResolver.needsOriginDial(server("8.8.8.8")))
    }

    @Test
    fun realityForcesOriginDial() {
        val config = DomainFrontConfig(
            enabled = true,
            frontAddress = "104.19.229.21",
            fallbackAddress = "104.19.230.21"
        )
        val reality = server(
            host = "9.9.9.9",
            sni = "www.microsoft.com",
            security = "Reality",
            streamSecurity = "reality",
            publicKey = "abcdefghijklmnopqrstuvwxyz012345"
        )
        assertTrue(FrontAddressResolver.needsOriginDial(reality))
        val (next, note) = FrontAddressResolver.withResolvedFronts(config, reality)
        assertEquals("9.9.9.9", next.frontAddress)
        assertTrue(note!!.contains("origin dial"))
    }
}
