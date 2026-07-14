package com.v2rayez.app

import com.v2rayez.app.data.repository.RealVpnController
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.ui.viewmodel.FreeServersViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FreeServersViewModel.pickScanSample] backs the free-servers "Quick scan" chip: it must
 * never scan the whole 6800-entry aggregator list, and repeated scans should prefer rows
 * that have not been tested yet so users keep discovering new working servers.
 */
class FreeScanSampleTest {

    private fun server(i: Int) = Server(
        id = "id-$i",
        name = "s$i",
        country = "",
        countryCode = "",
        protocol = Protocol.VLESS,
        transport = "TCP",
        security = "TLS",
        sni = "",
        address = "host$i.example.com:443",
        pingMs = 0,
        signal = 0,
        group = ServerGroup.MANUAL,
        host = "host$i.example.com",
        port = 443
    )

    @Test
    fun smallListReturnedWhole() {
        val list = (1..5).map(::server)
        assertEquals(list, FreeServersViewModel.pickScanSample(list, sample = 200))
    }

    @Test
    fun sampleIsCapped() {
        val list = (1..1000).map(::server)
        assertEquals(200, FreeServersViewModel.pickScanSample(list, sample = 200).size)
    }

    @Test
    fun untestedRowsPreferredOverTested() {
        val list = (1..100).map(::server)
        val tested = list.take(60).map { it.id }.toSet()
        val picked = FreeServersViewModel.pickScanSample(list, sample = 40, testedIds = tested)
        assertEquals(40, picked.size)
        assertTrue("sample should be entirely untested rows", picked.all { it.id !in tested })
    }

    @Test
    fun testedRowsTopUpWhenUntestedRunOut() {
        val list = (1..50).map(::server)
        val tested = list.take(40).map { it.id }.toSet()
        val picked = FreeServersViewModel.pickScanSample(list, sample = 30, testedIds = tested)
        assertEquals(30, picked.size)
        val untestedPicked = picked.count { it.id !in tested }
        assertEquals("all 10 untested rows must be included", 10, untestedPicked)
    }

    @Test
    fun displayNamesUseProtocolHostPort() {
        val named = FreeServersViewModel.withStableDisplayNames(
            listOf(
                server(1).copy(protocol = Protocol.VLESS, host = "a.example.com", port = 443),
                server(2).copy(protocol = Protocol.VMESS, host = "b.example.com", port = 80)
            )
        )
        assertEquals("VLESS · a.example.com:443", named[0].name)
        assertEquals("VMESS · b.example.com:80", named[1].name)
    }

    @Test
    fun duplicateHostPortGetsHashSuffix() {
        val named = FreeServersViewModel.withStableDisplayNames(
            listOf(
                server(1).copy(protocol = Protocol.TROJAN, host = "same.example.com", port = 443),
                server(2).copy(protocol = Protocol.TROJAN, host = "same.example.com", port = 443),
                server(3).copy(protocol = Protocol.TROJAN, host = "same.example.com", port = 443)
            )
        )
        assertEquals("Trojan · same.example.com:443", named[0].name)
        assertEquals("Trojan · same.example.com:443 #2", named[1].name)
        assertEquals("Trojan · same.example.com:443 #3", named[2].name)
    }

    @Test
    fun timedOutHandshakeKeepsReachableTcpPing() {
        val result = RealVpnController.tcpResult("free-1", tcpMs = 37, failureMessage = "Timed out")

        assertTrue(result.success)
        assertEquals(37, result.pingMs)
    }

    @Test
    fun failedTcpProbeStaysUnreachable() {
        val result = RealVpnController.tcpResult("free-1", tcpMs = -1, failureMessage = "Timed out")

        assertTrue(!result.success)
        assertEquals(-1, result.pingMs)
        assertEquals("Timed out", result.message)
    }
}
