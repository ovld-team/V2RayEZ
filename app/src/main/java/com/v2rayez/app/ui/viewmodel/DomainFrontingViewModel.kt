package com.v2rayez.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.R
import com.v2rayez.app.data.fronting.DomainFrontEngine
import com.v2rayez.app.data.fronting.DomainFrontTuning
import com.v2rayez.app.data.mock.MockLogRepository
import com.v2rayez.app.data.mock.MockSettingsRepository
import com.v2rayez.app.data.mock.MockVpnController
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import com.v2rayez.app.ui.tor.TorConflictHandler
import com.v2rayez.app.ui.tor.TorConflictUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Powers Domain Fronting Tools: persists [DomainFrontConfig] via [SettingsRepository],
 * applies Fast / Balanced / Stealth presets into the stored tuning fields, and clears the
 * dialer's strategy cache through [DomainFrontEngine].
 */
@HiltViewModel
class DomainFrontingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val engine: DomainFrontEngine,
    private val logs: LogRepository,
    private val vpn: VpnController,
    private val torController: TorController? = null
) : ViewModel() {

    /** Preview / no-arg constructor — [DomainFrontEngine] itself has a no-arg ctor (pure Java dialer, no native calls). */
    constructor() : this(
        MockSettingsRepository(),
        DomainFrontEngine(),
        MockLogRepository(),
        MockVpnController(),
        null
    )

    private val torConflict = TorConflictHandler(settings, vpn, viewModelScope, torController)
    val torConflictDialog: StateFlow<TorConflictUi?> = torConflict.dialog
    fun confirmTorConflict() = torConflict.confirm()
    fun dismissTorConflict() = torConflict.dismiss()

    val state: StateFlow<AppSettings> =
        settings.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** True when VPN is up — fronting changes need a reconnect to take effect. */
    val connected: StateFlow<Boolean> =
        vpn.connectionState.map { it.status == ConnectionStatus.CONNECTED }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val engineStatus: StateFlow<DomainFrontEngine.Status> =
        engine.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DomainFrontEngine.Status())

    val isRunning: Boolean get() = engineStatus.value.isRunning
    val lastError: String get() = engineStatus.value.lastError
    val trafficSummary: String get() = engineStatus.value.trafficSummary
    val activeTargetLabel: String get() = engineStatus.value.activeTargetLabel

    private fun edit(transform: (AppSettings) -> AppSettings) =
        viewModelScope.launch { settings.update(transform) }

    private fun editFront(transform: (DomainFrontConfig) -> DomainFrontConfig) =
        edit { it.copy(domainFront = transform(it.domainFront)) }

    fun toggleEnabled(v: Boolean) {
        if (!v) {
            engine.clearStrategyCache()
            editFront { it.copy(enabled = false) }
            return
        }
        viewModelScope.launch {
            torConflict.runOrPrompt(
                blocked = settings.current().tor.enabled,
                messageRes = R.string.tor_conflict_domain_front,
                stopDaemon = true
            ) {
                engine.clearStrategyCache()
                settings.update { it.copy(domainFront = it.domainFront.copy(enabled = true)) }
                log(LogLevel.INFO, "Domain fronting enabled")
            }
        }
    }

    fun setFrontAddress(s: String) {
        engine.clearStrategyCache()
        editFront { it.copy(frontAddress = s.trim()) }
    }

    fun setFallbackAddress(s: String) {
        engine.clearStrategyCache()
        editFront { it.copy(fallbackAddress = s.trim()) }
    }

    fun setFrontPort(p: Int) {
        engine.clearStrategyCache()
        editFront { it.copy(frontPort = p.coerceIn(1, 65535)) }
    }

    fun setFakeSni(s: String) {
        engine.clearStrategyCache()
        editFront { it.copy(fakeSni = s.trim()) }
    }

    fun setListenPort(p: Int) = editFront { it.copy(listenPort = p.coerceIn(1, 65535)) }

    /**
     * Apply a tuning preset. Non-custom modes copy [DomainFrontTuning] preset values into
     * the persisted config so ConfigBuilder / dialer see consistent fields.
     */
    fun setTuningMode(mode: String) {
        engine.clearStrategyCache()
        editFront { df ->
            when (mode.lowercase(Locale.US)) {
                DomainFrontTuning.MODE_FAST -> df.withTuning(DomainFrontTuning.fast())
                DomainFrontTuning.MODE_STEALTH -> df.withTuning(DomainFrontTuning.stealth())
                DomainFrontTuning.MODE_CUSTOM -> df.copy(tuningMode = DomainFrontTuning.MODE_CUSTOM)
                else -> df.withTuning(DomainFrontTuning.balanced())
            }
        }
    }

    fun clearStrategyCache() {
        engine.clearStrategyCache()
        log(LogLevel.INFO, "Domain fronting strategy cache cleared")
    }

    /** Reconnect so domain-front dialer / Xray dial path picks up new settings. */
    fun reconnect() {
        val server = vpn.connectionState.value.server ?: return
        vpn.connect(server)
        log(LogLevel.INFO, "Reconnecting to apply domain fronting changes")
    }

    private fun log(level: LogLevel, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.append(LogEntry(UUID.randomUUID().toString(), ts, level, message))
    }
}

private fun DomainFrontConfig.withTuning(t: DomainFrontTuning): DomainFrontConfig = copy(
    tuningMode = t.mode,
    fakeProbeEnabled = t.fakeProbeEnabled,
    fakeProbeCount = t.fakeProbeCount,
    fakeProbeDelayMs = t.fakeProbeDelayMs,
    multiFragmentSize = t.multiFragmentSize,
    sniSplitDelayMs = t.sniSplitDelayMs,
    tlsRecordDelayMs = t.tlsRecordDelayMs,
    multiDelayMs = t.multiDelayMs,
    halfDelayMs = t.halfDelayMs,
    routeProbeTimeoutMs = t.routeProbeTimeoutMs,
    strategyCacheEnabled = t.strategyCacheEnabled,
    strategyCacheTtlMs = t.strategyCacheTtlMs,
    logLevel = t.logLevel
)
