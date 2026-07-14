package com.v2rayez.app

import com.v2rayez.app.data.mitm.MitmConfigBuilder
import com.v2rayez.app.domain.model.MitmDomainFrontConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmConfigBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun config() = MitmDomainFrontConfig(
        enabled = true,
        proxyPort = 10808,
        rulesText = """
            google.com = www.google.com
            youtube.com = www.google.com
            x.com = creators.spotify.com
        """.trimIndent(),
        defaultFront = "www.microsoft.com"
    )

    private fun build() = MitmConfigBuilder.build(
        config(),
        certFile = "/data/data/com.v2rayez.app/files/mycert.crt",
        keyFile = "/data/data/com.v2rayez.app/files/mycert.key"
    )

    @Test
    fun outputIsValidJson() {
        val obj = json.decodeFromString(JsonObject.serializer(), build())
        assertNotNull(obj["inbounds"])
        assertNotNull(obj["outbounds"])
        assertNotNull(obj["routing"])
        assertNotNull(obj["dns"])
    }

    @Test
    fun containsFromMitM() {
        assertTrue(build().contains(MitmConfigBuilder.ALPN_FROM_MITM))
    }

    @Test
    fun usesAbsoluteCertPaths() {
        val out = build()
        assertTrue(out.contains("/data/data/com.v2rayez.app/files/mycert.crt"))
        assertTrue(out.contains("/data/data/com.v2rayez.app/files/mycert.key"))
    }

    @Test
    fun hasSocksInboundOnProxyPort() {
        val out = build()
        assertTrue(out.contains("\"protocol\":\"socks\""))
        assertTrue(out.contains("\"port\":10808"))
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_SOCKS_IN}\""))
    }

    @Test
    fun hasTunInboundByDefault() {
        val out = build()
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_TUN_IN}\""))
        assertTrue(out.contains("\"protocol\":\"tun\""))
    }

    @Test
    fun blocksQuicAndUdp443() {
        val out = build()
        assertTrue(out.contains("\"protocol\":[\"quic\"]"))
        assertTrue(out.contains("\"outboundTag\":\"${MitmConfigBuilder.TAG_BLOCK}\""))
        assertTrue(out.contains("\"port\":\"443\""))
        assertTrue(out.contains("\"network\":[\"udp\"]"))
    }

    @Test
    fun tunBuildIncludesFakeDns() {
        val out = build()
        assertTrue(out.contains("\"fakedns\""))
        assertTrue(out.contains("198.18.0.0/15"))
        assertTrue(out.contains("\"destOverride\":[\"http\",\"tls\",\"quic\",\"fakedns\"]"))
    }

    @Test
    fun proxyOnlyOmitsFakeDnsPool() {
        val out = MitmConfigBuilder.build(
            config(),
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        assertFalse(out.contains("198.18.0.0/15"))
        // QUIC block still applies without TUN.
        assertTrue(out.contains("\"protocol\":[\"quic\"]"))
    }

    @Test
    fun canOmitTunInbound() {
        val out = MitmConfigBuilder.build(
            config(),
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        assertFalse(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_TUN_IN}\""))
        // Routing must not reference tun-in when the inbound is omitted.
        assertFalse(out.contains("\"${MitmConfigBuilder.TAG_TUN_IN}\""))
        assertTrue(
            out.contains(
                "\"inboundTag\":[\"${MitmConfigBuilder.TAG_SOCKS_IN}\",\"${MitmConfigBuilder.TAG_HTTP_IN}\"]"
            )
        )
    }

    @Test
    fun hasHttpInboundOnHttpPort() {
        val out = MitmConfigBuilder.build(
            config().copy(httpPort = 10809),
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        assertTrue(out.contains("\"protocol\":\"http\""))
        assertTrue(out.contains("\"port\":10809"))
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_HTTP_IN}\""))
    }

    @Test
    fun hasMitmDecryptInboundWithIssueCert() {
        val out = build()
        assertTrue(out.contains("\"protocol\":\"dokodemo-door\""))
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_DECRYPT}\""))
        assertTrue(out.contains("\"usage\":\"issue\""))
    }

    @Test
    fun hasRedirectOutbound() {
        val out = build()
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_REDIRECT}\""))
        assertTrue(out.contains("127.0.0.1:${MitmConfigBuilder.DECRYPT_PORT}"))
    }

    @Test
    fun hasPerFrontRepackOutbounds() {
        val out = build()
        // Two distinct fronts -> two repack outbounds carrying their serverName + alpn fromMitM.
        assertTrue(out.contains("\"serverName\":\"www.google.com\""))
        assertTrue(out.contains("\"serverName\":\"creators.spotify.com\""))
        assertTrue(out.contains("\"verifyPeerCertByName\":\"fromMitM,www.google.com\""))
    }

    @Test
    fun defaultFrontDoesNotMitmUnmatchedHosts() {
        // DoH still uses www.microsoft.com as front SNI; defaultFront must NOT add a catch-all MITM.
        val out = MitmConfigBuilder.build(
            MitmDomainFrontConfig(
                rulesText = "google.com = www.google.com",
                defaultFront = "www.microsoft.com",
                dohFrontSni = "www.cloudflare.com"
            ),
            certFile = "/abs/mycert.crt",
            keyFile = "/abs/mycert.key"
        )
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_DIRECT}\""))
        assertTrue(out.contains("domain:google.com"))
        // No catch-all decrypt → default front repack (only rule-based google front).
        assertFalse(out.contains("\"tag\":\"tls-repack-default\""))
    }

    @Test
    fun unmatchedAppTrafficRoutesDirect() {
        val out = build()
        // After rule redirects, socks/http/tun leftovers must be tagged direct.
        assertTrue(out.contains("\"outboundTag\":\"${MitmConfigBuilder.TAG_DIRECT}\""))
        assertTrue(
            out.contains(
                "\"inboundTag\":[\"${MitmConfigBuilder.TAG_SOCKS_IN}\"," +
                    "\"${MitmConfigBuilder.TAG_HTTP_IN}\",\"${MitmConfigBuilder.TAG_TUN_IN}\"]"
            )
        )
    }

    @Test
    fun dnsUsesFrontedDoh() {
        val out = build()
        assertTrue(out.contains("h2c://1.1.1.1/dns-query"))
        assertTrue(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_REPACK_DNS}\""))
        assertTrue(out.contains("cloudflare-dns.com"))
        assertTrue(out.contains("\"serverName\":\"www.microsoft.com\""))
    }

    @Test
    fun routingMapsRulesToRepack() {
        val out = build()
        assertTrue(out.contains("domain:google.com"))
        assertTrue(out.contains("domain:x.com"))
        assertTrue(out.contains("\"inboundTag\":[\"${MitmConfigBuilder.TAG_DECRYPT}\"]"))
    }

    @Test
    fun emptyRulesStillProducesValidConfig() {
        val out = MitmConfigBuilder.build(
            MitmDomainFrontConfig(rulesText = "# only a comment\n", defaultFront = ""),
            certFile = "/abs/mycert.crt",
            keyFile = "/abs/mycert.key"
        )
        val obj = json.decodeFromString(JsonObject.serializer(), out)
        assertEquals(true, obj["outbounds"] != null)
        assertTrue(out.contains(MitmConfigBuilder.ALPN_FROM_MITM))
    }
}
