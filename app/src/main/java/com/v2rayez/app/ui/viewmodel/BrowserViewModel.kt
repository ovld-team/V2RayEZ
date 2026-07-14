package com.v2rayez.app.ui.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.data.cert.MitmCaStore
import com.v2rayez.app.data.mock.MockMitmProxyController
import com.v2rayez.app.data.mock.MockSettingsRepository
import com.v2rayez.app.data.mock.MockVpnController
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.repository.MitmProxyController
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Built-in Browser state.
 *
 * The app package is disallowed from the VpnService TUN (so Xray/dialer sockets do not loop).
 * Chrome (another UID) uses the VPN; this WebView would otherwise hit clearnet and fail under
 * domain-front / censored networks. On API 30+ we point [android.webkit.ProxyController] at
 * Xray's local `http-in` (or MITM's HTTP port) so the in-app browser shares the tunnel.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val proxy: MitmProxyController,
    private val vpn: VpnController,
    @ApplicationContext private val context: Context?
) : ViewModel() {

    /** Preview / no-arg constructor. */
    constructor() : this(
        MockSettingsRepository(),
        MockMitmProxyController(),
        MockVpnController(),
        null
    )

    val mitmReady: StateFlow<Boolean> = settings.settings()
        .map { s ->
            s.mitm.enabled && s.mitm.caInstallAcknowledged &&
                (context?.let(MitmCaStore::isPresent) ?: false)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the standalone MITM SOCKS/HTTP proxy is listening. */
    val proxyRunning: StateFlow<Boolean> = proxy.running

    val deviceTunnelRunning: StateFlow<Boolean> = vpn.connectionState
        .map { it.status == ConnectionStatus.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val captureAllApps = settings.settings()
        .map { it.mitm.captureAllApps }

    val lastError: StateFlow<String?> = combine(
        proxy.lastError,
        vpn.connectionState,
        captureAllApps
    ) { proxyError, vpnState, captureAll ->
        proxyError ?: vpnState.errorMessage?.takeIf { captureAll }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Local HTTP proxy port for WebView: MITM when its proxy is up, otherwise Xray `http-in`
     * while the VPN is connected.
     */
    val httpPort: StateFlow<Int> = combine(
        settings.settings(),
        proxy.running,
        vpn.connectionState
    ) { s, mitmOn, conn ->
        val mitmSession = mitmOn ||
            (conn.status == ConnectionStatus.CONNECTED && conn.server?.id == "mitm")
        if (mitmSession) s.mitm.httpPort else s.httpPort
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10809)

    /** WebView ProxyController.setProxyOverride needs API 30+. */
    val proxyApiSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * True when WebView traffic must be forced through a loopback HTTP proxy because the
     * app UID is excluded from TUN (VPN up and/or MITM standalone proxy up).
     */
    val webViewProxyActive: StateFlow<Boolean> = combine(proxy.running, deviceTunnelRunning) {
            mitmOn, vpnOn -> mitmOn || vpnOn
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Banner: MITM configured + running, or any VPN tunnel (domain-front included). */
    val ready: StateFlow<Boolean> = combine(
        mitmReady,
        proxy.running,
        deviceTunnelRunning
    ) { configured, proxyOn, tunnelOn ->
        tunnelOn || (configured && proxyOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Entering the built-in browser should produce a usable tunnel when MITM is configured.
     * Capture-all / ordinary VPN already route Chrome via VpnService; WebView uses ProxyController.
     */
    fun ensureBrowserTunnel() {
        if (!mitmReady.value || proxy.running.value || deviceTunnelRunning.value) return
        viewModelScope.launch {
            val cfg = settings.current().mitm
            if (!cfg.captureAllApps) {
                proxy.clearError()
                proxy.start()
            }
        }
    }
}
