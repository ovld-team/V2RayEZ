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
 * Built-in Browser state: MITM readiness, whether the standalone proxy is running,
 * HTTP proxy port for WebView [android.webkit.ProxyController], and API gate for proxy override.
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
        .map {
            it.status == ConnectionStatus.CONNECTED && it.server?.id == "mitm"
        }
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

    val httpPort: StateFlow<Int> = settings.settings()
        .map { it.mitm.httpPort }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10809)

    /** WebView ProxyController.setProxyOverride needs API 30+. */
    val proxyApiSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Banner "MITM active" when set up and proxy is up. */
    val ready: StateFlow<Boolean> = combine(
        mitmReady,
        proxyRunning,
        deviceTunnelRunning
    ) { configured, proxyOn, tunnelOn ->
        configured && (proxyOn || tunnelOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Entering the built-in browser should produce a usable tunnel, not just an inactive banner.
     * Capture-all sessions already route the WebView through VpnService; proxy-only mode starts
     * the local HTTP/SOCKS service that WebView's ProxyController consumes.
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
