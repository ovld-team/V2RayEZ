package com.v2rayez.app.data.vpn

import com.v2rayez.app.domain.model.AppProxyConfig
import com.v2rayez.app.domain.model.AppSettings

/**
 * Pure policy for Android VpnService per-app routing.
 * Empty allow-list (proxy-only mode with no packages) must not leave the tunnel
 * without any allowed apps — fall back to self-exclude full-device.
 */
object PerAppTunnelPolicy {

    enum class Mode {
        /** Route all apps except [selfPackage]. */
        FULL_DEVICE_EXCEPT_SELF,
        /** Bypass listed packages (+ self); everyone else is tunneled. */
        BYPASS_LIST,
        /** Only listed packages are tunneled (allow-list). */
        ALLOW_LIST
    }

    data class Decision(
        val mode: Mode,
        val packages: Set<String>,
        /** True when UI App Proxy is on but policy fell back (empty allow / conflict). */
        val degradedToFullDevice: Boolean = false,
        val conflictWithFullDeviceTunnel: Boolean = false
    )

    fun decide(settings: AppSettings, selfPackage: String): Decision {
        val proxy = settings.appProxy
        val conflict = settings.fullDeviceTunnel && proxy.enabled && proxy.packages.isNotEmpty()
        if (settings.fullDeviceTunnel || !proxy.enabled) {
            return Decision(
                mode = Mode.FULL_DEVICE_EXCEPT_SELF,
                packages = setOf(selfPackage),
                conflictWithFullDeviceTunnel = conflict
            )
        }
        val pkgs = proxy.packages.filter { it.isNotBlank() }.toSet()
        if (pkgs.isEmpty()) {
            // Allow mode with empty list: VpnService would tunnel nothing — harden.
            return Decision(
                mode = Mode.FULL_DEVICE_EXCEPT_SELF,
                packages = setOf(selfPackage),
                degradedToFullDevice = true
            )
        }
        return if (proxy.bypassMode) {
            Decision(
                mode = Mode.BYPASS_LIST,
                packages = pkgs + selfPackage,
                conflictWithFullDeviceTunnel = conflict
            )
        } else {
            val allow = pkgs.filter { it != selfPackage }.toSet()
            if (allow.isEmpty()) {
                Decision(
                    mode = Mode.FULL_DEVICE_EXCEPT_SELF,
                    packages = setOf(selfPackage),
                    degradedToFullDevice = true
                )
            } else {
                Decision(mode = Mode.ALLOW_LIST, packages = allow)
            }
        }
    }

    /** Human-readable reason for Settings / App Proxy UI. */
    fun conflictMessage(settings: AppSettings): String? {
        val d = decide(settings, "com.v2rayez.app")
        return when {
            d.conflictWithFullDeviceTunnel ->
                "Full device tunnel overrides App Proxy until you turn Full device tunnel off."
            d.degradedToFullDevice && settings.appProxy.enabled ->
                "App Proxy allow-list is empty — routing all apps (excluding V2RayEz)."
            else -> null
        }
    }

    fun applyToBuilder(
        addDisallowed: (String) -> Unit,
        addAllowed: (String) -> Unit,
        decision: Decision
    ) {
        when (decision.mode) {
            Mode.FULL_DEVICE_EXCEPT_SELF ->
                decision.packages.forEach { runCatching { addDisallowed(it) } }
            Mode.BYPASS_LIST ->
                decision.packages.forEach { runCatching { addDisallowed(it) } }
            Mode.ALLOW_LIST ->
                decision.packages.forEach { runCatching { addAllowed(it) } }
        }
    }
}
