package com.v2rayez.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.R
import com.v2rayez.app.data.cert.MitmCaGenerator
import com.v2rayez.app.data.cert.MitmCaImportResult
import com.v2rayez.app.data.cert.MitmCaInstallHelper
import com.v2rayez.app.data.cert.MitmCaInstallPlan
import com.v2rayez.app.data.cert.MitmCaStore
import com.v2rayez.app.data.mitm.MitmDesktopExporter
import com.v2rayez.app.data.mock.MockMitmProxyController
import com.v2rayez.app.data.mock.MockSettingsRepository
import com.v2rayez.app.data.mock.MockVpnController
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.MitmDomainFrontConfig
import com.v2rayez.app.domain.repository.MitmProxyController
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Powers the MITM Domain Fronting tool: persists [MitmDomainFrontConfig], CA lifecycle,
 * standalone [MitmProxyController] Start/Stop, and optional device-wide VPN capture.
 */
@HiltViewModel
class MitmDomainFrontingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val vpn: VpnController,
    private val proxy: MitmProxyController,
    @ApplicationContext private val context: Context?
) : ViewModel() {

    /** Preview / no-arg constructor. */
    constructor() : this(
        MockSettingsRepository(),
        MockVpnController(),
        MockMitmProxyController(),
        null
    )

    val mitm: StateFlow<MitmDomainFrontConfig> = settings.settings()
        .map { it.mitm }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MitmDomainFrontConfig())

    /** True when the standalone SOCKS/HTTP proxy ([MitmProxyService]) is listening. */
    val proxyRunning: StateFlow<Boolean> = proxy.running

    /**
     * True when the standalone proxy is up, or when capture-all is using the VPN MITM tunnel.
     */
    val connected: StateFlow<Boolean> = combine(
        proxy.running,
        vpn.connectionState,
        mitm
    ) { proxyOn, vpnState, cfg ->
        proxyOn || (cfg.captureAllApps && vpnState.status == ConnectionStatus.CONNECTED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the device-wide MITM VpnService session is connected. */
    val deviceTunnelRunning: StateFlow<Boolean> = combine(vpn.connectionState, mitm) { vpnState, cfg ->
        cfg.captureAllApps &&
            vpnState.status == ConnectionStatus.CONNECTED &&
            vpnState.server?.id == "mitm"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _caPresent = MutableStateFlow(false)
    val caPresent: StateFlow<Boolean> = _caPresent.asStateFlow()

    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint: StateFlow<String?> = _fingerprint.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshCaState()
        viewModelScope.launch {
            proxy.lastError.collect { err ->
                if (!err.isNullOrBlank()) _message.value = err
            }
        }
    }

    fun clearMessage() {
        _message.update { null }
        proxy.clearError()
    }

    private fun refreshCaState() {
        val ctx = context ?: return
        _caPresent.value = MitmCaStore.isPresent(ctx)
        _fingerprint.value = MitmCaStore.fingerprintSha256(ctx)
    }

    private fun edit(transform: (MitmDomainFrontConfig) -> MitmDomainFrontConfig) {
        viewModelScope.launch { settings.update { it.copy(mitm = transform(it.mitm)) } }
    }

    fun toggleEnabled(enabled: Boolean) {
        if (enabled && !isCaReady()) {
            _message.value = context?.getString(R.string.mitm_ca_enable_requires_install)
                ?: "Generate the certificate, install it, then confirm it's installed before enabling."
            return
        }
        edit { it.copy(enabled = enabled) }
    }

    private fun isCaReady(): Boolean = _caPresent.value && mitm.value.caInstallAcknowledged

    fun setCaptureAllApps(capture: Boolean) {
        viewModelScope.launch {
            val current = settings.current()
            if (capture && current.tor.routeAllDevice) {
                _message.value = context?.getString(R.string.mitm_mutex_tor)
                    ?: "Turn off Tor tunnel-whole-device first"
                return@launch
            }
            if (capture && !isCaReady()) {
                _message.value = context?.getString(R.string.mitm_ca_capture_requires_install)
                    ?: "Generate the certificate, install it, then confirm it's installed before tunneling the device."
                return@launch
            }
            // Switching mode: stop standalone proxy when enabling whole-device.
            if (capture && proxy.running.value) proxy.stop()
            // Turning capture off while VPN MITM is up → disconnect.
            if (!capture && isMitmVpnSession()) {
                vpn.disconnect()
            }
            settings.update {
                it.copy(
                    defaultCore = com.v2rayez.app.domain.model.ProxyCoreType.XRAY,
                    mitm = it.mitm.copy(
                        enabled = if (capture) true else it.mitm.enabled,
                        captureAllApps = capture
                    )
                )
            }
            if (capture) {
                when (vpn.connectionState.value.status) {
                    ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> {
                        // Restart so startTunnel takes the MITM capture branch.
                        if (!isMitmVpnSession()) {
                            vpn.disconnect()
                            // Never toggle while still CONNECTED — wait for a clean disconnect
                            // first (mirrors TorViewModel.setRouteAllDevice).
                            withTimeoutOrNull(15_000) {
                                vpn.connectionState.first { it.status == ConnectionStatus.DISCONNECTED }
                            }
                            vpn.toggle()
                        }
                    }
                    ConnectionStatus.DISCONNECTED -> vpn.toggle()
                }
            }
        }
    }

    private fun isMitmVpnSession(): Boolean {
        val st = vpn.connectionState.value
        return st.status == ConnectionStatus.CONNECTED && st.server?.id == "mitm"
    }

    fun setRulesText(text: String) = edit { it.copy(rulesText = text) }

    fun resetRules() = edit { it.copy(rulesText = MitmDomainFrontConfig.DEFAULT_RULES) }

    fun setProxyPort(port: Int) = edit { it.copy(proxyPort = port.coerceIn(1, 65535)) }

    fun setHttpPort(port: Int) = edit { it.copy(httpPort = port.coerceIn(1, 65535)) }

    fun setDefaultFront(front: String) = edit { it.copy(defaultFront = front.trim()) }

    fun setDohPreset(preset: String, ip: String, frontSni: String, host: String) = edit {
        it.copy(dohPreset = preset, dohIp = ip, dohFrontSni = frontSni, dohHost = host)
    }

    fun acknowledgeCaInstall(acked: Boolean = true) = edit { it.copy(caInstallAcknowledged = acked) }

    fun generateCa() {
        val ctx = context ?: run { _message.value = "Not available in preview"; return }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { MitmCaGenerator.generate(ctx) }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    refreshCaState()
                    acknowledgeCaInstall(false)
                    _message.value = ctx.getString(R.string.mitm_ca_generated)
                }.onFailure { e ->
                    val detail = generateSequence(e) { it.cause }
                        .mapNotNull { it.message }
                        .distinct()
                        .joinToString(": ")
                        .ifBlank { e.toString() }
                    _message.value = detail
                }
            }
        }
    }

    fun installCaIntent(): Intent? {
        val ctx = context ?: return null
        return MitmCaInstallHelper.prepareInstall(ctx)?.intent
    }

    fun prepareCaInstall(): MitmCaInstallPlan? {
        val ctx = context ?: return null
        return MitmCaInstallHelper.prepareInstall(ctx)
    }

    fun saveCaToDownloads() {
        val ctx = context ?: run { _message.value = "Not available in preview"; return }
        viewModelScope.launch(Dispatchers.IO) {
            val hint = runCatching { MitmCaInstallHelper.exportCrtToDownloads(ctx) }.getOrNull()
            withContext(Dispatchers.Main) {
                _message.value = if (hint != null) {
                    ctx.getString(R.string.mitm_ca_saved_downloads, MitmCaInstallHelper.DOWNLOADS_CRT_NAME)
                } else {
                    ctx.getString(R.string.mitm_ca_save_failed)
                }
            }
        }
    }

    fun openSecuritySettings() {
        val ctx = context ?: return
        MitmCaInstallHelper.openSecuritySettings(ctx)
    }

    fun onCaInstallReturned() = refreshCaState()

    fun crtShareUri(): Uri? = context?.let(MitmCaStore::exportCrtShareUri)

    fun pcPackShareUri(): Uri? = context?.let(MitmCaStore::exportPcPackZipUri)

    fun desktopJsonText(): String = MitmDesktopExporter.export(mitm.value)

    fun importPair(crtText: String, keyText: String) {
        val ctx = context ?: run { _message.value = "Not available in preview"; return }
        if (crtText.isBlank() || keyText.isBlank()) {
            _message.value = ctx.getString(R.string.mitm_import_paste_both)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = MitmCaStore.importPair(ctx, crtText.toByteArray(), keyText.toByteArray())
            withContext(Dispatchers.Main) {
                when (result) {
                    is MitmCaImportResult.Success -> {
                        refreshCaState()
                        acknowledgeCaInstall(false)
                        _message.value = ctx.getString(R.string.mitm_ca_imported)
                    }
                    is MitmCaImportResult.Failure -> _message.value = result.reason
                }
            }
        }
    }

    /**
     * Dedicated toggle for the standalone local SOCKS/HTTP proxy (no VPN).
     * Turning on forces `captureAllApps=false` and [mitm.enabled]=true.
     */
    fun setStandaloneRunning(running: Boolean) {
        viewModelScope.launch {
            if (running) {
                if (!isCaReady()) {
                    _message.value =
                        "Generate the certificate, install it, then confirm it's installed before starting."
                    return@launch
                }
                val current = settings.current()
                if (current.tor.routeAllDevice) {
                    _message.value = context?.getString(R.string.mitm_mutex_tor)
                        ?: "Turn off Tor tunnel-whole-device first"
                    return@launch
                }
                // Standalone path: never use VPN capture for this toggle.
                if (current.mitm.captureAllApps &&
                    vpn.connectionState.value.status == ConnectionStatus.CONNECTED
                ) {
                    vpn.disconnect()
                }
                settings.update {
                    it.copy(mitm = it.mitm.copy(enabled = true, captureAllApps = false))
                }
                if (!proxy.running.value) proxy.start()
            } else {
                if (proxy.running.value) proxy.stop()
            }
        }
    }

    /**
     * Start/Stop: standalone [MitmProxyController] unless [MitmDomainFrontConfig.captureAllApps],
     * then VpnService MITM tunnel.
     */
    fun startStop() {
        viewModelScope.launch {
            val cfg = settings.current().mitm
            if (!isCaReady() && !connected.value) {
                _message.value =
                    "Generate the certificate, install it, then confirm it's installed before starting."
                return@launch
            }
            if (cfg.captureAllApps) {
                if (proxy.running.value) proxy.stop()
                if (!cfg.enabled) {
                    settings.update { it.copy(mitm = it.mitm.copy(enabled = true)) }
                }
                vpn.toggle()
            } else {
                setStandaloneRunning(!proxy.running.value)
            }
        }
    }
}
