package com.v2rayez.app

import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.core.SingBoxConfigBuilder
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 — proves the runtime config builders emit real WireGuard/SSH/xReality blocks (not stubs),
 * and that validate() gates the new protocols with actionable messages.
 */
class P7ProtocolConfigTest {

    private val settings = AppSettings()

    // ------------------------------------------------------------ WireGuard (sing-box endpoint)
    @Test
    fun singBoxEmitsWireguardEndpoint() {
        val s = ProxyParser.parse(
            "wireguard://PRIVKEY@wg.example.com:51820?publickey=PUBKEY&address=10.0.0.2/32&reserved=1,2,3&mtu=1408#WG"
        )!!
        val json = SingBoxConfigBuilder.build(s, settings, 10808)
        assertTrue("has endpoints", json.contains("\"endpoints\""))
        assertTrue("wireguard type", json.contains("\"type\":\"wireguard\""))
        assertTrue("private key", json.contains("\"private_key\":\"PRIVKEY\""))
        assertTrue("peer public key", json.contains("\"public_key\":\"PUBKEY\""))
        assertTrue("mtu", json.contains("\"mtu\":1408"))
        assertTrue("reserved", json.contains("\"reserved\":[1,2,3]"))
        assertTrue("route final proxy", json.contains("\"final\":\"proxy\""))
    }

    // ------------------------------------------------------------ SSH (sing-box outbound)
    @Test
    fun singBoxEmitsSshOutbound() {
        val s = ProxyParser.parse("ssh://root:pw@ssh.example.com:22#S")!!
        val json = SingBoxConfigBuilder.build(s, settings, 10808)
        assertTrue("ssh type", json.contains("\"type\":\"ssh\""))
        assertTrue("user", json.contains("\"user\":\"root\""))
        assertTrue("password", json.contains("\"password\":\"pw\""))
        assertTrue("client version", json.contains("client_version"))
    }

    // ------------------------------------------------------------ xReality hardening
    @Test
    fun effectiveFingerprintDefaultsToChrome() {
        val s = ProxyParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@ex.com:443?security=reality&pbk=KEY&sid=ab#R"
        )!!
        // URI omitted fp → hardened default.
        assertTrue(s.fingerprint.isBlank())
        assertEquals("chrome", ConfigBuilder.effectiveFingerprint(s, "reality"))
        assertEquals("chrome", ConfigBuilder.effectiveFingerprint(s, "tls"))
        assertEquals("", ConfigBuilder.effectiveFingerprint(s, "none"))
    }

    @Test
    fun xrayRealityConfigCarriesChromeFingerprint() {
        val s = ProxyParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@ex.com:443?security=reality&pbk=KEY&sid=ab&spx=%2Fspider#R"
        )!!
        val json = ConfigBuilder.build(s, settings, geositeAvailable = false)
        assertTrue("reality block", json.contains("realitySettings"))
        assertTrue("chrome fp", json.contains("\"fingerprint\":\"chrome\""))
        assertTrue("spiderX round-trips", json.contains("/spider"))
    }

    @Test
    fun explicitFingerprintIsPreserved() {
        val s = ProxyParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@ex.com:443?security=tls&fp=firefox&sni=ex.com#R"
        )!!
        assertEquals("firefox", ConfigBuilder.effectiveFingerprint(s, "tls"))
        val json = ConfigBuilder.build(s, settings, geositeAvailable = false)
        assertTrue(json.contains("\"fingerprint\":\"firefox\""))
    }

    @Test
    fun singBoxTlsGetsUtlsFingerprint() {
        val s = ProxyParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@ex.com:443?security=tls&sni=ex.com#T"
        )!!
        val json = SingBoxConfigBuilder.build(s, settings, 10808)
        assertTrue("utls block", json.contains("\"utls\""))
        assertTrue("chrome default", json.contains("\"fingerprint\":\"chrome\""))
    }

    // ------------------------------------------------------------ validate() gates
    @Test
    fun validateGatesNewProtocols() {
        val psiphon = ProxyParser.parse("psiphon://#p")!!
        assertNotNull(ConfigBuilder.validate(psiphon)) // blank config → error

        val dnsBad = ProxyParser.parse("dnstt://@t.example?resolver=https://1.1.1.1/dns-query#d")!!
        assertNotNull(ConfigBuilder.validate(dnsBad)) // missing pubkey → error

        val ssh = ProxyParser.parse("ssh://root:pw@h.example:22#s")!!
        assertNull(ConfigBuilder.validate(ssh)) // password present → ok

        val wgBad = ProxyParser.parse("wireguard://@h.example:51820?publickey=PUB#w")!!
        assertNotNull(ConfigBuilder.validate(wgBad)) // missing private key → error
    }
}
