package com.v2rayez.app

import com.v2rayez.app.data.parser.ClashYamlParser
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashYamlParserTest {
    @Test
    fun importsSupportedMihomoProxies() {
        val yaml = requireNotNull(javaClass.getResource("/fixtures/clash-mihomo.yaml")).readText()
        val servers = ClashYamlParser.parse(yaml, ServerGroup.SUBSCRIPTION, "sub-1")

        assertEquals(3, servers.size)
        val trojan = servers.first { it.protocol == Protocol.TROJAN }
        assertEquals("reality", trojan.streamSecurity)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", trojan.publicKey)
        assertEquals("tunnel", trojan.path)

        val ss = servers.first { it.protocol == Protocol.SHADOWSOCKS }
        assertEquals("v2ray-plugin", ss.ssPlugin)
        assertTrue(ss.ssPluginOptions.contains("mode=websocket"))

        val wg = servers.first { it.protocol == Protocol.WIREGUARD }
        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16"), wg.wgAllowedIps)
        assertTrue(servers.all { it.subscriptionId == "sub-1" })
    }
}
