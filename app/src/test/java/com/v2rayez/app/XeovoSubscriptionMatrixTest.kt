package com.v2rayez.app

import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.DesyncConfig
import com.v2rayez.app.domain.model.DesyncMode
import com.v2rayez.app.domain.model.DnsConfig
import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.FragmentConfig
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.RoutingConfig
import com.v2rayez.app.domain.model.RoutingMode
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.SniConfig
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.WarpConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Live Xeovo subscription matrix. Prefers /tmp/xeovo-sub.txt if present, else fetches.
 */
class XeovoSubscriptionMatrixTest {

    private fun loadSub(): String {
        val local = File("/tmp/xeovo-sub.txt")
        if (local.isFile && local.length() > 100) return local.readText()
        return URL(SUB_URL).openStream().bufferedReader().readText()
    }

    private fun servers(): List<Server> =
        ProxyParser.parseMany(loadSub(), ServerGroup.SUBSCRIPTION, "xeovo-test")

    @Test
    fun parseInventory() {
        val raw = loadSub()
        val lines = raw.lines().map { it.trim() }.filter { it.contains("://") }
        val byScheme = lines.groupingBy { it.substringBefore("://").lowercase() }.eachCount()
        val detail = ProxyParser.parseManyDetailed(raw, ServerGroup.SUBSCRIPTION, "xeovo-test")
        val parsed = detail.servers
        println("RAW lines=${lines.size} schemes=$byScheme")
        println("PARSED=${parsed.size} byProto=${parsed.groupingBy { it.protocol }.eachCount()}")
        println("DETAIL ${detail.summaryMessage()}")
        // Hysteria2 is unsupported — expect skip report
        assertTrue("expected hysteria2 in feed", (byScheme["hysteria2"] ?: 0) > 0)
        assertEquals(byScheme["hysteria2"] ?: 0, detail.skippedUnsupported)
        assertTrue(detail.summaryMessage().contains("hysteria2"))
        // Supported protocols must mostly parse
        val supported = (byScheme["ss"] ?: 0) + (byScheme["trojan"] ?: 0) +
            (byScheme["vless"] ?: 0) + (byScheme["vmess"] ?: 0)
        assertTrue("parsed ${parsed.size} < 80% of supported $supported", parsed.size >= (supported * 0.8).toInt())
        // No empty hosts / bad ports
        parsed.forEach { s ->
            assertTrue("${s.name} blank host", s.host.isNotBlank())
            assertTrue("${s.name} bad port ${s.port}", s.port in 1..65535)
            assertNull("${s.name} validate=${ConfigBuilder.validate(s)}", ConfigBuilder.validate(s))
        }
    }

    @Test
    fun vlessPathSegmentDoesNotCorruptPort() {
        val vless = servers().filter { it.protocol == Protocol.VLESS }
        assumeTrue(vless.isNotEmpty())
        // Xeovo uses host:443/path?query — port must stay numeric and path must be present for WS
        val ws = vless.filter { it.network == "ws" }
        assertTrue(ws.isNotEmpty())
        ws.forEach { s ->
            assertTrue("${s.name} port=${s.port}", s.port in listOf(80, 443, 8443, 2053, 2083, 2087, 2096) || s.port in 1..65535)
            // path should not contain '?', and should not be empty for WS xeovo nodes
            assertFalse("${s.name} path has query junk: ${s.path}", s.path.contains("?"))
            if (s.rawUri.contains("/?") || s.rawUri.matches(Regex("""vless://[^@]+@[^:]+:\d+/[^?]+\\?.*"""))) {
                // URI-path form: ensure path was recovered somehow
                println("WS ${s.name} path='${s.path}' host=${s.host}:${s.port}")
            }
        }
    }

    @Test
    fun matrixPlainBuilds() {
        val samples = pickSamples(servers())
        assumeTrue(samples.isNotEmpty())
        samples.forEach { s ->
            val json = ConfigBuilder.build(s, AppSettings())
            assertTrue(json.contains("\"protocol\":\"tun\""))
            assertTrue(json.contains("\"tag\":\"proxy\""))
            assertFalse("DoH default leaked", json.contains("dns-query"))
        }
    }

    @Test
    fun matrixTorStandalone() {
        val s = pickSamples(servers()).firstOrNull() ?: return
        val settings = AppSettings(tor = TorConfig(enabled = true))
        val json = ConfigBuilder.build(s, settings)
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertTrue(json.contains("\"outboundTag\":\"tor\""))
    }

    @Test
    fun matrixTorPlusDomainFront() {
        val s = pickSamples(servers()).firstOrNull() ?: return
        val settings = AppSettings(
            tor = TorConfig(enabled = true),
            domainFront = DomainFrontConfig(
                enabled = true,
                frontAddress = "104.19.229.21",
                fakeSni = "www.hcaptcha.com",
                listenHost = "127.0.0.1",
                listenPort = 40443
            ),
            routing = RoutingConfig(mode = RoutingMode.RULE, domainStrategy = "AsIs")
        )
        val json = ConfigBuilder.build(s, settings, domainFrontRunning = true)
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        assertTrue(json.contains("regexp:.*\\\\.onion"))
        // Must NOT steal all traffic when fronting
        assertFalse(json.contains("\"outboundTag\":\"tor\",\"network\":[\"tcp\",\"udp\"]"))
    }

    @Test
    fun matrixSniFragmentDesync() {
        val s = pickSamples(servers()).firstOrNull() ?: return
        val settings = AppSettings(
            sni = SniConfig(spoofEnabled = true, spoofDomain = "www.cloudflare.com", splitEnabled = true),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.SPLIT),
            routing = RoutingConfig(mode = RoutingMode.RULE, domainStrategy = "IPIfNonMatch")
        )
        val json = ConfigBuilder.build(s, settings, desyncRunning = true)
        assertTrue(json.contains("\"dialerProxy\":\"byedpi\"") || json.contains("\"tag\":\"byedpi\""))
        assertTrue(json.contains("IPIfNonMatch"))
    }

    @Test
    fun matrixAllFeaturesStacked() {
        val s = pickSamples(servers()).first { it.protocol == Protocol.VLESS || it.protocol == Protocol.TROJAN }
        val settings = AppSettings(
            tor = TorConfig(enabled = true),
            domainFront = DomainFrontConfig(
                enabled = true,
                frontAddress = "104.19.229.21",
                fakeSni = "www.cloudflare.com",
                listenPort = 40443
            ),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.DISORDER),
            sni = SniConfig(spoofEnabled = true, spoofDomain = "cdnjs.cloudflare.com", splitEnabled = true),
            warp = WarpConfig(enabled = false), // unregistered — must not crash
            dns = DnsConfig(remoteDns = "1.1.1.1", enableFakeDns = false),
            enableLocalDns = false,
            routing = RoutingConfig(mode = RoutingMode.RULE, domainStrategy = "IPIfNonMatch", blockAds = true)
        )
        val json = ConfigBuilder.build(s, settings, desyncRunning = true, domainFrontRunning = true)
        // Fronting wins over byedpi/fragment dialer
        assertFalse(json.contains("\"dialerProxy\":\"byedpi\""))
        assertFalse(json.contains("\"tag\":\"fragment\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertNotNull(json)
    }

    @Test
    fun buildForTestOmitsTunAndDpi() {
        val s = pickSamples(servers()).first()
        val dirty = AppSettings(
            tor = TorConfig(enabled = true),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.FAKE),
            domainFront = DomainFrontConfig(enabled = true, frontAddress = "1.2.3.4", fakeSni = "a.com")
        )
        val json = ConfigBuilder.buildForTest(s, dirty)
        assertFalse(json.contains("\"protocol\":\"tun\""))
        assertFalse(json.contains("\"tag\":\"byedpi\""))
        assertFalse(json.contains("\"tag\":\"fragment\""))
        assertFalse(json.contains("\"tag\":\"tor\""))
    }

    @Test
    fun liveTcpReachabilitySample() {
        val samples = pickSamples(servers(), perProto = 2)
        assumeTrue(samples.isNotEmpty())
        var ok = 0
        samples.forEach { s ->
            val ms = tcpMs(s.host, s.port)
            println("TCP ${s.protocol} ${s.name} ${s.host}:${s.port} -> ${if (ms > 0) "$ms ms" else "FAIL"}")
            if (ms > 0) ok++
        }
        assertTrue("expected some TCP successes, got $ok/${samples.size}", ok >= (samples.size / 2))
    }

    private fun pickSamples(all: List<Server>, perProto: Int = 1): List<Server> {
        val out = mutableListOf<Server>()
        for (p in listOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)) {
            out += all.filter { it.protocol == p }.take(perProto)
        }
        return out
    }

    private fun tcpMs(host: String, port: Int, timeout: Int = 5000): Long {
        val t0 = System.currentTimeMillis()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeout)
                (System.currentTimeMillis() - t0).coerceAtLeast(1)
            }
        }.getOrDefault(-1)
    }

    companion object {
        // User-provided test subscription (sideload QA only).
        const val SUB_URL =
            "https://xeovo.com/proxy/pw/LUSyhcYjwPoC1FvhQd95hrUacf6RtJX7/plain/config"
    }
}
