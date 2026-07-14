package com.v2rayez.app

import com.v2rayez.app.data.core.AbsoluteByteDelta
import com.v2rayez.app.data.core.hevTrafficDeltas
import com.v2rayez.app.data.service.TOR_TUN_DNS_SERVER
import com.v2rayez.app.data.service.TunnelHealthSnapshot
import com.v2rayez.app.data.service.dnsIpsForTun
import com.v2rayez.app.data.service.needsReconnectTeardown
import com.v2rayez.app.data.service.protocolRuntimeAvailable
import com.v2rayez.app.data.service.torSupportsServerProtocol
import com.v2rayez.app.data.service.tunnelDeathReason
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.DnsConfig
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.torEffectiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecyclePolicyTest {

    @Test
    fun absoluteHevCountersBecomeIntervalDeltas() {
        val delta = AbsoluteByteDelta()

        assertEquals(0L to 0L, delta.update(tx = 1_000L, rx = 2_000L))
        assertEquals(250L to 750L, delta.update(tx = 1_250L, rx = 2_750L))
        assertEquals(0L to 0L, delta.update(tx = 10L, rx = 20L))
        assertEquals(5L to 15L, delta.update(tx = 15L, rx = 35L))
    }

    @Test
    fun resetDropsOldHevBaseline() {
        val delta = AbsoluteByteDelta()
        delta.update(tx = 100L, rx = 200L)
        delta.reset()

        assertEquals(0L to 0L, delta.update(tx = 500L, rx = 900L))
    }

    @Test
    fun hevTunDirectionsMapRxToDownloadAndTxToUpload() {
        assertEquals(700L to 300L, hevTrafficDeltas(txDelta = 300L, rxDelta = 700L))
    }

    @Test
    fun reconnectTearsDownForAnyOwnedTunnelResource() {
        for (mask in 0 until 8) {
            val tunnel = mask and 1 != 0
            val engine = mask and 2 != 0
            val tun = mask and 4 != 0
            assertEquals(
                "mask=$mask",
                mask != 0,
                needsReconnectTeardown(tunnel, engine, tun)
            )
        }
    }

    @Test
    fun torSupportsOnlyCompatibleSelectedServerProtocols() {
        assertTrue(torSupportsServerProtocol(Protocol.VLESS))
        assertTrue(torSupportsServerProtocol(Protocol.WIREGUARD))
        assertFalse(torSupportsServerProtocol(Protocol.SSH))
        assertFalse(torSupportsServerProtocol(Protocol.DNSTUNNEL))
        assertFalse(torSupportsServerProtocol(Protocol.PSIPHON))
    }

    @Test
    fun torDnsPortUsesTunPeerInsteadOfStrippedLoopbackPort() {
        val settings = AppSettings(
            tor = TorConfig(enabled = true, dnsPort = 9053, routeAllDevice = false)
        ).torEffectiveSettings()

        assertEquals("127.0.0.1:9053", settings.dns.remoteDns)
        assertEquals(listOf(TOR_TUN_DNS_SERVER), dnsIpsForTun(settings))
        assertFalse(dnsIpsForTun(settings).contains("127.0.0.1"))
    }

    @Test
    fun torDnsPeerRequiresAnEnabledLocalDnsPath() {
        val torWithoutDns = AppSettings(
            tor = TorConfig(enabled = true),
            enableLocalDns = false,
            dns = DnsConfig(remoteDns = "9.9.9.9", domesticDns = "8.8.8.8", enableFakeDns = false)
        )

        assertEquals(listOf("9.9.9.9", "8.8.8.8"), dnsIpsForTun(torWithoutDns))
        assertEquals(
            listOf(TOR_TUN_DNS_SERVER),
            dnsIpsForTun(torWithoutDns.copy(enableLocalDns = true))
        )
    }

    @Test
    fun nonTorDnsFiltersHostnamesPortsAndDuplicates() {
        val settings = AppSettings(
            tor = TorConfig(enabled = false),
            dns = DnsConfig(
                remoteDns = "https://1.1.1.1/dns-query",
                domesticDns = "1.1.1.1:53"
            )
        )

        assertEquals(listOf("1.1.1.1"), dnsIpsForTun(settings))
        assertTrue(
            dnsIpsForTun(settings.copy(dns = settings.dns.copy(remoteDns = "dns.example")))
                .all { it != "dns.example" }
        )
    }

    @Test
    fun deathWatchdogIdentifiesOwnedResourceFailures() {
        assertEquals(
            "VPN tunnel interface closed unexpectedly",
            tunnelDeathReason(TunnelHealthSnapshot(tunPresent = false))
        )
        assertEquals(
            "Tor engine stopped unexpectedly",
            tunnelDeathReason(TunnelHealthSnapshot(torRequired = true, torConnected = false))
        )
        assertEquals(
            "Domain fronting engine stopped unexpectedly",
            tunnelDeathReason(
                TunnelHealthSnapshot(domainFrontRequired = true, domainFrontRunning = false)
            )
        )
        assertEquals(
            "ByeDPI engine stopped unexpectedly",
            tunnelDeathReason(TunnelHealthSnapshot(byeDpiRequired = true, byeDpiRunning = false))
        )
    }

    @Test
    fun deathWatchdogDistinguishesProcessCoreStandaloneAndHevFailures() {
        assertEquals(
            "Mihomo process stopped unexpectedly",
            tunnelDeathReason(
                TunnelHealthSnapshot(
                    processCore = true,
                    primaryEngineHealthy = false,
                    hevHealthy = false,
                    coreLabel = "Mihomo"
                )
            )
        )
        assertEquals(
            "TUN bridge stopped unexpectedly",
            tunnelDeathReason(
                TunnelHealthSnapshot(processCore = true, primaryEngineHealthy = true, hevHealthy = false)
            )
        )
        assertEquals(
            "Psiphon engine stopped unexpectedly",
            tunnelDeathReason(
                TunnelHealthSnapshot(protocol = Protocol.PSIPHON, primaryEngineHealthy = false)
            )
        )
        assertEquals(
            "DNS tunnel engine stopped unexpectedly",
            tunnelDeathReason(
                TunnelHealthSnapshot(protocol = Protocol.DNSTUNNEL, primaryEngineHealthy = false)
            )
        )
        assertEquals(null, tunnelDeathReason(TunnelHealthSnapshot()))
    }

    @Test
    fun protocolRuntimeGateDistinguishesMissingAndPresentPacks() {
        assertFalse(protocolRuntimeAvailable(Protocol.PSIPHON, singBoxAvailable = true, addonAvailable = false))
        assertTrue(protocolRuntimeAvailable(Protocol.PSIPHON, singBoxAvailable = false, addonAvailable = true))
        assertFalse(protocolRuntimeAvailable(Protocol.DNSTUNNEL, singBoxAvailable = true, addonAvailable = false))
        assertTrue(protocolRuntimeAvailable(Protocol.DNSTUNNEL, singBoxAvailable = false, addonAvailable = true))
    }

    @Test
    fun sshRuntimeGateRequiresSingBoxButOrdinaryProtocolsDoNot() {
        assertFalse(protocolRuntimeAvailable(Protocol.SSH, singBoxAvailable = false, addonAvailable = true))
        assertTrue(protocolRuntimeAvailable(Protocol.SSH, singBoxAvailable = true, addonAvailable = false))
        assertTrue(protocolRuntimeAvailable(Protocol.VLESS, singBoxAvailable = false, addonAvailable = false))
    }
}
