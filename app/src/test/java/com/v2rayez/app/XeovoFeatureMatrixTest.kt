package com.v2rayez.app

import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.core.SingBoxConfigBuilder
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.DesyncConfig
import com.v2rayez.app.domain.model.DesyncMode
import com.v2rayez.app.domain.model.DnsConfig
import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.FragmentConfig
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.model.RoutingConfig
import com.v2rayez.app.domain.model.RoutingMode
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.SniConfig
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorTransport
import com.v2rayez.app.domain.model.WarpConfig
import com.v2rayez.app.domain.model.WarpMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Xeovo feature matrix: named stacking scenarios + seeded random toggles. Offline by default
 * via [XeovoTestSupport] fixture; live fetch requires [XeovoTestSupport.LIVE_NETWORK_ENV]=1.
 */
class XeovoFeatureMatrixTest {

    private fun loadSub(): String = XeovoTestSupport.loadSub()

    private fun servers(): List<Server> =
        ProxyParser.parseMany(loadSub(), ServerGroup.SUBSCRIPTION, "xeovo-feat")

    private fun sample(): Server {
        val all = servers()
        assumeTrue("Xeovo parse empty", all.isNotEmpty())
        return all.first {
            it.protocol in listOf(Protocol.VLESS, Protocol.TROJAN, Protocol.VMESS, Protocol.SHADOWSOCKS)
        }
    }

    private fun samples(): List<Server> {
        val all = servers()
        assumeTrue(all.isNotEmpty())
        val out = mutableListOf<Server>()
        for (p in listOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)) {
            all.firstOrNull { it.protocol == p }?.let { out += it }
        }
        return out.ifEmpty { listOf(all.first()) }
    }

    private fun front(enabled: Boolean = true) = DomainFrontConfig(
        enabled = enabled,
        frontAddress = "104.19.229.21",
        fakeSni = "www.hcaptcha.com",
        listenHost = "127.0.0.1",
        listenPort = 40443
    )

    private fun warpConfigured(
        enabled: Boolean = true,
        mode: WarpMode = WarpMode.OUTBOUND
    ) = WarpConfig(
        enabled = enabled,
        mode = mode,
        privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
        addresses = listOf("172.16.0.2/32"),
        endpoint = "engage.cloudflareclient.com:2408"
    )

    private fun assertValidJson(label: String, json: String) {
        assertTrue("$label: empty", json.isNotBlank())
        assertTrue("$label: missing tun", json.contains("\"protocol\":\"tun\"") || json.contains("\"tag\":\"proxy\""))
        assertFalse("$label: raw exception text", json.contains("Exception"))
        assertFalse("$label: null literal leak", json.contains(":null,") && json.contains("\"tag\":null"))
    }

    // ── Named stacking scenarios ───────────────────────────────────────────

    @Test
    fun torFullDevice_tcpCatchAllToTor_noWarpFragmentDesync() {
        val s = sample()
        val settings = AppSettings(
            tor = TorConfig(enabled = true, transport = TorTransport.OBFS4, routeAllDevice = true),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.SPLIT),
            warp = warpConfigured(enabled = true),
            sni = SniConfig(spoofEnabled = true, spoofDomain = "www.cloudflare.com")
        )
        val json = ConfigBuilder.build(s, settings, desyncRunning = true, domainFrontRunning = false)
        println("torOnly len=${json.length}")
        assertValidJson("torOnly", json)
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertTrue(json.contains("\"outboundTag\":\"tor\",\"network\":[\"tcp\"]"))
        assertTrue(json.contains("\"outboundTag\":\"block\",\"network\":[\"udp\"]"))
        assertFalse("WARP must be off under tor standalone", json.contains("\"protocol\":\"wireguard\""))
        assertFalse("fragment must be off under tor standalone", json.contains("\"tag\":\"fragment\""))
        assertFalse("byedpi must be off under tor standalone", json.contains("\"tag\":\"byedpi\""))
    }

    @Test
    fun torPlusDomainFront_builderDoesNotAddTorRoutingFallback() {
        val s = sample()
        val settings = AppSettings(
            tor = TorConfig(enabled = true),
            domainFront = front(true),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.DISORDER),
            warp = warpConfigured(true)
        )
        val json = ConfigBuilder.build(s, settings, desyncRunning = true, domainFrontRunning = true)
        println("tor+front len=${json.length}")
        assertValidJson("tor+front", json)
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        // V2RayVpnService rejects this mutually-exclusive combination before building a config.
        // Keep the builder defensive too: do not create a partial onion/catch-all Tor route.
        assertFalse(json.contains("regexp:.*\\\\.onion"))
        assertFalse(json.contains("\"outboundTag\":\"tor\""))
        assertFalse("WARP off when fronting", json.contains("\"protocol\":\"wireguard\""))
        assertFalse("byedpi off when fronting", json.contains("\"dialerProxy\":\"byedpi\""))
        assertFalse("fragment off when fronting", json.contains("\"tag\":\"fragment\""))
    }

    @Test
    fun torPlusWarp_warpDropped_torOwnsExit() {
        val s = sample()
        val settings = AppSettings(
            tor = TorConfig(enabled = true),
            warp = warpConfigured(true, WarpMode.OUTBOUND)
        )
        val json = ConfigBuilder.build(s, settings)
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertFalse(json.contains("\"protocol\":\"wireguard\""))
    }

    @Test
    fun warpOnly_outboundWireguard() {
        val s = sample()
        val settings = AppSettings(warp = warpConfigured(true, WarpMode.OUTBOUND))
        val json = ConfigBuilder.build(s, settings)
        assertValidJson("warpOnly", json)
        assertTrue(json.contains("\"protocol\":\"wireguard\""))
        assertFalse(json.contains("\"tag\":\"tor\""))
    }

    @Test
    fun warpFront_chainsServerThroughWarp() {
        val s = sample()
        val settings = AppSettings(warp = warpConfigured(true, WarpMode.FRONT))
        val json = ConfigBuilder.build(s, settings)
        assertValidJson("warpFront", json)
        assertTrue(json.contains("\"protocol\":\"wireguard\""))
        assertTrue(json.contains("\"dialerProxy\":\"warp\"") || json.contains("\"tag\":\"warp\""))
    }

    @Test
    fun sniFragmentOnly_addsFragmentDialer() {
        val s = sample()
        val settings = AppSettings(
            sni = SniConfig(spoofEnabled = true, spoofDomain = "cdnjs.cloudflare.com", splitEnabled = true),
            fragment = FragmentConfig(enabled = true)
        )
        val json = ConfigBuilder.build(s, settings)
        assertTrue(json.contains("\"tag\":\"fragment\""))
        assertTrue(json.contains("\"dialerProxy\":\"fragment\""))
    }

    @Test
    fun desyncOnly_addsByedpi() {
        val s = sample()
        val settings = AppSettings(
            desync = DesyncConfig(enabled = true, mode = DesyncMode.SPLIT),
            fragment = FragmentConfig(enabled = true) // desync wins over fragment
        )
        val json = ConfigBuilder.build(s, settings, desyncRunning = true)
        assertTrue(json.contains("\"tag\":\"byedpi\""))
        assertTrue(json.contains("\"dialerProxy\":\"byedpi\""))
        assertFalse(json.contains("\"tag\":\"fragment\""))
    }

    @Test
    fun allFeaturesEnabled_invalidTorFrontingHasNoTorRoute() {
        val s = sample()
        val settings = AppSettings(
            tor = TorConfig(enabled = true, transport = TorTransport.WEBTUNNEL, autoRotateBridges = true),
            domainFront = front(true),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.FAKE),
            sni = SniConfig(spoofEnabled = true, spoofDomain = "www.cloudflare.com", splitEnabled = true),
            warp = warpConfigured(true, WarpMode.OUTBOUND),
            dns = DnsConfig(remoteDns = "1.1.1.1", enableFakeDns = false),
            enableLocalDns = false,
            routing = RoutingConfig(mode = RoutingMode.RULE, domainStrategy = "IPIfNonMatch", blockAds = true),
            defaultCore = ProxyCoreType.XRAY
        )
        val json = ConfigBuilder.build(s, settings, desyncRunning = true, domainFrontRunning = true)
        println("allFeatures len=${json.length}")
        assertValidJson("allFeatures", json)
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        assertTrue(json.contains("geosite:category-ads-all") || json.contains("IPIfNonMatch"))
        assertFalse(json.contains("\"protocol\":\"wireguard\""))
        assertFalse(json.contains("\"dialerProxy\":\"byedpi\""))
        assertFalse(json.contains("\"tag\":\"fragment\""))
        assertFalse(json.contains("\"outboundTag\":\"tor\""))
    }

    @Test
    fun namedScenarios_acrossXeovoProtocols() {
        val scenarios = listOf(
            "plain" to AppSettings(),
            "tor" to AppSettings(tor = TorConfig(enabled = true)),
            "front" to AppSettings(domainFront = front(true)),
            "tor+front" to AppSettings(tor = TorConfig(enabled = true), domainFront = front(true)),
            "warp" to AppSettings(warp = warpConfigured(true)),
            "tor+warp" to AppSettings(tor = TorConfig(enabled = true), warp = warpConfigured(true)),
            "frag" to AppSettings(fragment = FragmentConfig(enabled = true)),
            "desync" to AppSettings(desync = DesyncConfig(enabled = true, mode = DesyncMode.SPLIT)),
            "sni+frag" to AppSettings(
                sni = SniConfig(spoofEnabled = true, spoofDomain = "www.microsoft.com", splitEnabled = true),
                fragment = FragmentConfig(enabled = true)
            ),
            "rule+ads" to AppSettings(
                routing = RoutingConfig(mode = RoutingMode.RULE, bypassLan = true, blockAds = true)
            )
        )
        var builds = 0
        for (server in samples()) {
            for ((name, base) in scenarios) {
                val frontRunning = base.domainFront.enabled
                val desyncRunning = base.desync.enabled && base.desync.mode != DesyncMode.NONE
                val json = ConfigBuilder.build(
                    server,
                    base,
                    desyncRunning = desyncRunning,
                    domainFrontRunning = frontRunning
                )
                assertValidJson("${server.protocol}/$name", json)
                when (name) {
                    "tor" -> {
                        assertTrue(json.contains("\"tag\":\"tor\""))
                        assertTrue(json.contains("\"dialerProxy\":\"tor\""))
                        assertFalse(json.contains("\"outboundTag\":\"tor\""))
                    }
                    "tor+front" -> {
                        assertFalse(json.contains("regexp:.*\\\\.onion"))
                        assertFalse(json.contains("\"outboundTag\":\"tor\""))
                    }
                    "tor+warp" -> assertFalse(json.contains("\"protocol\":\"wireguard\""))
                    "warp" -> assertTrue(json.contains("\"protocol\":\"wireguard\""))
                }
                builds++
            }
        }
        println("namedScenarios builds=$builds")
        assertTrue(builds >= 20)
    }

    // ── Seeded random feature toggles ──────────────────────────────────────

    @Test
    fun randomFeatureCombos_seeded_neverCrash_stackingHolds() {
        val s = sample()
        val rng = Random(0xE070_2026L) // fixed seed → reproducible
        val failures = mutableListOf<String>()
        var ok = 0
        repeat(64) { i ->
            val torOn = rng.nextBoolean()
            val frontOn = rng.nextBoolean()
            val warpOn = rng.nextBoolean()
            val fragOn = rng.nextBoolean()
            val desyncOn = rng.nextBoolean()
            val sniOn = rng.nextBoolean()
            val warpMode = if (rng.nextBoolean()) WarpMode.OUTBOUND else WarpMode.FRONT
            val desyncMode = listOf(DesyncMode.SPLIT, DesyncMode.DISORDER, DesyncMode.FAKE, DesyncMode.TLSREC)
                .random(rng)
            val settings = AppSettings(
                tor = TorConfig(
                    enabled = torOn,
                    transport = listOf(
                        TorTransport.DIRECT,
                        TorTransport.OBFS4,
                        TorTransport.SNOWFLAKE,
                        TorTransport.WEBTUNNEL
                    ).random(rng)
                ),
                domainFront = front(frontOn),
                warp = warpConfigured(warpOn, warpMode),
                fragment = FragmentConfig(enabled = fragOn),
                desync = DesyncConfig(enabled = desyncOn, mode = if (desyncOn) desyncMode else DesyncMode.NONE),
                sni = SniConfig(
                    spoofEnabled = sniOn,
                    spoofDomain = if (sniOn) "www.cloudflare.com" else "",
                    splitEnabled = sniOn
                ),
                routing = RoutingConfig(
                    mode = if (rng.nextBoolean()) RoutingMode.GLOBAL else RoutingMode.RULE,
                    blockAds = rng.nextBoolean(),
                    bypassLan = rng.nextBoolean()
                )
            )
            val label =
                "r$i tor=$torOn front=$frontOn warp=$warpOn/$warpMode frag=$fragOn desync=$desyncOn sni=$sniOn"
            try {
                val json = ConfigBuilder.build(
                    s,
                    settings,
                    desyncRunning = desyncOn,
                    domainFrontRunning = frontOn
                )
                assertValidJson(label, json)

                val selectedServerOverTor = torOn && !frontOn
                if (selectedServerOverTor) {
                    assertTrue("$label missing tor tag", json.contains("\"tag\":\"tor\""))
                    assertTrue("$label missing Tor dialer chain", json.contains("\"dialerProxy\":\"tor\""))
                    assertFalse("$label unexpected Tor catch-all", json.contains("\"outboundTag\":\"tor\""))
                    assertFalse("$label WARP leaked", json.contains("\"protocol\":\"wireguard\""))
                    assertFalse("$label fragment leaked", json.contains("\"tag\":\"fragment\""))
                    assertFalse("$label byedpi leaked", json.contains("\"tag\":\"byedpi\""))
                }
                if (torOn && frontOn) {
                    assertFalse("$label unexpected onion fallback", json.contains("regexp:.*\\\\.onion"))
                    assertFalse("$label unexpected Tor route", json.contains("\"outboundTag\":\"tor\""))
                    assertFalse("$label WARP with front", json.contains("\"protocol\":\"wireguard\""))
                }
                if (frontOn) {
                    assertFalse("$label byedpi with front", json.contains("\"dialerProxy\":\"byedpi\""))
                    assertFalse("$label fragment with front", json.contains("\"tag\":\"fragment\""))
                }
                if (!torOn && !frontOn && warpOn) {
                    assertTrue("$label missing wireguard", json.contains("\"protocol\":\"wireguard\""))
                }
                if (!torOn && !frontOn && desyncOn) {
                    assertTrue("$label missing byedpi", json.contains("\"tag\":\"byedpi\""))
                }
                if (!torOn && !frontOn && !desyncOn && fragOn) {
                    assertTrue("$label missing fragment", json.contains("\"tag\":\"fragment\""))
                }
                ok++
            } catch (t: Throwable) {
                failures += "$label → ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        failures.take(8).forEach { println("FAIL $it") }
        println("randomFeatureCombos ok=$ok/64 failures=${failures.size}")
        assertTrue("random failures: ${failures.take(3)}", failures.isEmpty())
        assertTrue(ok == 64)
    }

    @Test
    fun singBoxBuilds_forXeovo_ignoreTorWiring() {
        // Process cores do not tunnel via Tor; build must still succeed for plain settings.
        var n = 0
        for (s in samples()) {
            val json = SingBoxConfigBuilder.build(s, AppSettings(), socksPort = 10808)
            assertTrue(json.contains("inbounds") || json.contains("outbounds"))
            n++
        }
        println("singBoxBuilds n=$n")
        assertTrue(n >= 1)
    }

    @Test
    fun buildForTest_stripsDpiTorTun_acrossCombos() {
        val s = sample()
        val dirty = AppSettings(
            tor = TorConfig(enabled = true),
            fragment = FragmentConfig(enabled = true),
            desync = DesyncConfig(enabled = true, mode = DesyncMode.FAKE),
            domainFront = front(true),
            warp = warpConfigured(true)
        )
        val json = ConfigBuilder.buildForTest(s, dirty)
        assertFalse(json.contains("\"protocol\":\"tun\""))
        assertFalse(json.contains("\"tag\":\"byedpi\""))
        assertFalse(json.contains("\"tag\":\"fragment\""))
        assertFalse(json.contains("\"tag\":\"tor\""))
    }
}
