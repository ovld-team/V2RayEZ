package com.v2rayez.app.data.core

import java.util.Locale

/**
 * Pure decision logic for the Iran ("ir") geo-routing bypass. Kept free of Android deps so it
 * is unit-testable on the JVM alongside [ConfigBuilder]. Device country detection (SIM /
 * network / locale) lives in [DeviceCountry]; the concrete config rules are emitted by
 * [ConfigBuilder.build] when [com.v2rayez.app.domain.model.RoutingConfig.bypassIran] is set.
 */
object IranRouting {

    /** ISO-3166 alpha-2 code for Iran. */
    const val COUNTRY_IR = "ir"

    /** True when [countryCode] denotes Iran (case/whitespace-insensitive). */
    fun isIran(countryCode: String?): Boolean =
        countryCode?.trim()?.lowercase(Locale.US) == COUNTRY_IR

    /**
     * Whether a one-shot auto-enable of the Iran bypass should fire now.
     *
     * Fires only when the device is in Iran, the full geo pack is installed (`geosite:ir` /
     * `geoip:ir` need it), it has not already been auto-applied, and the user has not already
     * turned the bypass on. The one-shot guard means we never fight a user who turned it back off.
     */
    fun shouldAutoEnable(
        countryCode: String?,
        geositeAvailable: Boolean,
        alreadyApplied: Boolean,
        alreadyBypassing: Boolean
    ): Boolean =
        isIran(countryCode) && geositeAvailable && !alreadyApplied && !alreadyBypassing

    /**
     * Whether to surface a "download the geo pack" CTA for Iran routing: the device is in Iran
     * but the full geo databases that back `geosite:ir` / `geoip:ir` are not installed yet.
     */
    fun shouldShowGeoPackCta(countryCode: String?, geositeAvailable: Boolean): Boolean =
        isIran(countryCode) && !geositeAvailable
}
