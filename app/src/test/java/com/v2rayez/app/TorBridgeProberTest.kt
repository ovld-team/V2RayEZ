package com.v2rayez.app

import com.v2rayez.app.data.tor.TorBridgeProber
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.domain.model.TorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBridgeProberTest {

    @Test
    fun transportPreferenceOrderMatchesProbe() {
        val preference = listOf(
            TorTransport.OBFS4,
            TorTransport.DIRECT,
            TorTransport.SNOWFLAKE,
            TorTransport.WEBTUNNEL,
            TorTransport.MEEK,
            TorTransport.VANILLA
        )
        assertEquals(TorTransport.OBFS4, preference.first())
        assertTrue(preference.indexOf(TorTransport.DIRECT) < preference.indexOf(TorTransport.SNOWFLAKE))
    }

    @Test
    fun probeEventCarriesMessage() {
        val ev = TorBridgeProber.ProbeEvent("Trying obfs4…")
        assertEquals("Trying obfs4…", ev.message)
    }

    @Test
    fun plausibleBridgeLinesAccepted() {
        assertTrue(
            TorController.isPlausibleBridgeLine(
                "obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=abc iat-mode=0"
            )
        )
        assertTrue(
            TorController.isPlausibleBridgeLine(
                "1.2.3.4:443 AABBCCDDEEFF00112233445566778899AABBCCDD"
            )
        )
        assertFalse(TorController.isPlausibleBridgeLine("<html>captcha</html>"))
        assertFalse(TorController.isPlausibleBridgeLine(""))
        assertFalse(TorController.isPlausibleBridgeLine("obfs4"))
    }
}
