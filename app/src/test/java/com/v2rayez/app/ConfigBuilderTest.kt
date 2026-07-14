package com.v2rayez.app

import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.RoutingConfig
import com.v2rayez.app.domain.model.RoutingMode
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.torEffectiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    @Test
    fun rejectsMissingOrMalformedRealityPublicKey() {
        val base = com.v2rayez.app.data.parser.ProxyParser.parse(
            "vless://id@reality.example:443?security=reality&type=tcp&sni=www.example.com#Reality"
        )!!
        org.junit.Assert.assertTrue(ConfigBuilder.validate(base)!!.contains("missing"))
        org.junit.Assert.assertTrue(
            ConfigBuilder.validate(base.copy(publicKey = "not-a-key"))!!.contains("invalid")
        )
        org.junit.Assert.assertNull(
            ConfigBuilder.validate(base.copy(publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        )
    }

    @Test
    fun rejectsQuicAndKcpWithoutSilentTcpFallback() {
        val base = com.v2rayez.app.data.parser.ProxyParser.parse(
            "vless://id@transport.example:443?security=none&type=quic#QUIC"
        )!!
        org.junit.Assert.assertTrue(ConfigBuilder.validate(base)!!.contains("not supported"))
        org.junit.Assert.assertTrue(
            ConfigBuilder.validate(base.copy(network = "kcp"))!!.contains("not supported")
        )
    }

    private fun vless() = ProxyParser.parse(
        "vless://id-9@srv.example.com:443?encryption=none&security=tls&type=ws&path=%2Fp&sni=srv.example.com#Node"
    )!!

    @Test
    fun buildsValidJsonWithProxyOutbound() {
        val json = ConfigBuilder.build(vless(), AppSettings())
        assertTrue(json.contains("\"protocol\":\"vless\""))
        assertTrue(json.contains("\"tag\":\"proxy\""))
        assertTrue(json.contains("\"tag\":\"direct\""))
        assertTrue(json.contains("\"tag\":\"block\""))
        assertTrue(json.contains("srv.example.com"))
        assertTrue(json.contains("\"network\":\"ws\""))
    }

    @Test
    fun ruleModeBypassesLanAndCn() {
        val settings = AppSettings(
            routing = RoutingConfig(mode = RoutingMode.RULE, bypassLan = true, bypassMainland = true)
        )
        val json = ConfigBuilder.build(vless(), settings, geositeAvailable = true)
        assertTrue(json.contains("geoip:private"))
        assertTrue(json.contains("geosite:cn"))
    }

    @Test
    fun geositeUnavailableStripsAllGeositeMatchers() {
        val settings = AppSettings(
            routing = RoutingConfig(
                mode = RoutingMode.RULE,
                bypassLan = true,
                bypassMainland = true,
                blockAds = true
            )
        )
        val json = ConfigBuilder.build(vless(), settings, geositeAvailable = false)
        assertFalse(json.contains("geosite:"))
        // geoip stays — the packaged mini geoip always backs private/cn.
        assertTrue(json.contains("geoip:private"))
        assertTrue(json.contains("geoip:cn"))
    }

    @Test
    fun geositeUnavailableSkipsDomesticDnsSplit() {
        val json = ConfigBuilder.build(vless(), AppSettings(), geositeAvailable = false)
        assertFalse(json.contains("geosite:cn"))
        assertFalse(json.contains("223.5.5.5"))
    }

    @Test
    fun geositeUnavailableDropsAnyRuleWithGeoMatcher() {
        val settings = AppSettings(
            routing = RoutingConfig(
                mode = RoutingMode.RULE,
                rules = listOf(
                    // Pure geosite rule.
                    com.v2rayez.app.domain.model.RoutingRule(
                        id = "r1",
                        remark = "ads",
                        outbound = com.v2rayez.app.domain.model.RuleOutbound.BLOCK,
                        domains = listOf("geosite:category-ads-all")
                    ),
                    // Mixed geosite + literal — dropped WHOLE: Xray ANDs matchers, so keeping
                    // only the literal domain would broaden the rule and leak.
                    com.v2rayez.app.domain.model.RoutingRule(
                        id = "r2",
                        remark = "mixed",
                        outbound = com.v2rayez.app.domain.model.RuleOutbound.DIRECT,
                        domains = listOf("geosite:cn", "domain:example.org")
                    ),
                    // Non-offline geoip tag — needs full geoip.dat, so dropped too.
                    com.v2rayez.app.domain.model.RoutingRule(
                        id = "r3",
                        remark = "telegram",
                        outbound = com.v2rayez.app.domain.model.RuleOutbound.PROXY,
                        ips = listOf("geoip:telegram")
                    ),
                    // Literal-only rule survives.
                    com.v2rayez.app.domain.model.RoutingRule(
                        id = "r4",
                        remark = "literal",
                        outbound = com.v2rayez.app.domain.model.RuleOutbound.DIRECT,
                        domains = listOf("domain:keep.example")
                    )
                )
            )
        )
        val json = ConfigBuilder.build(vless(), settings, geositeAvailable = false)
        assertFalse(json.contains("geosite:"))
        assertFalse(json.contains("category-ads-all"))
        assertFalse(json.contains("example.org"))
        assertFalse(json.contains("geoip:telegram"))
        assertTrue(json.contains("keep.example"))
    }

    @Test
    fun geositeUnavailableKeepsOfflineGeoipRules() {
        val settings = AppSettings(
            routing = RoutingConfig(
                mode = RoutingMode.RULE,
                rules = listOf(
                    com.v2rayez.app.domain.model.RoutingRule(
                        id = "r1",
                        remark = "cn-direct",
                        outbound = com.v2rayez.app.domain.model.RuleOutbound.DIRECT,
                        ips = listOf("geoip:cn", "geoip:private")
                    )
                )
            )
        )
        val json = ConfigBuilder.build(vless(), settings, geositeAvailable = false)
        // cn + private ship in the mini geoip, so this rule stays.
        assertTrue(json.contains("geoip:cn"))
        assertTrue(json.contains("geoip:private"))
    }

    @Test
    fun bypassIranEmitsGeositeAndGeoipIrWhenGeoPackPresent() {
        val settings = AppSettings(
            routing = RoutingConfig(mode = RoutingMode.RULE, bypassIran = true)
        )
        val json = ConfigBuilder.build(vless(), settings, geositeAvailable = true)
        assertTrue(json.contains("geosite:ir"))
        assertTrue(json.contains("geoip:ir"))
    }

    @Test
    fun bypassIranSuppressedWhenGeoPackMissing() {
        // Both halves of the Iran split live only in the full geo databases, so with the mini
        // geoip the whole block is dropped and the UI surfaces the download CTA instead.
        val settings = AppSettings(
            routing = RoutingConfig(mode = RoutingMode.RULE, bypassIran = true)
        )
        val json = ConfigBuilder.build(vless(), settings, geositeAvailable = false)
        assertFalse(json.contains("geosite:ir"))
        assertFalse(json.contains("geoip:ir"))
    }

    @Test
    fun torEnabledAddsTorOutbound() {
        val settings = AppSettings(tor = com.v2rayez.app.domain.model.TorConfig(enabled = true))
        val json = ConfigBuilder.build(vless(), settings)
        assertTrue(json.contains("\"tag\":\"tor\""))
    }

    @Test
    fun nonRouteAllTorForcesLocalFakeDnsAndDnsPort() {
        val raw = AppSettings(
            enableLocalDns = false,
            enableSniffing = false,
            tor = TorConfig(enabled = true, dnsPort = 9153, routeAllDevice = false)
        )

        val effective = raw.torEffectiveSettings()
        assertTrue(effective.enableLocalDns)
        assertTrue(effective.enableSniffing)
        assertTrue(effective.dns.enableFakeDns)
        assertEquals("127.0.0.1:9153", effective.dns.remoteDns)
        assertFalse(effective.tor.routeAllDevice)
        assertFalse(effective.fullDeviceTunnel)

        // ConfigBuilder also applies the runtime settings so non-service callers cannot regress.
        val json = ConfigBuilder.build(vless(), raw)
        assertTrue(json.contains("\"servers\":[\"fakedns\",\"127.0.0.1:9153\""))
        assertTrue(json.contains("\"outboundTag\":\"dns-out\",\"port\":\"53\""))
        assertTrue(json.indexOf("\"outboundTag\":\"dns-out\"") < json.indexOf("\"outboundTag\":\"tor\""))
    }

    @Test
    fun torEffectiveSettingsFallsBackToDefaultDnsPort() {
        val effective = AppSettings(
            enableLocalDns = false,
            tor = TorConfig(enabled = true, dnsPort = 0)
        ).torEffectiveSettings()

        assertEquals(9053, effective.tor.dnsPort)
        assertEquals("127.0.0.1:9053", effective.dns.remoteDns)
        assertTrue(effective.enableLocalDns)
        assertTrue(effective.dns.enableFakeDns)
    }

    @Test
    fun torStandaloneSkipsWarpOutbound() {
        val settings = AppSettings(
            tor = com.v2rayez.app.domain.model.TorConfig(enabled = true),
            warp = com.v2rayez.app.domain.model.WarpConfig(
                enabled = true,
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                peerPublicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                endpoint = "engage.cloudflareclient.com:2408",
                addresses = listOf("172.16.0.2/32"),
                mode = com.v2rayez.app.domain.model.WarpMode.OUTBOUND
            )
        )
        val json = ConfigBuilder.build(vless(), settings, domainFrontRunning = false)
        assertTrue(json.contains("\"outboundTag\":\"tor\""))
        assertFalse(json.contains("\"protocol\":\"wireguard\""))
    }

    @Test
    fun torStandaloneSkipsFragmentDialer() {
        val settings = AppSettings(
            tor = com.v2rayez.app.domain.model.TorConfig(enabled = true),
            fragment = com.v2rayez.app.domain.model.FragmentConfig(enabled = true)
        )
        val json = ConfigBuilder.build(vless(), settings)
        assertFalse(json.contains("\"tag\":\"fragment\""))
        assertTrue(json.contains("\"outboundTag\":\"tor\""))
    }

    @Test
    fun torBesideDomainFrontingKeepsProxyDefault() {
        val settings = AppSettings(
            tor = com.v2rayez.app.domain.model.TorConfig(enabled = true),
            domainFront = com.v2rayez.app.domain.model.DomainFrontConfig(
                enabled = true,
                frontAddress = "104.19.229.21",
                fakeSni = "www.hcaptcha.com",
                listenHost = "127.0.0.1",
                listenPort = 40443
            )
        )
        val json = ConfigBuilder.build(
            vless(),
            settings,
            domainFrontRunning = true
        )
        assertTrue(json.contains("\"tag\":\"tor\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        assertTrue(json.contains("regexp:.*\\\\.onion"))
        // Must NOT steal all traffic to Tor when fronting is active.
        assertTrue(!json.contains("\"outboundTag\":\"tor\",\"network\":[\"tcp\",\"udp\"]"))
    }

    @Test
    fun fragmentEnabledAddsFragmentOutboundAndDialer() {
        val settings = AppSettings(fragment = com.v2rayez.app.domain.model.FragmentConfig(enabled = true))
        val json = ConfigBuilder.build(vless(), settings)
        assertTrue(json.contains("\"tag\":\"fragment\""))
        assertTrue(json.contains("\"dialerProxy\":\"fragment\""))
    }

    @Test
    fun statsAndPolicyAlwaysPresent() {
        val json = ConfigBuilder.build(vless(), AppSettings())
        assertTrue(json.contains("\"stats\":{}"))
        assertTrue(json.contains("\"statsOutboundUplink\":true"))
        assertTrue(json.contains("\"statsOutboundDownlink\":true"))
    }

    @Test
    fun tunInboundAlwaysPresent() {
        val json = ConfigBuilder.build(vless(), AppSettings())
        assertTrue(json.contains("\"protocol\":\"tun\""))
        assertTrue(json.contains("\"tag\":\"tun-in\""))
    }

    @Test
    fun quicBlockedByDefault() {
        val json = ConfigBuilder.build(vless(), AppSettings())
        assertTrue(json.contains("quic"))
        assertTrue(json.contains("\"outboundTag\":\"block\""))
    }

    @Test
    fun desyncEnabledAddsByedpiOutboundAndDialer() {
        val settings = AppSettings(
            desync = com.v2rayez.app.domain.model.DesyncConfig(
                enabled = true,
                mode = com.v2rayez.app.domain.model.DesyncMode.SPLIT
            )
        )
        val json = ConfigBuilder.build(vless(), settings, desyncRunning = true)
        assertTrue(json.contains("\"tag\":\"byedpi\""))
        assertTrue(json.contains("\"dialerProxy\":\"byedpi\""))
    }

    @Test
    fun desyncNotEmittedWhenEngineNotRunning() {
        val settings = AppSettings(
            desync = com.v2rayez.app.domain.model.DesyncConfig(
                enabled = true,
                mode = com.v2rayez.app.domain.model.DesyncMode.SPLIT
            )
        )
        val json = ConfigBuilder.build(vless(), settings, desyncRunning = false)
        assertTrue(!json.contains("\"tag\":\"byedpi\""))
        assertTrue(!json.contains("\"dialerProxy\":\"byedpi\""))
    }

    @Test
    fun desyncTakesPrecedenceOverFragmentDialer() {
        val settings = AppSettings(
            fragment = com.v2rayez.app.domain.model.FragmentConfig(enabled = true),
            desync = com.v2rayez.app.domain.model.DesyncConfig(
                enabled = true,
                mode = com.v2rayez.app.domain.model.DesyncMode.DISORDER
            )
        )
        val json = ConfigBuilder.build(vless(), settings, desyncRunning = true)
        assertTrue(json.contains("\"dialerProxy\":\"byedpi\""))
        assertTrue(!json.contains("\"dialerProxy\":\"fragment\""))
    }

    @Test
    fun domainFrontingDialsLocalhostKeepsRealSni() {
        val settings = AppSettings(
            domainFront = com.v2rayez.app.domain.model.DomainFrontConfig(
                enabled = true,
                frontAddress = "104.19.229.21",
                fakeSni = "www.hcaptcha.com",
                listenHost = "127.0.0.1",
                listenPort = 40443
            ),
            fragment = com.v2rayez.app.domain.model.FragmentConfig(enabled = true),
            desync = com.v2rayez.app.domain.model.DesyncConfig(
                enabled = true,
                mode = com.v2rayez.app.domain.model.DesyncMode.SPLIT
            )
        )
        val json = ConfigBuilder.build(
            vless(),
            settings,
            desyncRunning = true,
            domainFrontRunning = true
        )
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        assertTrue(json.contains("\"port\":40443"))
        assertTrue(json.contains("\"serverName\":\"srv.example.com\""))
        assertTrue(!json.contains("www.hcaptcha.com"))
        assertTrue(!json.contains("\"dialerProxy\":\"byedpi\""))
        assertTrue(!json.contains("\"tag\":\"fragment\""))
    }

    @Test
    fun fakeDnsAddsFakednsServerAndPool() {
        val settings = AppSettings(
            dns = com.v2rayez.app.domain.model.DnsConfig(enableFakeDns = true)
        )
        val json = ConfigBuilder.build(vless(), settings)
        assertTrue(json.contains("fakedns"))
        assertTrue(json.contains("198.18.0.0/15"))
        assertTrue(json.contains("\"tag\":\"dns-out\""))
    }

    @Test
    fun localDnsRoutesPort53ToDnsHandler() {
        val json = ConfigBuilder.build(vless(), AppSettings(enableLocalDns = true))
        assertTrue(json.contains("\"tag\":\"dns-out\""))
        assertTrue(json.contains("\"outboundTag\":\"dns-out\""))
    }

    @Test
    fun buildForTestStripsFragmentAndDesync() {
        val settings = AppSettings(
            fragment = com.v2rayez.app.domain.model.FragmentConfig(enabled = true),
            desync = com.v2rayez.app.domain.model.DesyncConfig(
                enabled = true,
                mode = com.v2rayez.app.domain.model.DesyncMode.FAKE
            ),
            enableMux = true
        )
        val json = ConfigBuilder.buildForTest(vless(), settings)
        assertTrue(!json.contains("\"tag\":\"byedpi\""))
        assertTrue(!json.contains("\"tag\":\"fragment\""))
        assertTrue(!json.contains("\"dialerProxy\""))
    }

    @Test
    fun buildForTestStripsGlobalSniSpoofKeepsServerSni() {
        val settings = AppSettings(
            sni = com.v2rayez.app.domain.model.SniConfig(
                spoofEnabled = true,
                spoofDomain = "www.cloudflare.com"
            )
        )
        val json = ConfigBuilder.buildForTest(vless(), settings)
        assertTrue(json.contains("\"serverName\":\"srv.example.com\""))
        assertTrue(!json.contains("www.cloudflare.com"))
    }

    @Test
    fun muxDisabledWhenVisionFlow() {
        val server = vless().copy(flow = "xtls-rprx-vision")
        val json = ConfigBuilder.build(server, AppSettings(enableMux = true))
        assertTrue(!json.contains("\"mux\""))
    }

    @Test
    fun desyncFlagMappingProducesCiadpiArgs() {
        val fake = com.v2rayez.app.domain.model.DesyncConfig(
            enabled = true,
            mode = com.v2rayez.app.domain.model.DesyncMode.FAKE,
            splitPos = 2,
            useSniOffset = true,
            fakeTtl = 6,
            autoTrigger = true
        )
        val args = fake.toCiadpiArgs()
        assertTrue(args.contains("--auto=torst"))
        assertTrue(args.contains("--fake"))
        assertTrue(args.contains("2+s"))
        assertTrue(args.contains("--ttl"))
        assertTrue(args.contains("6"))
    }

    @Test
    fun tunInboundHasNameAndMtu() {
        val json = ConfigBuilder.build(vless(), AppSettings(mtu = 1280))
        assertTrue(json.contains("\"tag\":\"tun-in\""))
        assertTrue(json.contains("\"name\":\"xray0\""))
        assertTrue(json.contains("\"mtu\":1280"))
    }

    @Test
    fun buildForTestOmitsTunInbound() {
        val json = ConfigBuilder.buildForTest(vless(), AppSettings())
        assertTrue(!json.contains("\"protocol\":\"tun\""))
        assertTrue(!json.contains("\"tag\":\"tun-in\""))
        assertTrue(json.contains("\"tag\":\"socks-in\""))
    }

    @Test
    fun defaultDnsUsesPlainUdpNotDoh() {
        val json = ConfigBuilder.build(vless(), AppSettings())
        assertTrue(json.contains("1.1.1.1"))
        assertTrue(!json.contains("dns-query"))
    }

    @Test
    fun localDnsOffRoutesPort53Direct() {
        val json = ConfigBuilder.build(vless(), AppSettings(enableLocalDns = false))
        assertTrue(json.contains("\"outboundTag\":\"direct\""))
        assertTrue(json.contains("\"port\":\"53\""))
    }

    @Test
    fun torStandalonePlacesDnsAndLoopbackBeforeCatchAll() {
        val settings = AppSettings(
            tor = com.v2rayez.app.domain.model.TorConfig(enabled = true),
            enableLocalDns = true
        )
        val json = ConfigBuilder.build(vless(), settings)
        val dnsIdx = json.indexOf("\"outboundTag\":\"dns-out\"")
        val port53Idx = json.indexOf("\"port\":\"53\"")
        val loopbackIdx = json.indexOf(
            "\"type\":\"field\",\"outboundTag\":\"direct\",\"ip\":[\"127.0.0.1\",\"::1\"]"
        )
        val torCatchIdx = json.indexOf("\"outboundTag\":\"tor\"")
        assertTrue("dns-out rule missing", dnsIdx >= 0)
        assertTrue("port 53 rule missing", port53Idx >= 0)
        assertTrue("loopback direct missing", loopbackIdx >= 0)
        assertTrue("tor catch-all missing", torCatchIdx >= 0)
        assertTrue("port 53 must precede Tor catch-all", port53Idx < torCatchIdx)
        assertTrue("loopback direct must precede Tor catch-all", loopbackIdx < torCatchIdx)
        assertTrue(json.contains("\"tag\":\"dns-out\""))
    }
}
