package com.v2rayez.app

import com.v2rayez.app.data.local.ServerListEntity
import com.v2rayez.app.data.local.toModel
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListEntityMappingTest {
    @Test
    fun `list projection omits blob columns with empty defaults`() {
        val model = ServerListEntity(
            id = "lite-1",
            name = "Lite",
            country = "Unknown",
            countryCode = "UN",
            protocol = Protocol.SSH,
            transport = "TCP",
            security = "None",
            sni = "",
            address = "example.com:22",
            pingMs = 42,
            signal = 3,
            group = ServerGroup.SUBSCRIPTION,
            isFavorite = true,
            host = "example.com",
            port = 22,
            uuid = "",
            password = "secret",
            method = "",
            alterId = 0,
            flow = "",
            network = "tcp",
            headerType = "none",
            path = "",
            requestHost = "",
            streamSecurity = "none",
            alpn = "",
            fingerprint = "",
            allowInsecure = false,
            publicKey = "",
            shortId = "",
            spiderX = "",
            subscriptionId = "sub-1",
            sshUser = "root",
            preferredCore = "SYSTEM"
        ).toModel()

        assertEquals("lite-1", model.id)
        assertEquals(42, model.pingMs)
        assertEquals("root", model.sshUser)
        assertEquals("", model.rawUri)
        assertEquals("", model.sshPrivateKey)
        assertEquals("", model.sshHostKey)
        assertEquals("", model.wgPrivateKey)
        assertEquals("", model.wgPeerPublicKey)
        assertEquals("", model.wgPreSharedKey)
        assertEquals("", model.psiphonConfig)
        assertTrue(model.isFavorite)
    }
}
