package com.v2rayez.app

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.DownloadMode
import com.v2rayez.app.domain.model.TorTransport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tolerant settings decoding used by DataStoreSettingsRepository:
 * a persisted blob containing enum values that no longer exist (e.g. a removed
 * "CONJURE" TorTransport) must decode with defaults instead of throwing and
 * silently wiping every user setting (theme, language, ...).
 */
class SettingsDecodeTest {

    // Mirrors the Json configuration in DataStoreSettingsRepository.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun unknownEnumValueCoercesToDefaultInsteadOfThrowing() {
        // "CONJURE" is not a defined TorTransport; it must coerce to the default (DIRECT).
        val legacy = """{"language":"فارسی","theme":"Dark","tor":{"enabled":true,"transport":"CONJURE"}}"""
        val decoded = json.decodeFromString(AppSettings.serializer(), legacy)
        assertEquals("Dark", decoded.theme)
        assertEquals("فارسی", decoded.language)
        assertEquals(true, decoded.tor.enabled)
        assertEquals(TorTransport.DIRECT, decoded.tor.transport)
    }

    @Test
    fun knownTransportDecodesToItself() {
        val blob = """{"tor":{"enabled":true,"transport":"OBFS4"}}"""
        val decoded = json.decodeFromString(AppSettings.serializer(), blob)
        assertEquals(TorTransport.OBFS4, decoded.tor.transport)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val legacy = """{"theme":"Light","someRemovedSetting":42}"""
        val decoded = json.decodeFromString(AppSettings.serializer(), legacy)
        assertEquals("Light", decoded.theme)
    }

    @Test
    fun roundTripPreservesSettings() {
        val settings = AppSettings(theme = "Dark", language = "Русский", mtu = 1400)
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals(settings, decoded)
    }

    @Test
    fun unknownDownloadModeCoercesToAuto() {
        val blob = """{"downloadMode":"SPACE_LASER","bootAutoConnect":true,"pendingAddonInstall":["tor"]}"""
        val decoded = json.decodeFromString(AppSettings.serializer(), blob)
        assertEquals(DownloadMode.AUTO, decoded.downloadMode)
        assertTrue(decoded.bootAutoConnect)
        assertEquals(listOf("tor"), decoded.pendingAddonInstall)
    }

    @Test
    fun f1FieldsRoundTrip() {
        val settings = AppSettings(
            bootAutoConnect = true,
            autoConnect = false,
            downloadMode = DownloadMode.THROUGH,
            pendingAddonInstall = listOf("tor", "sing-box"),
            analyticsConsent = true,
            crashlyticsConsent = false
        )
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals(DownloadMode.THROUGH, decoded.downloadMode)
        assertTrue(decoded.bootAutoConnect)
        assertFalse(decoded.autoConnect)
        assertEquals(listOf("tor", "sing-box"), decoded.pendingAddonInstall)
        assertTrue(decoded.analyticsConsent)
        assertFalse(decoded.crashlyticsConsent)
    }
}
