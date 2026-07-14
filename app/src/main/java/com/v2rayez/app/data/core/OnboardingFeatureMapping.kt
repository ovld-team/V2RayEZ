package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.OnboardingWants

/**
 * Maps welcome-wizard feature picks to concrete [AppSettings] flags and pack IDs queued in
 * [AppSettings.pendingAddonInstall]. Pure / unit-testable — no Android or network I/O.
 *
 * Rules:
 * - Only queue packs that are actually downloadable on-demand.
 * - Only flip settings that the app really honors (LAN share, analytics).
 * - Do **not** auto-start Tor / MITM (those need an explicit Tools screen + CA / VPN consent).
 */
object OnboardingFeatureMapping {

    /** Pack / core ids written into [AppSettings.pendingAddonInstall] for [wants]. */
    fun pendingPackIds(wants: OnboardingWants): List<String> = buildList {
        if (wants.tor) add("tor")
        if (wants.dpiBypass) add(AddonPackId.BYEDPI.name.lowercase())
        if (wants.processCores) {
            add("sing-box")
            add("mihomo")
        }
    }.distinct()

    /**
     * Apply wizard selections onto [base]: analytics + LAN share + merge pending pack queue.
     * Preserves unrelated settings and any packs already queued outside the wizard.
     */
    fun apply(base: AppSettings, wants: OnboardingWants): AppSettings {
        val normalized = wants.copy(analytics = wants.analytics)
        return base.copy(
            onboardingWants = normalized,
            analyticsConsent = normalized.analytics,
            enableLanSharing = if (normalized.hotspot) true else base.enableLanSharing,
            allowLan = if (normalized.hotspot) true else base.allowLan,
            pendingAddonInstall = (base.pendingAddonInstall + pendingPackIds(normalized)).distinct()
        )
    }
}
