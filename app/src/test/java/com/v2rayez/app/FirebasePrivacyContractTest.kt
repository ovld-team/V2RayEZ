package com.v2rayez.app

import com.v2rayez.app.data.analytics.LocalAnalyticsRing
import com.v2rayez.app.data.analytics.sanitizedForCrashlytics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    // Crashlytics has no beforeSend hook — sanitizedForCrashlytics() is the only scrub boundary
    // on that stream (14-P0-1). Mirrors SentryPrivacyTest's coverage of the Sentry side.

    @Test
    fun scrubsHostFromExceptionMessage() {
        val original = IllegalStateException("connect failed for host.example.com:443")
        val sanitized = sanitizedForCrashlytics(original)
        assertFalse(sanitized.message.orEmpty().contains("host.example.com"))
        assertTrue(sanitized.message.orEmpty().contains("[host]"))
        // Grouping should still work: same exception type, same stack trace.
        assertEquals(original.javaClass, sanitized.javaClass)
        assertEquals(original.stackTrace.toList(), sanitized.stackTrace.toList())
    }

    @Test
    fun leavesCleanExceptionUntouched() {
        val original = IllegalStateException("Timed out")
        // No PII pattern matched → must be the exact same instance (no unnecessary rebuild).
        assertSame(original, sanitizedForCrashlytics(original))
    }

    @Test
    fun scrubsCauseChain() {
        val cause = RuntimeException("unreachable 203.0.113.5:8443")
        val wrapper = IllegalStateException("vpn start failed", cause)
        val sanitized = sanitizedForCrashlytics(wrapper)
        assertNotSame(wrapper, sanitized)
        val sanitizedCause = sanitized.cause
        assertTrue(sanitizedCause != null && !sanitizedCause.message.orEmpty().contains("203.0.113.5"))
    }

    @Test
    fun fallsBackToRuntimeExceptionWithoutStringConstructor() {
        class NoStringCtorException : RuntimeException("failed for host.example.com")
        val sanitized = sanitizedForCrashlytics(NoStringCtorException())
        assertFalse(sanitized.message.orEmpty().contains("host.example.com"))
    }

    @Test
    fun truncatesDeepCauseChainsInsteadOfLeakingThemUnscrubbed() {
        // Build a cause chain deeper than MAX_CAUSE_DEPTH (5), with PII at the very bottom.
        var deepest: Throwable = RuntimeException("root cause at host.example.com")
        repeat(7) { i -> deepest = RuntimeException("wrapper $i", deepest) }
        val sanitized = sanitizedForCrashlytics(deepest)

        // Walk the sanitized chain — no cause at any depth may contain the raw host.
        var cur: Throwable? = sanitized
        var depth = 0
        while (cur != null) {
            assertFalse(
                "cause at depth $depth leaked PII: ${cur.message}",
                cur.message.orEmpty().contains("host.example.com")
            )
            cur = cur.cause
            depth++
        }
    }
}
