package com.v2rayez.app

import com.v2rayez.app.data.repository.RealVpnController
import com.v2rayez.app.domain.model.TestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TestResult.mergeSiteFetch] and site probe helpers for ping + HTTP fetch probes. */
class SiteFetchTest {

    @Test
    fun mergeSiteFetchPreservesPingAndAddsSiteMetrics() {
        val ping = TestResult("s1", pingMs = 42, success = true)
        val site = TestResult(
            serverId = "s1",
            pingMs = 118,
            success = true,
            siteOk = true,
            siteMs = 118
        )

        val merged = ping.mergeSiteFetch(site)

        assertEquals(42, merged.pingMs)
        assertTrue(merged.success)
        assertEquals(true, merged.siteOk)
        assertEquals(118, merged.siteMs)
        assertNull(merged.siteMessage)
    }

    @Test
    fun mergeSiteFetchKeepsPingWhenSiteFails() {
        val ping = TestResult("s1", pingMs = 37, success = true)
        val site = RealVpnController.siteResult("s1", ms = -1L, success = false, message = "Timed out")

        val merged = ping.mergeSiteFetch(site)

        assertEquals(37, merged.pingMs)
        assertTrue(merged.success)
        assertFalse(merged.siteOk!!)
        assertEquals("Timed out", merged.siteMessage)
    }

    @Test
    fun siteResultMarksSuccessOnlyWhenDelayPositive() {
        val ok = RealVpnController.siteResult("s1", ms = 88L, success = true, message = "")
        val fail = RealVpnController.siteResult("s1", ms = -1L, success = false, message = "Site fetch probe failed")

        assertTrue(ok.siteOk!!)
        assertEquals(88, ok.siteMs)
        assertFalse(fail.siteOk!!)
        assertEquals(-1, fail.pingMs)
    }
}
