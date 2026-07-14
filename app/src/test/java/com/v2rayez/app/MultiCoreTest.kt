package com.v2rayez.app

import com.v2rayez.app.data.core.ClashConfigBuilder
import com.v2rayez.app.data.core.CoreResolver
import com.v2rayez.app.data.core.SingBoxConfigBuilder
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.CorePreference
import com.v2rayez.app.domain.model.ProxyCoreType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCoreTest {

    private fun vless() = ProxyParser.parse(
        "vless://id-9@srv.example.com:443?encryption=none&security=tls&type=ws&path=%2Fp&sni=srv.example.com#Node"
    )!!

    @Test
    fun coreResolverUsesServerOverride() {
        val settings = AppSettings(defaultCore = ProxyCoreType.XRAY)
        val server = vless().copy(preferredCore = CorePreference.SING_BOX)
        assertEquals(ProxyCoreType.SING_BOX, CoreResolver.resolve(server, settings))
    }

    @Test
    fun coreResolverFallsBackToDefault() {
        val settings = AppSettings(defaultCore = ProxyCoreType.CLASH)
        val server = vless().copy(preferredCore = CorePreference.SYSTEM)
        assertEquals(ProxyCoreType.CLASH, CoreResolver.resolve(server, settings))
    }

    @Test
    fun singBoxConfigContainsSocksAndVless() {
        val json = SingBoxConfigBuilder.build(vless(), AppSettings(), 10808)
        assertTrue(json.contains("\"type\":\"socks\""))
        assertTrue(json.contains("\"type\":\"vless\""))
        assertTrue(json.contains("srv.example.com"))
        assertTrue(json.contains("10808"))
    }

    @Test
    fun clashConfigContainsMixedPortAndVless() {
        val yaml = ClashConfigBuilder.build(vless(), AppSettings(), 10808)
        assertTrue(yaml.contains("mixed-port: 10808"))
        assertTrue(yaml.contains("type: vless"))
        assertTrue(yaml.contains("srv.example.com"))
        assertTrue(yaml.contains("MATCH,GLOBAL"))
    }
}
