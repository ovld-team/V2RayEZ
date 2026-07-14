package com.v2rayez.app.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the user-selected UI language without AppCompat. The chosen BCP-47 tag
 * is mirrored into a plain [android.content.SharedPreferences] so it can be read
 * synchronously from [android.app.Activity.attachBaseContext] (DataStore is async
 * and unavailable that early), and the activity is recreated when it changes.
 */
object LocaleHelper {

    private const val PREFS = "v2rayez_locale"
    private const val KEY_TAG = "locale_tag"

    /** Map the human-readable Settings language label to a BCP-47 language tag. */
    fun tagForLanguage(language: String): String = SupportedLanguages.tagForLabel(language)

    fun savedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "en") ?: "en"

    fun persistTag(context: Context, tag: String) {
        // commit() (synchronous) — the caller recreates the activity right after,
        // and attachBaseContext must observe the new tag.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).commit()
    }

    /** Wrap [context] with a configuration overriding the locale to [tag].
     *  Language/layout follow [tag]; numeric formatting stays ASCII via Locale.US. */
    fun wrap(context: Context, tag: String): Context {
        val locale = Locale.forLanguageTag(tag)
        // Avoid Locale.Category setters — some API 26 OEM builds crash / ignore Category
        // variants; DISPLAY+FORMAT via setDefault(Locale) is enough for UI resources.
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
