package com.v2rayez.app

import com.v2rayez.app.data.service.legacyAutoConnectMigration
import com.v2rayez.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BootReceiver dropped the `bootAutoConnect || autoConnect` OR fallback (W-03 / boot-copy
 * honesty) — these tests pin that the one-time migration still preserves boot-reconnect for
 * users who only ever had the legacy `autoConnect` toggle on, without permanently re-coupling
 * the two settings.
 */
class BootAutoConnectMigrationTest {

    @Test
    fun legacyAutoConnectOnlyUserGetsBootAutoConnectGrantedOnce() {
        val legacy = AppSettings(autoConnect = true, bootAutoConnect = false)
        val migrated = legacyAutoConnectMigration(legacy)
        assertTrue(migrated.bootAutoConnect)
        assertTrue(migrated.legacyAutoConnectBootMigrated)
    }

    @Test
    fun freshInstallWithNeitherFlagStaysUnaffected() {
        val fresh = AppSettings(autoConnect = false, bootAutoConnect = false)
        val migrated = legacyAutoConnectMigration(fresh)
        assertFalse(migrated.bootAutoConnect)
        assertTrue(migrated.legacyAutoConnectBootMigrated)
    }

    @Test
    fun userWhoAlreadyHasBootAutoConnectIsUnaffected() {
        val explicit = AppSettings(autoConnect = false, bootAutoConnect = true)
        val migrated = legacyAutoConnectMigration(explicit)
        assertTrue(migrated.bootAutoConnect)
        assertTrue(migrated.legacyAutoConnectBootMigrated)
    }

    @Test
    fun migrationNeverReRunsOnceGuardIsSet() {
        // A user who was migrated, then explicitly turned bootAutoConnect back off, must stay
        // off — the migration must never re-grant it on a later boot.
        val alreadyMigratedAndOptedOut = AppSettings(
            autoConnect = true,
            bootAutoConnect = false,
            legacyAutoConnectBootMigrated = true
        )
        val result = legacyAutoConnectMigration(alreadyMigratedAndOptedOut)
        assertEquals(alreadyMigratedAndOptedOut, result)
        assertFalse(result.bootAutoConnect)
    }
}
