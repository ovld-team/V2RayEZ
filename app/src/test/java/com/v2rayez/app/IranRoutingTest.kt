package com.v2rayez.app

import com.v2rayez.app.data.core.IranRouting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision logic behind the Iran geo-routing bypass (auto-enable + geo-pack CTA). */
class IranRoutingTest {

    @Test
    fun isIranAcceptsCaseAndWhitespaceVariants() {
        assertTrue(IranRouting.isIran("ir"))
        assertTrue(IranRouting.isIran("IR"))
        assertTrue(IranRouting.isIran("  Ir  "))
        assertFalse(IranRouting.isIran("us"))
        assertFalse(IranRouting.isIran(""))
        assertFalse(IranRouting.isIran(null))
    }

    @Test
    fun autoEnableFiresOnlyWhenIranPlusGeoPackAndNotYetApplied() {
        assertTrue(
            IranRouting.shouldAutoEnable(
                countryCode = "ir",
                geositeAvailable = true,
                alreadyApplied = false,
                alreadyBypassing = false
            )
        )
    }

    @Test
    fun autoEnableSkippedWhenGeoPackMissing() {
        assertFalse(
            IranRouting.shouldAutoEnable(
                countryCode = "ir",
                geositeAvailable = false,
                alreadyApplied = false,
                alreadyBypassing = false
            )
        )
    }

    @Test
    fun autoEnableNeverRepeatsOrFightsUser() {
        // One-shot guard: already applied.
        assertFalse(
            IranRouting.shouldAutoEnable("ir", geositeAvailable = true, alreadyApplied = true, alreadyBypassing = false)
        )
        // User already turned it on — nothing to do.
        assertFalse(
            IranRouting.shouldAutoEnable("ir", geositeAvailable = true, alreadyApplied = false, alreadyBypassing = true)
        )
    }

    @Test
    fun autoEnableSkippedOutsideIran() {
        assertFalse(
            IranRouting.shouldAutoEnable("us", geositeAvailable = true, alreadyApplied = false, alreadyBypassing = false)
        )
    }

    @Test
    fun geoPackCtaShownOnlyInIranWithoutPack() {
        assertTrue(IranRouting.shouldShowGeoPackCta("ir", geositeAvailable = false))
        assertFalse(IranRouting.shouldShowGeoPackCta("ir", geositeAvailable = true))
        assertFalse(IranRouting.shouldShowGeoPackCta("us", geositeAvailable = false))
        assertFalse(IranRouting.shouldShowGeoPackCta(null, geositeAvailable = false))
    }
}
