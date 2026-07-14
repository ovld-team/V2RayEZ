package com.v2rayez.app

import com.v2rayez.app.data.analytics.PiiScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PiiScrubber] is the only thing standing between a server host/URI/bridge/subscription body
 * and the Sentry PRIMARY telemetry stream (see `RemoteTelemetry.kt`, `beforeSend`/`beforeBreadcrumb`
 * hooks). These tests pin down exactly what must never survive a scrub.
 */
class SentryPrivacyTest {

    @Test
    fun stripsBareHostname() {
        val scrubbed = PiiScrubber.scrub("connect failed for host.example.com:443")
        assertFalse(scrubbed.contains("host.example.com"))
        assertTrue(scrubbed.contains("[host]"))
    }

    @Test
    fun stripsProxyUris() {
        val samples = listOf(
            "vless://uuid-1234@evil-server.example.com:443?type=tcp",
            "vmess://eyJhZGQiOiJob3N0LmV4YW1wbGUuY29tIn0=",
            "trojan://password@203.0.113.5:443#front",
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@1.2.3.4:8388"
        )
        samples.forEach { raw ->
            val scrubbed = PiiScrubber.scrub(raw)
            assertTrue("expected [uri] marker for: $raw", scrubbed.contains("[uri]"))
            assertFalse("scheme body must not survive for: $raw", scrubbed.contains("@"))
        }
    }

    @Test
    fun stripsIpv4Addresses() {
        val scrubbed = PiiScrubber.scrub("TCP connect failed: 203.0.113.5:8443 unreachable")
        assertFalse(scrubbed.contains("203.0.113.5"))
        assertTrue(scrubbed.contains("[ip]"))
    }

    @Test
    fun stripsIpv6AddressesButKeepsTimestamps() {
        val scrubbed = PiiScrubber.scrub("exit probe failed for 2001:db8:85a3::8a2e:370:7334")
        assertFalse(scrubbed.contains("2001:db8"))
        assertTrue(scrubbed.contains("[ip]"))

        // A plain HH:MM:SS timestamp must never be mistaken for an IPv6 literal.
        val timestamp = PiiScrubber.scrub("connected at 14:32:10 after retry")
        assertEquals("connected at 14:32:10 after retry", timestamp)
    }

    @Test
    fun stripsPemBlocks() {
        val pem = """
            -----BEGIN CERTIFICATE-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA
            -----END CERTIFICATE-----
        """.trimIndent()
        val scrubbed = PiiScrubber.scrub("CA install failed:\n$pem")
        assertFalse(scrubbed.contains("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA"))
        assertTrue(scrubbed.contains("[pem]"))
    }

    @Test
    fun stripsTorBridgeLines() {
        val bridge = "obfs4 192.0.2.1:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA cert=abc iat-mode=0"
        val scrubbed = PiiScrubber.scrub("Using bundled bridge: $bridge")
        assertFalse(scrubbed.contains("192.0.2.1"))
        assertFalse(scrubbed.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(scrubbed.contains("[bridge]"))
    }

    @Test
    fun stripsSubscriptionBodyBlobs() {
        // A long base64 run with no dots/colons/scheme — the shape of a pasted subscription
        // body/config blob, which none of the other (host/URI/IP) patterns would catch.
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5YWJjZGVmZ2hpams="
        val scrubbed = PiiScrubber.scrub("subscription body: $blob")
        assertFalse(scrubbed.contains(blob))
        assertTrue(scrubbed.contains("[b64]"))
    }

    @Test
    fun leavesPlainMessagesUntouched() {
        assertEquals("Timed out", PiiScrubber.scrub("Timed out"))
        assertEquals("Tor bootstrap timed out (exit not ready)", PiiScrubber.scrub("Tor bootstrap timed out (exit not ready)"))
    }

    @Test
    fun nullInputStaysNull() {
        assertNull(PiiScrubber.scrubOrNull(null))
    }
}
