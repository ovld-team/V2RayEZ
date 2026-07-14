package com.v2rayez.app.ui

import com.v2rayez.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedLanguagesTest {

    @Test
    fun supportedLabels_mapToCorrectTags() {
        assertEquals("en", SupportedLanguages.tagForLabel(SupportedLanguages.ENGLISH))
        assertEquals("fa", SupportedLanguages.tagForLabel(SupportedLanguages.PERSIAN))
        assertEquals("ru", SupportedLanguages.tagForLabel(SupportedLanguages.RUSSIAN))
    }

    @Test
    fun legacyRemovedLocales_mapToEnglishTag() {
        listOf("Arabic", "Chinese", "Simplified Chinese", "Traditional Chinese").forEach { legacy ->
            assertEquals("en", SupportedLanguages.tagForLabel(legacy))
        }
    }

    @Test
    fun normalizeLabel_rewritesLegacyAndUnknown() {
        assertEquals(SupportedLanguages.ENGLISH, SupportedLanguages.normalizeLabel("Arabic"))
        assertEquals(SupportedLanguages.ENGLISH, SupportedLanguages.normalizeLabel("Chinese"))
        assertEquals(SupportedLanguages.ENGLISH, SupportedLanguages.normalizeLabel("Unknown Lang"))
        assertEquals(SupportedLanguages.PERSIAN, SupportedLanguages.normalizeLabel(SupportedLanguages.PERSIAN))
        assertEquals(SupportedLanguages.RUSSIAN, SupportedLanguages.normalizeLabel(SupportedLanguages.RUSSIAN))
    }

    @Test
    fun isSupported_englishPersianAndRussian() {
        assertTrue(SupportedLanguages.isSupported(SupportedLanguages.ENGLISH))
        assertTrue(SupportedLanguages.isSupported(SupportedLanguages.PERSIAN))
        assertTrue(SupportedLanguages.isSupported(SupportedLanguages.RUSSIAN))
        assertFalse(SupportedLanguages.isSupported("Arabic"))
    }

    @Test
    fun normalizeSettings_rewritesLegacyLanguageField() {
        val legacy = AppSettings(language = "Arabic")
        val fixed = SupportedLanguages.normalizeSettings(legacy)
        assertEquals(SupportedLanguages.ENGLISH, fixed.language)
    }

    @Test
    fun normalizeSettings_preservesSupportedLanguage() {
        val fa = AppSettings(language = SupportedLanguages.PERSIAN)
        assertEquals(fa, SupportedLanguages.normalizeSettings(fa))
    }
}

class LocaleHelperTest {

    @Test
    fun tagForLanguage_delegatesToSupportedLanguages() {
        assertEquals("fa", LocaleHelper.tagForLanguage(SupportedLanguages.PERSIAN))
        assertEquals("en", LocaleHelper.tagForLanguage("Chinese"))
    }
}
