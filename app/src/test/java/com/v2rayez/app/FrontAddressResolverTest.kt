package com.v2rayez.app

import com.v2rayez.app.data.fronting.FrontAddressResolver
import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontAddressResolverTest {

    private fun server(host: String, sni: String = host) = Server(
        id = "1",
        name = "t",
        country = "",
        countryCode = "",
        protocol = Protocol.VLESS,
        transport = "WS",
        security = "TLS",
        sni = sni,
        address = "$host:443",
        pingMs = -1,
        signal = 0,
        group = ServerGroup.MANUAL,
        host = host,
        port = 443
    )

    @Test
    fun ipHostUsedDirectly() {
        val resolved = FrontAddressResolver.resolveForServer(server("1.2.3.4", sni = ""))
        assertNotNull(resolved)
        assertEquals("1.2.3.4", resolved!!.primary)
    }

    @Test
    fun withResolvedFrontsOverridesGenericCfDefaults() {
        val config = DomainFrontConfig(
            enabled = true,
            frontAddress = "104.19.229.21",
            fallbackAddress = "104.19.230.21"
        )
        // Use literal IP server so test is offline-safe
        val (next, note) = FrontAddressResolver.withResolvedFronts(config, server("8.8.8.8", sni = ""))
        assertEquals("8.8.8.8", next.frontAddress)
        assertTrue(note!!.contains("8.8.8.8"))
    }
}
