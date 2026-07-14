package com.v2rayez.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.R
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.data.tor.TorState
import com.v2rayez.app.data.tor.TorStatus
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.model.TorTransport
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Drives the Tor screen: persists [com.v2rayez.app.domain.model.TorConfig], controls the
 * embedded Tor engine, and optional whole-device VPN tunnel via [VpnController].
 */
@HiltViewModel
class TorViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val controller: TorController,
    private val vpn: VpnController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val state: StateFlow<AppSettings> =
        settings.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val status: StateFlow<TorStatus> = controller.status

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun availableTransports(): List<TorTransport> = controller.availableTransports()

    private fun edit(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(transform) }

    fun setEnabled(v: Boolean) {
        edit { it.copy(tor = it.tor.copy(enabled = v)) }
        viewModelScope.launch {
            val cfg = settings.current().tor.copy(enabled = v)
            if (v) {
                controller.start(cfg)
            } else {
                if (settings.current().tor.routeAllDevice || isTorVpnSession()) {
                    settings.update { it.copy(tor = it.tor.copy(routeAllDevice = false)) }
                    vpn.disconnect()
                }
                controller.stop()
            }
        }
    }

    /**
     * Enable/disable Tor full-device VPN. Caller must obtain VPN permission first when enabling.
     */
    fun setRouteAllDevice(route: Boolean) {
        viewModelScope.launch {
            val current = settings.current()
            if (route) {
                if (current.mitm.enabled && current.mitm.captureAllApps) {
                    _message.value = context.getString(R.string.tor_mutex_mitm)
                    return@launch
                }
                settings.update {
                    it.copy(
                        defaultCore = ProxyCoreType.XRAY,
                        tor = it.tor.copy(enabled = true, routeAllDevice = true)
                    )
                }
                if (controller.status.value.state != TorState.CONNECTED) {
                    controller.start(settings.current().tor)
                }
                when (vpn.connectionState.value.status) {
                    ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> {
                        vpn.disconnect()
                        // Never toggle while still CONNECTED — wait for clean disconnect first.
                        withTimeoutOrNull(15_000) {
                            vpn.connectionState.first {
                                it.status == ConnectionStatus.DISCONNECTED
                            }
                        }
                        vpn.toggle()
                    }
                    ConnectionStatus.DISCONNECTED -> vpn.toggle()
                }
            } else {
                settings.update { it.copy(tor = it.tor.copy(routeAllDevice = false)) }
                if (isTorVpnSession()) {
                    vpn.disconnect()
                }
            }
        }
    }

    private fun isTorVpnSession(): Boolean {
        val st = vpn.connectionState.value
        return st.status == ConnectionStatus.CONNECTED &&
            st.server?.id == "tor-device"
    }

    fun setTransport(t: TorTransport) {
        edit { it.copy(tor = it.tor.copy(transport = t)) }
        viewModelScope.launch {
            val cfg = settings.current().tor
            if (cfg.enabled || controller.status.value.state == TorState.CONNECTED ||
                controller.status.value.state == TorState.BOOTSTRAPPING
            ) {
                controller.restart(cfg.copy(transport = t))
            }
        }
    }
    fun toggleAutoRotate(v: Boolean) = edit { it.copy(tor = it.tor.copy(autoRotateBridges = v)) }
    fun setSocksPort(p: Int) = edit { it.copy(tor = it.tor.copy(socksPort = p)) }
    fun setBridges(text: String) = edit {
        it.copy(tor = it.tor.copy(bridges = text.split('\n').map { l -> l.trim() }.filter { l -> l.isNotEmpty() }))
    }

    fun getNewBridges(onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            val transport = settings.current().tor.transport
            val fresh = controller.requestNewBridges(transport)
            if (fresh.isNotEmpty()) {
                edit { it.copy(tor = it.tor.copy(bridges = fresh)) }
            }
            _busy.value = false
            onResult(fresh.size)
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _probeLog = MutableStateFlow<List<String>>(emptyList())
    val probeLog: StateFlow<List<String>> = _probeLog.asStateFlow()

    fun autoSetup(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            _probeLog.value = emptyList()
            val cfg = controller.autoSetup(settings.current().tor) { line ->
                _probeLog.value = (_probeLog.value + line).takeLast(40)
            }
            settings.update { it.copy(tor = cfg) }
            val ok = controller.status.value.state == TorState.CONNECTED
            _busy.value = false
            onDone(ok)
        }
    }

    fun testConnection(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val cfg = settings.current().tor
            val exitOk = controller.testExitReachable(cfg)
            _busy.value = false
            onResult(exitOk)
        }
    }

    fun restart() {
        viewModelScope.launch {
            val cfg = settings.current().tor
            if (cfg.enabled) controller.start(cfg)
        }
    }
}
