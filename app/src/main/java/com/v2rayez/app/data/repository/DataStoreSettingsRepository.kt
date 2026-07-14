package com.v2rayez.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.ui.SupportedLanguages
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "v2rayez_settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    // coerceInputValues: unknown enum values (e.g. a persisted TorTransport that was
    // removed in a later release) fall back to the property default instead of
    // throwing and wiping every setting.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    }
    private val key = stringPreferencesKey("app_settings_json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { repairPersistedSettingsIfNeeded() }
    }

    override fun settings(): Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs -> decode(prefs[key]) }

    override suspend fun current(): AppSettings = settings().first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val updated = SupportedLanguages.normalizeSettings(transform(decode(prefs[key])))
            prefs[key] = json.encodeToString(AppSettings.serializer(), updated)
        }
    }

    /**
     * One-time self-heal: re-encodes the persisted blob so stale values (legacy
     * language labels, removed enum entries) are rewritten in the current schema.
     */
    private suspend fun repairPersistedSettingsIfNeeded() {
        context.settingsDataStore.edit { prefs ->
            val raw = prefs[key] ?: return@edit
            val normalized = SupportedLanguages.normalizeSettings(parseRaw(raw) ?: AppSettings())
            val encoded = json.encodeToString(AppSettings.serializer(), normalized)
            if (encoded != raw) prefs[key] = encoded
        }
    }

    private fun decode(raw: String?): AppSettings =
        SupportedLanguages.normalizeSettings(parseRaw(raw) ?: AppSettings())

    private fun parseRaw(raw: String?): AppSettings? {
        if (raw == null) return null
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse {
                Log.w(TAG, "Settings JSON decode failed; using defaults", it)
                null
            }
    }

    companion object {
        private const val TAG = "DataStoreSettings"
    }
}
