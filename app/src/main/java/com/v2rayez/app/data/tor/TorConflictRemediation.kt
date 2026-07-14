package com.v2rayez.app.data.tor

import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Clears Tor full-tunnel / Tor-enabled settings that block VPN, MITM, or domain fronting,
 * awaits a clean VPN disconnect when a Tor device session is up, and optionally stops the daemon.
 */
object TorConflictRemediation {

    private const val DISCONNECT_TIMEOUT_MS = 15_000L

    fun isTorDeviceSession(vpn: VpnController): Boolean {
        val st = vpn.connectionState.value
        return (st.status == ConnectionStatus.CONNECTED || st.status == ConnectionStatus.CONNECTING) &&
            st.server?.id == "tor-device"
    }

    fun isMitmDeviceSession(vpn: VpnController): Boolean {
        val st = vpn.connectionState.value
        return (st.status == ConnectionStatus.CONNECTED || st.status == ConnectionStatus.CONNECTING) &&
            st.server?.id == "mitm"
    }

    /**
     * @param disableEnabled when true, also clears [com.v2rayez.app.domain.model.TorConfig.enabled]
     *   and stops the Tor daemon (needed for MITM capture and domain-fronting mutex).
     * @return false if a connected Tor device session did not disconnect before timeout —
     *   callers must not proceed with the gated action.
     */
    suspend fun disableTorBlocking(
        settings: SettingsRepository,
        vpn: VpnController,
        torController: TorController? = null,
        disableEnabled: Boolean = false
    ): Boolean {
        val torSession = isTorDeviceSession(vpn)
        settings.update {
            it.copy(
                tor = it.tor.copy(
                    enabled = if (disableEnabled) false else it.tor.enabled,
                    routeAllDevice = false
                )
            )
        }
        if (torSession && !awaitDisconnected(vpn)) return false
        if (disableEnabled) {
            runCatching { torController?.stop() }
        }
        return true
    }

    /**
     * Clears MITM whole-device capture so Tor full tunnel can start.
     * @return false if a connected MITM device session did not disconnect before timeout.
     */
    suspend fun disableMitmCaptureBlocking(
        settings: SettingsRepository,
        vpn: VpnController
    ): Boolean {
        val mitmSession = isMitmDeviceSession(vpn)
        settings.update {
            it.copy(mitm = it.mitm.copy(captureAllApps = false))
        }
        if (mitmSession && !awaitDisconnected(vpn)) return false
        return true
    }

    /** @return true when VPN is DISCONNECTED (or already was); false on timeout while still up. */
    private suspend fun awaitDisconnected(vpn: VpnController): Boolean {
        vpn.disconnect()
        val done = withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
            vpn.connectionState.first { it.status == ConnectionStatus.DISCONNECTED }
        }
        if (done != null) return true
        val st = vpn.connectionState.value.status
        return st == ConnectionStatus.DISCONNECTED
    }
}
