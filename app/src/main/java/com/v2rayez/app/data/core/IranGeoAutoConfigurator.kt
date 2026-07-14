package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import com.v2rayez.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot startup hook that turns on the Iran geo-routing bypass when the device is detected in
 * Iran and the full geo databases are installed. Guarded by [AppSettings.iranBypassAutoApplied]
 * so it fires at most once and never overrides a user who later toggles the bypass off.
 *
 * When the device is in Iran but the geo pack is missing, nothing is auto-applied — the Home /
 * Core-manager CTA (see [IranRouting.shouldShowGeoPackCta]) prompts the download instead.
 */
@Singleton
class IranGeoAutoConfigurator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geoAssets: GeoAssetManager,
    private val settings: SettingsRepository
) {

    suspend fun applyIfNeeded() {
        runCatching {
            val current = settings.current()
            if (current.iranBypassAutoApplied) return
            val country = DeviceCountry.detect(context)
            val geosite = geoAssets.geositeAvailable()
            if (!IranRouting.isIran(country)) return
            if (IranRouting.shouldAutoEnable(
                    countryCode = country,
                    geositeAvailable = geosite,
                    alreadyApplied = current.iranBypassAutoApplied,
                    alreadyBypassing = current.routing.bypassIran
                )
            ) {
                settings.update {
                    it.copy(
                        iranBypassAutoApplied = true,
                        routing = it.routing.copy(bypassIran = true)
                    )
                }
                Log.i(TAG, "Iran detected + geo pack present — enabled geoip:ir/geosite:ir bypass")
            } else if (current.routing.bypassIran && geosite) {
                // User (or a prior build) already bypassing — latch the one-shot so toggling off
                // later does not get overridden on the next cold start.
                settings.update { it.copy(iranBypassAutoApplied = true) }
            } else if (!geosite) {
                Log.i(TAG, "Iran detected but geo pack missing — surfacing download CTA")
            }
        }.onFailure { Log.w(TAG, "Iran geo auto-config skipped", it) }
    }

    private companion object {
        const val TAG = "IranGeoAutoConfig"
    }
}
