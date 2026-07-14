package com.v2rayez.app

import com.v2rayez.app.data.core.OnboardingFeatureMapping
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.OnboardingWants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFeatureMappingTest {

    @Test
    fun pendingPackIds_emptyWhenNothingSelected() {
        assertTrue(OnboardingFeatureMapping.pendingPackIds(OnboardingWants()).isEmpty())
    }

    @Test
    fun pendingPackIds_mapsConcretePacks() {
        val wants = OnboardingWants(tor = true, dpiBypass = true, processCores = true, mitm = true)
        assertEquals(
            listOf("tor", "byedpi", "sing-box", "mihomo"),
            OnboardingFeatureMapping.pendingPackIds(wants)
        )
    }

    @Test
    fun mitmAndBrowser_doNotQueuePacks() {
        val wants = OnboardingWants(mitm = true, browser = true)
        assertTrue(OnboardingFeatureMapping.pendingPackIds(wants).isEmpty())
    }

    @Test
    fun apply_enablesLanShareAndAnalytics() {
        val base = AppSettings()
        val applied = OnboardingFeatureMapping.apply(
            base,
            OnboardingWants(hotspot = true, analytics = true, tor = true)
        )
        assertTrue(applied.enableLanSharing)
        assertTrue(applied.allowLan)
        assertTrue(applied.analyticsConsent)
        assertEquals(listOf("tor"), applied.pendingAddonInstall)
        assertFalse(applied.tor.enabled) // Tor stays off until Tools
        assertFalse(applied.mitm.enabled)
    }

    @Test
    fun apply_mergesPendingQueueFromWants() {
        val base = AppSettings(pendingAddonInstall = listOf("stale-pack"))
        val applied = OnboardingFeatureMapping.apply(base, OnboardingWants(processCores = true))
        assertEquals(listOf("stale-pack", "sing-box", "mihomo"), applied.pendingAddonInstall)
    }
}
