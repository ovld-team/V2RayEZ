package com.v2rayez.app

import com.v2rayez.app.data.net.UrlSafety
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress

class UrlSafetyTest {

    // ---- host / address denylist ----

    @Test
    fun blocksLoopbackAddresses() {
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("::1")))
    }

    @Test
    fun blocksRfc1918PrivateAddresses() {
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("172.16.1.1")))
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("192.168.1.1")))
    }

    @Test
    fun blocksLinkLocalAndCloudMetadataAddress() {
        // 169.254.169.254 is the AWS/GCP/Azure metadata endpoint — link-local range covers it.
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("169.254.1.1")))
    }

    @Test
    fun blocksWildcardAndMulticastAddresses() {
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("0.0.0.0")))
        assertTrue(UrlSafety.isBlockedAddress(InetAddress.getByName("224.0.0.1")))
    }

    @Test
    fun allowsOrdinaryPublicAddress() {
        // 8.8.8.8 (public DNS) is a plain global unicast address — none of the blocked classes.
        assertTrue(!UrlSafety.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
    }

    // ---- assertSafe: scheme + resolved-address gate ----

    @Test
    fun assertSafeRejectsLoopbackUrl() {
        assertThrows(UrlSafety.UnsafeUrlException::class.java) {
            UrlSafety.assertSafe("http://127.0.0.1:8080/sub")
        }
    }

    @Test
    fun assertSafeRejectsPrivateLanUrl() {
        assertThrows(UrlSafety.UnsafeUrlException::class.java) {
            UrlSafety.assertSafe("http://10.1.2.3/rules.txt")
        }
    }

    @Test
    fun assertSafeRejectsCloudMetadataUrl() {
        assertThrows(UrlSafety.UnsafeUrlException::class.java) {
            UrlSafety.assertSafe("http://169.254.169.254/latest/meta-data/")
        }
    }

    @Test
    fun assertSafeRejectsNonHttpScheme() {
        assertThrows(UrlSafety.UnsafeUrlException::class.java) {
            UrlSafety.assertSafe("file:///etc/hosts")
        }
    }

    @Test
    fun assertSafeAllowsPublicIpLiteral() {
        // No exception expected for a plain public address.
        UrlSafety.assertSafe("http://8.8.8.8/sub.txt")
    }

    // ---- body size cap ----

    @Test
    fun readBoundedReturnsFullTextUnderCap() {
        val text = "line1\nline2\n"
        val result = UrlSafety.readBounded(ByteArrayInputStream(text.toByteArray()), maxBytes = 1024)
        assertTrue(result == text)
    }

    @Test
    fun readBoundedThrowsWhenBodyExceedsCap() {
        val big = ByteArray(2048) { 'a'.code.toByte() }
        assertThrows(IOException::class.java) {
            UrlSafety.readBounded(ByteArrayInputStream(big), maxBytes = 1024)
        }
    }

    @Test
    fun readBoundedAllowsExactlyAtCap() {
        val exact = ByteArray(1024) { 'x'.code.toByte() }
        val result = UrlSafety.readBounded(ByteArrayInputStream(exact), maxBytes = 1024)
        assertTrue(result.length == 1024)
    }
}
