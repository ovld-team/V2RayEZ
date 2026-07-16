package com.v2rayez.app

import com.v2rayez.app.data.analytics.VpnFailureKeys
import com.v2rayez.app.data.analytics.classifyVpnFailureKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnFailureKeysTest {

    @Test
    fun classifiesNoServerIncludingFa() {
        assertEquals(VpnFailureKeys.NO_SERVER, classifyVpnFailureKey("No server selected"))
        assertEquals(VpnFailureKeys.NO_SERVER, classifyVpnFailureKey("[vpn_connect] سروری انتخاب نشده"))
    }

    @Test
    fun classifiesGeoIranEof() {
        val msg =
            "Core failed to start — illegal domain rule: geosite:ir > common/geodata: failed to check code IR > EOF"
        assertEquals(VpnFailureKeys.GEO_IR, classifyVpnFailureKey(msg))
    }

    @Test
    fun classifiesPortInUseAndCoreDied() {
        assertEquals(
            VpnFailureKeys.PORT_IN_USE,
            classifyVpnFailureKey("listen tcp :10808 bind: address already in use")
        )
        assertEquals(
            VpnFailureKeys.CORE_DIED,
            classifyVpnFailureKey("Xray core stopped unexpectedly")
        )
    }

    @Test
    fun expectedUxKeysExcludeCrashlyticsNoise() {
        assertTrue(VpnFailureKeys.NO_SERVER in VpnFailureKeys.EXPECTED_UX_KEYS)
        assertTrue(VpnFailureKeys.MITM_CA in VpnFailureKeys.EXPECTED_UX_KEYS)
        assertTrue(VpnFailureKeys.MITM_PROBE in VpnFailureKeys.EXPECTED_UX_KEYS)
        assertTrue(VpnFailureKeys.TOR_PROBE in VpnFailureKeys.EXPECTED_UX_KEYS)
        assertTrue(VpnFailureKeys.GEO_IR !in VpnFailureKeys.EXPECTED_UX_KEYS)
    }

    @Test
    fun classifiesMitmAndTorProbes() {
        assertEquals(
            VpnFailureKeys.MITM_PROBE,
            classifyVpnFailureKey("MITM tunnel probe failed")
        )
        assertEquals(
            VpnFailureKeys.TOR_PROBE,
            classifyVpnFailureKey("Tor SOCKS/exit/DNS probe failed")
        )
        assertEquals(
            VpnFailureKeys.MITM_CA,
            classifyVpnFailureKey("Install and acknowledge the domain fronting CA before connecting")
        )
    }

    @Test
    fun classifiesOutboundBuild() {
        assertEquals(
            VpnFailureKeys.OUTBOUND_BUILD,
            classifyVpnFailureKey("failed to build outbound config with tag proxy > stream settings")
        )
    }
}
