package com.v2rayez.app

import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerValidationTest {

    private fun server(
        protocol: Protocol = Protocol.VLESS,
        host: String = "srv.example.com",
        port: Int = 443,
        uuid: String = "some-uuid",
        password: String = "secret",
        method: String = "aes-256-gcm"
    ) = Server(
        id = "1", name = "Test", country = "US", countryCode = "US",
        protocol = protocol, transport = "TCP", security = "TLS", sni = "",
        address = "$host:$port", pingMs = 0, signal = 0, group = ServerGroup.MANUAL,
        host = host, port = port, uuid = uuid, password = password, method = method
    )

    @Test
    fun validServerPasses() {
        assertNull(ConfigBuilder.validate(server()))
        assertNull(ConfigBuilder.validate(server(protocol = Protocol.TROJAN)))
        assertNull(ConfigBuilder.validate(server(protocol = Protocol.SHADOWSOCKS)))
    }

    @Test
    fun blankHostFails() {
        assertNotNull(ConfigBuilder.validate(server(host = "")))
    }

    @Test
    fun invalidPortFails() {
        assertNotNull(ConfigBuilder.validate(server(port = 0)))
        assertNotNull(ConfigBuilder.validate(server(port = 70000)))
    }

    @Test
    fun trojanWithoutPasswordFailsWithSpecificMessage() {
        val msg = ConfigBuilder.validate(server(protocol = Protocol.TROJAN, password = ""))
        assertNotNull(msg)
        assertTrue(msg!!.contains("Trojan password", ignoreCase = true))
    }

    @Test
    fun shadowsocksWithoutPasswordOrMethodFails() {
        assertNotNull(ConfigBuilder.validate(server(protocol = Protocol.SHADOWSOCKS, password = "")))
        assertNotNull(ConfigBuilder.validate(server(protocol = Protocol.SHADOWSOCKS, method = "")))
    }

    @Test
    fun vlessAndVmessWithoutUuidFail() {
        assertNotNull(ConfigBuilder.validate(server(protocol = Protocol.VLESS, uuid = "")))
        assertNotNull(ConfigBuilder.validate(server(protocol = Protocol.VMESS, uuid = "")))
    }
}
