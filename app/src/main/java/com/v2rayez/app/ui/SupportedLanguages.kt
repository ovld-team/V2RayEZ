package com.v2rayez.app.ui

/**
 * Single source of truth for UI languages shipped with the app.
 * English (US), Persian, and Russian — Arabic and Chinese are not supported.
 */
object SupportedLanguages {

    const val ENGLISH = "English (US)"
    const val PERSIAN = "Persian"
    const val RUSSIAN = "Russian"

    val labels: List<String> = listOf(ENGLISH, PERSIAN, RUSSIAN)

    private val labelToTag = mapOf(
        ENGLISH to "en",
        PERSIAN to "fa",
        RUSSIAN to "ru",
    )

    /** Legacy labels from removed locales or old backups — all map to English. */
    private val legacyLabels = setOf(
        "Arabic",
        "Chinese",
        "Simplified Chinese",
        "Traditional Chinese",
        "简体中文",
        "繁體中文",
        "العربية",
    )

    fun tagForLabel(label: String): String = labelToTag[label] ?: "en"

    /** Returns a supported label, rewriting legacy/unknown values to [ENGLISH]. */
    fun normalizeLabel(label: String): String = when {
        label in labelToTag -> label
        label in legacyLabels -> ENGLISH
        else -> ENGLISH
    }

    fun isSupported(label: String): Boolean = label in labelToTag

    /** Normalize [language] field on persisted settings. */
    fun normalizeSettings(settings: com.v2rayez.app.domain.model.AppSettings): com.v2rayez.app.domain.model.AppSettings {
        val normalized = normalizeLabel(settings.language)
        return if (normalized == settings.language) settings else settings.copy(language = normalized)
    }
}
