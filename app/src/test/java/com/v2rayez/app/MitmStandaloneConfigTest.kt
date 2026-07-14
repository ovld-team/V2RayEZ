package com.v2rayez.app

import com.v2rayez.app.data.mitm.MitmConfigBuilder
import com.v2rayez.app.domain.model.MitmDomainFrontConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Standalone MITM config using the shipped patterniha/DEFAULT_RULES (our product config).
 * Asserts SOCKS+HTTP inbounds and no TUN when includeTun=false — what MitmProxyService runs.
 */
class MitmStandaloneConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ourConfig() = MitmDomainFrontConfig(
        enabled = true,
        proxyPort = 10808,
        httpPort = 10809,
        captureAllApps = false,
        rulesText = MitmDomainFrontConfig.DEFAULT_RULES,
        caInstallAcknowledged = true
    )

    @Test
    fun defaultRulesPresentAndParseable() {
        assertTrue(MitmDomainFrontConfig.DEFAULT_RULES.contains("google.com"))
        assertTrue(MitmDomainFrontConfig.DEFAULT_RULES.contains("www.google.com"))
        val out = MitmConfigBuilder.build(
            ourConfig(),
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        val obj = json.decodeFromString(JsonObject.serializer(), out)
        assertTrue((obj["inbounds"] as JsonArray).size >= 3) // socks + http + decrypt
    }

    @Test
    fun standaloneHasHttpAndSocksWithoutTun() {
        val cfg = ourConfig()
        val out = MitmConfigBuilder.build(
            cfg,
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        assertTrue(out.contains("\"protocol\":\"socks\""))
        assertTrue(out.contains("\"protocol\":\"http\""))
        assertTrue(out.contains("\"port\":10808"))
        assertTrue(out.contains("\"port\":10809"))
        assertFalse(out.contains("\"tag\":\"${MitmConfigBuilder.TAG_TUN_IN}\""))
        assertFalse(out.contains(MitmConfigBuilder.TAG_TUN_IN))
        // Rules from DEFAULT_RULES appear in routing
        assertTrue(out.contains("domain:google.com"))
        assertTrue(out.contains("domain:youtube.com") || out.contains("domain:googlevideo.com") ||
            out.contains("www.google.com"))
    }

    @Test
    fun standaloneRoutesYouTubeMediaCdnsThroughMitm() {
        // Hard requirement: the standalone (proxy-only) MITM config must keep the YouTube media
        // CDNs in the decrypt path, or the in-app Browser gets a spinner instead of video.
        val out = MitmConfigBuilder.build(
            ourConfig(),
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        listOf(
            "domain:youtube.com",
            "domain:googlevideo.com",
            "domain:ytimg.com",
            "domain:ggpht.com",
            "domain:gstatic.com",
            "domain:googleapis.com",
            "domain:googleusercontent.com",
            "domain:gvt1.com"
        ).forEach { assertTrue("missing route $it", out.contains(it)) }
        // All of the above front through www.google.com (pure SNI swap).
        assertTrue(out.contains("\"serverName\":\"www.google.com\""))
    }

    @Test
    fun standaloneBlocksQuicAndUdp443ForTcpFallback() {
        // Even without TUN, QUIC/UDP-443 must be blackholed so WebView falls back to TCP TLS,
        // which the MITM can actually decrypt.
        val out = MitmConfigBuilder.build(
            ourConfig(),
            certFile = "/tmp/mycert.crt",
            keyFile = "/tmp/mycert.key",
            includeTun = false
        )
        assertTrue(out.contains("\"protocol\":[\"quic\"]"))
        assertTrue(out.contains("\"port\":\"443\""))
        assertTrue(out.contains("\"network\":[\"udp\"]"))
        assertTrue(out.contains("\"outboundTag\":\"${MitmConfigBuilder.TAG_BLOCK}\""))
    }

    @Test
    fun desktopExportMatchesStandaloneShape() {
        val exported = com.v2rayez.app.data.mitm.MitmDesktopExporter.export(ourConfig())
        assertTrue(exported.contains("socks"))
        assertTrue(exported.contains("http") || exported.contains("\"port\":10809"))
        assertFalse(exported.contains("\"tag\":\"tun-in\""))
    }

    @Test
    fun writeStandaloneJsonArtifactForManualXrayProbe() {
        val dir = File("build/mitm-standalone-test").apply { mkdirs() }
        val crt = File(dir, "mycert.crt").apply {
            // Placeholder paths — real runtime uses MitmCaStore; this only validates JSON shape.
            writeText("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n")
        }
        val key = File(dir, "mycert.key").apply {
            writeText("-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----\n")
        }
        val cfg = ourConfig()
        val jsonText = MitmConfigBuilder.build(
            cfg,
            certFile = crt.absolutePath,
            keyFile = key.absolutePath,
            includeTun = false
        )
        File(dir, "standalone_mitm.json").writeText(jsonText)
        assertTrue(File(dir, "standalone_mitm.json").length() > 100)
        // Document expected listen ports for operators
        File(dir, "PORTS.txt").writeText("socks=${cfg.proxyPort}\nhttp=${cfg.httpPort}\n")
    }
}
