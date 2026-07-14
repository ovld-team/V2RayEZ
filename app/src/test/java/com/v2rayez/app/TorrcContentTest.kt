package com.v2rayez.app

import com.v2rayez.app.data.tor.TorrcContent
import com.v2rayez.app.domain.model.TorConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrcContentTest {

    @Test
    fun includesDnsPortAndAutomap() {
        val lines = TorrcContent.lines(
            config = TorConfig(socksPort = 9050, dnsPort = 9053),
            dataDirPath = "/tmp/tor-data",
            ptLines = emptyList(),
            bridges = emptyList()
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("SocksPort 127.0.0.1:9050"))
        assertTrue(text.contains("DNSPort 127.0.0.1:9053"))
        assertTrue(text.contains("AutomapHostsOnResolve 1"))
        assertTrue(text.contains("DataDirectory /tmp/tor-data"))
    }

    @Test
    fun bindsDnsPortToLoopbackWhenSocksHostIsCustom() {
        val lines = TorrcContent.lines(
            config = TorConfig(socksHost = "192.0.2.10", socksPort = 9050, dnsPort = 9053),
            dataDirPath = "/tmp/tor-data",
            ptLines = emptyList(),
            bridges = emptyList()
        )

        assertTrue(lines.contains("SocksPort 192.0.2.10:9050"))
        assertTrue(lines.contains("DNSPort 127.0.0.1:9053"))
        assertTrue(!lines.contains("DNSPort 192.0.2.10:9053"))
    }

    @Test
    fun includesPluggableTransportBeforeSelectedBridges() {
        val lines = TorrcContent.lines(
            config = TorConfig(socksPort = 9050, dnsPort = 9053),
            dataDirPath = "/tmp/tor-data",
            ptLines = listOf(
                "UseBridges 1",
                "ClientTransportPlugin obfs4 exec /native/liblyrebird.so"
            ),
            bridges = listOf(
                "Bridge obfs4 192.0.2.10:443 cert=abc iat-mode=0",
                "obfs4 192.0.2.11:8443 cert=def iat-mode=0"
            )
        )

        val pluginIndex = lines.indexOf("ClientTransportPlugin obfs4 exec /native/liblyrebird.so")
        val firstBridgeIndex = lines.indexOf("Bridge obfs4 192.0.2.10:443 cert=abc iat-mode=0")
        assertTrue(pluginIndex >= 0)
        assertTrue(firstBridgeIndex > pluginIndex)
        assertTrue(lines.contains("Bridge obfs4 192.0.2.11:8443 cert=def iat-mode=0"))
    }
}
