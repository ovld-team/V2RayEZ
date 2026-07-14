package com.v2rayez.app

import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.domain.model.TorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards T1b bridge-line filtering: only bridges that match the selected pluggable
 * transport may reach the engine, so a torrc never mixes obfs4 lines under a
 * snowflake ClientTransportPlugin.
 */
class TorBridgeFilterTest {

    private val obfs4 =
        "obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=abc iat-mode=0"
    private val snowflake =
        "snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=xyz url=https://x"
    private val meekLite =
        "meek_lite 192.0.2.2:2 97700DFE9F483596DDA6264C4D7DF7641E1E39CE url=https://x front=y"
    private val webtunnel =
        "webtunnel 192.0.2.5:443 A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D url=https://w/x ver=0.0.1"
    private val vanilla =
        "1.2.3.4:443 AABBCCDDEEFF00112233445566778899AABBCCDD"

    @Test
    fun obfs4LineMatchesOnlyObfs4() {
        assertTrue(TorController.bridgeMatchesTransport(obfs4, TorTransport.OBFS4))
        assertFalse(TorController.bridgeMatchesTransport(obfs4, TorTransport.SNOWFLAKE))
        assertFalse(TorController.bridgeMatchesTransport(obfs4, TorTransport.VANILLA))
        assertFalse(TorController.bridgeMatchesTransport(obfs4, TorTransport.DIRECT))
    }

    @Test
    fun snowflakeLineMatchesOnlySnowflake() {
        assertTrue(TorController.bridgeMatchesTransport(snowflake, TorTransport.SNOWFLAKE))
        assertFalse(TorController.bridgeMatchesTransport(snowflake, TorTransport.OBFS4))
    }

    @Test
    fun meekTransportAcceptsMeekAndMeekLite() {
        assertTrue(TorController.bridgeMatchesTransport(meekLite, TorTransport.MEEK))
        assertTrue(
            TorController.bridgeMatchesTransport(
                "meek 192.0.2.2:2 97700DFE9F483596DDA6264C4D7DF7641E1E39CE url=https://x",
                TorTransport.MEEK
            )
        )
        assertFalse(TorController.bridgeMatchesTransport(meekLite, TorTransport.WEBTUNNEL))
    }

    @Test
    fun webtunnelLineMatchesOnlyWebtunnel() {
        assertTrue(TorController.bridgeMatchesTransport(webtunnel, TorTransport.WEBTUNNEL))
        assertFalse(TorController.bridgeMatchesTransport(webtunnel, TorTransport.OBFS4))
    }

    @Test
    fun webtunnelDocAddressWithUrlIsPlausible() {
        // Real Tor Project builtin webtunnel bridges use [2001:db8:...] placeholders; the
        // endpoint is the url= field, so these must NOT be filtered as dead samples.
        val real =
            "webtunnel [2001:db8:e421:f43:a161:e711:dab6:935]:443 " +
                "3129F12AD7018700175B084864AE19BD1CF49946 url=https://api.zhan.science/x ver=0.0.3"
        assertTrue(TorController.isPlausibleBridgeLine(real))
    }

    @Test
    fun snowflakeDocIpv4WithUrlIsPlausible() {
        assertTrue(TorController.isPlausibleBridgeLine(snowflake))
    }

    @Test
    fun docAddressWithoutUrlIsRejected() {
        // obfs4 dials the address directly, so a doc-net address with no url= is dead.
        assertFalse(
            TorController.isPlausibleBridgeLine(
                "obfs4 192.0.2.1:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=abc iat-mode=0"
            )
        )
        assertFalse(
            TorController.isPlausibleBridgeLine(
                "obfs4 [2001:db8::1]:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 cert=abc iat-mode=0"
            )
        )
    }

    @Test
    fun vanillaLineIsIpPortFingerprint() {
        assertTrue(TorController.bridgeMatchesTransport(vanilla, TorTransport.VANILLA))
        assertFalse(TorController.bridgeMatchesTransport(vanilla, TorTransport.OBFS4))
    }

    @Test
    fun bridgePrefixIsStrippedBeforeMatching() {
        assertTrue(TorController.bridgeMatchesTransport("Bridge $obfs4", TorTransport.OBFS4))
    }

    @Test
    fun emptyLineNeverMatches() {
        assertFalse(TorController.bridgeMatchesTransport("", TorTransport.OBFS4))
        assertFalse(TorController.bridgeMatchesTransport("   ", TorTransport.VANILLA))
    }

    @Test
    fun mixedListFiltersToSelectedTransportOnly() {
        val mixed = listOf(obfs4, snowflake, meekLite, webtunnel, vanilla)
        val onlyObfs4 = mixed.filter {
            TorController.isPlausibleBridgeLine(it) &&
                TorController.bridgeMatchesTransport(it, TorTransport.OBFS4)
        }
        assertEquals(listOf(obfs4), onlyObfs4)

        val onlySnowflake = mixed.filter {
            TorController.bridgeMatchesTransport(it, TorTransport.SNOWFLAKE)
        }
        assertEquals(listOf(snowflake), onlySnowflake)
    }
}
