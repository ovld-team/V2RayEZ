package com.v2rayez.app.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Standalone MITM Domain Fronting proxy (local SOCKS/HTTP via Xray, no VpnService).
 * Device-wide capture still goes through [VpnController] when `captureAllApps` is on.
 */
interface MitmProxyController {
    val running: StateFlow<Boolean>
    val lastError: StateFlow<String?>

    /** Start the foreground [com.v2rayez.app.data.service.MitmProxyService]. */
    fun start()

    /** Stop the standalone proxy service (does not stop a VPN capture session). */
    fun stop()

    fun toggle() {
        if (running.value) stop() else start()
    }

    fun clearError()
}
