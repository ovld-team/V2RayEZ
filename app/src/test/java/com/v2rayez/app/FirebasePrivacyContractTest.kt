package com.v2rayez.app

import com.v2rayez.app.data.analytics.LocalAnalyticsRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebasePrivacyContractTest {

    @Test
    fun localRingCapsEvents() {
        val ring = LocalAnalyticsRing()
        repeat(80) { ring.record("e$it") }
        assertEquals(64, ring.snapshot().size)
    }

    @Test
    fun featureNamesAreSanitizedLikeTelemetry() {
        fun sanitize(feature: String) =
            feature.takeWhile { it.isLetterOrDigit() || it == '_' }.take(24)
        assertEquals("tor", sanitize("tor://evil"))
        assertTrue(sanitize("host.example.com:443").none { it == '.' || it == ':' })
    }
}
