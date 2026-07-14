package com.v2rayez.app

import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.CorePreference
import com.v2rayez.app.domain.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * P7 (v0.9.71) — proves ProxyParser + domain models accept WireGuard / SSH / DNS-tunnel / Psiphon
 * import URIs and round-trip the connection-critical fields. Pure JVM (no Android deps).
 */
class P7ProtocolParserTest {

    // ------------------------------------------------------------------ SSH
    @Test
    fun parsesSshLinkWithPassword() {
        val s = ProxyParser.parse("ssh://alice:s3cret@bastion.example.com:2222#Bastion")!!
        assertEquals(Protocol.SSH, s.protocol)
        assertEquals("bastion.example.com", s.host)
        assertEquals(2222, s.port)
        assertEquals("alice", s.sshUser)
        assertEquals("s3cret", s.password)
        // SSH runs only on sing-box.
        assertEquals(CorePreference.SING_BOX, s.preferredCore)
    }

    @Test
    fun sshDefaultsToPort22() {
        val s = ProxyParser.parse("ssh://root:pw@host.example#H")!!
        assertEquals(22, s.port)
        assertEquals("root", s.sshUser)
    }

    // ------------------------------------------------------------------ WireGuard (URI)
    @Test
    fun parsesWireguardUri() {
        val uri = "wireguard://cAABBprivkeyBASE64@wg.example.com:51820" +
            "?publickey=PEERPUBKEY&presharedkey=PSKKEY&address=10.7.0.2/32&reserved=1,2,3&mtu=1408#WG-DE"
        val s = ProxyParser.parse(uri)!!
        assertEquals(Protocol.WIREGUARD, s.protocol)
        assertEquals("wg.example.com", s.host)
        assertEquals(51820, s.port)
        assertEquals("cAABBprivkeyBASE64", s.wgPrivateKey)
        assertEquals("PEERPUBKEY", s.wgPeerPublicKey)
        assertEquals("PSKKEY", s.wgPreSharedKey)
        assertEquals(listOf("10.7.0.2/32"), s.wgLocalAddresses)
        assertEquals(listOf(1, 2, 3), s.wgReserved)
        assertEquals(1408, s.wgMtu)
        assertEquals(CorePreference.SING_BOX, s.preferredCore)
    }

    @Test
    fun parsesWireguardConfBlob() {
        val conf = """
            [Interface]
            PrivateKey = INTERFACEPRIVKEY
            Address = 10.9.0.2/32, fd00::2/128
            MTU = 1280

            [Peer]
            PublicKey = SERVERPUBKEY
            Endpoint = peer.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()
        val result = ProxyParser.parseManyDetailed(conf)
        assertEquals(1, result.servers.size)
        val s = result.servers.first()
        assertEquals(Protocol.WIREGUARD, s.protocol)
        assertEquals("peer.example.com", s.host)
        assertEquals(51820, s.port)
        assertEquals("INTERFACEPRIVKEY", s.wgPrivateKey)
        assertEquals("SERVERPUBKEY", s.wgPeerPublicKey)
        assertTrue(s.wgLocalAddresses.contains("10.9.0.2/32"))
        assertEquals(1280, s.wgMtu)
    }

    // ------------------------------------------------------------------ DNS tunnel
    @Test
    fun parsesDnsTunnelLink() {
        val uri = "dnstt://abcdef0123456789@t.example.com" +
            "?resolver=https%3A%2F%2F1.1.1.1%2Fdns-query&mode=doh#DNS-Home"
        val s = ProxyParser.parse(uri)!!
        assertEquals(Protocol.DNSTUNNEL, s.protocol)
        assertEquals("t.example.com", s.dnsTunnelDomain)
        assertEquals("abcdef0123456789", s.dnsTunnelPubKey)
        assertEquals("https://1.1.1.1/dns-query", s.dnsTunnelResolver)
        assertEquals("doh", s.dnsTunnelMode)
    }

    // ------------------------------------------------------------------ Psiphon
    @Test
    fun parsesPsiphonBase64Config() {
        val config = """{"PropagationChannelId":"ABC","SponsorId":"DEF"}"""
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(config.toByteArray())
        val s = ProxyParser.parse("psiphon://$b64#Psiphon")!!
        assertEquals(Protocol.PSIPHON, s.protocol)
        assertTrue(s.psiphonConfig.contains("PropagationChannelId"))
        assertEquals("Psiphon", s.name)
    }

    // ------------------------------------------------------------------ multi-import
    @Test
    fun parseManyMixesNewAndLegacyProtocols() {
        val text = """
            vless://11111111-2222-3333-4444-555555555555@ex.com:443?security=reality&pbk=KEY#V
            ssh://root:pw@ssh.example:22#S
            dnstt://deadbeef@t.example?resolver=https://1.1.1.1/dns-query#D
        """.trimIndent()
        val result = ProxyParser.parseManyDetailed(text)
        assertEquals(3, result.servers.size)
        assertEquals(0, result.skippedUnsupported)
        val protocols = result.servers.map { it.protocol }.toSet()
        assertTrue(protocols.contains(Protocol.VLESS))
        assertTrue(protocols.contains(Protocol.SSH))
        assertTrue(protocols.contains(Protocol.DNSTUNNEL))
    }

    // ------------------------------------------------------------------ round-trip
    @Test
    fun wireguardConfRoundTripsThroughToUri() {
        val conf = """
            [Interface]
            PrivateKey = PK
            Address = 10.0.0.5/32

            [Peer]
            PublicKey = PUB
            Endpoint = h.example:1234
        """.trimIndent()
        val s = ProxyParser.parseManyDetailed(conf).servers.first()
        val uri = ProxyParser.toUri(s)
        assertTrue(uri.startsWith("wireguard://"))
        val reparsed = ProxyParser.parse(uri)!!
        assertEquals(s.wgPeerPublicKey, reparsed.wgPeerPublicKey)
        assertEquals(s.host, reparsed.host)
        assertEquals(s.port, reparsed.port)
    }

    @Test
    fun protocolHelpersClassifyRuntime() {
        assertTrue(Protocol.WIREGUARD.requiresSingBox())
        assertTrue(Protocol.SSH.requiresSingBox())
        assertFalse(Protocol.VLESS.requiresSingBox())
        assertTrue(Protocol.PSIPHON.usesStandaloneEngine())
        assertTrue(Protocol.DNSTUNNEL.usesStandaloneEngine())
        assertFalse(Protocol.WIREGUARD.usesStandaloneEngine())
    }

    @Test
    fun psiphonImportedServerNotNull() {
        assertNotNull(ProxyParser.parse("psiphon://#Empty"))
    }
}
