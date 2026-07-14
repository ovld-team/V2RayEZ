package com.v2rayez.app

import com.v2rayez.app.data.local.V2RayDatabase
import com.v2rayez.app.data.local.toEntity
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomMigrationContractTest {
    @Test
    fun `recoverable schema versions have a contiguous migration path`() {
        val migrations = listOf(
            V2RayDatabase.MIGRATION_3_4,
            V2RayDatabase.MIGRATION_4_5,
            V2RayDatabase.MIGRATION_5_6,
            V2RayDatabase.MIGRATION_6_7,
            V2RayDatabase.MIGRATION_7_8
        )

        assertEquals(listOf(3, 4, 5, 6, 7), migrations.map { it.startVersion })
        assertEquals(listOf(4, 5, 6, 7, 8), migrations.map { it.endVersion })
    }

    @Test
    fun `v8 entity mapping preserves plugin and allowed ip columns`() {
        val entity = Server(
            id = "v8",
            name = "v8",
            country = "Unknown",
            countryCode = "UN",
            protocol = Protocol.SHADOWSOCKS,
            transport = "TCP",
            security = "None",
            sni = "",
            address = "example.com:443",
            pingMs = -1,
            signal = 0,
            group = ServerGroup.MANUAL,
            host = "example.com",
            ssPlugin = "v2ray-plugin",
            ssPluginOptions = "mode=websocket;tls",
            wgAllowedIps = listOf("10.0.0.0/8", "fd00::/8")
        ).toEntity()

        assertEquals("v2ray-plugin", entity.ssPlugin)
        assertEquals("mode=websocket;tls", entity.ssPluginOptions)
        assertEquals("10.0.0.0/8,fd00::/8", entity.wgAllowedIps)
    }
}
