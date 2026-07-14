package com.v2rayez.app.data.fronting

import com.v2rayez.app.domain.model.DomainFrontConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hilt-managed wrapper around [DomainFrontDialer] that maps [DomainFrontConfig] into
 * the UAC-style local fronting dialer.
 */
@Singleton
class DomainFrontEngine @Inject constructor() {

    private val dialer = DomainFrontDialer()

    data class Status(
        val isRunning: Boolean = false,
        val lastError: String = "",
        val trafficSummary: String = "0 B / 0 B",
        val activeTargetLabel: String = "No active dialer"
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    val isRunning: Boolean get() = _status.value.isRunning
    val lastError: String get() = _status.value.lastError
    val trafficSummary: String get() = _status.value.trafficSummary
    val activeTargetLabel: String get() = _status.value.activeTargetLabel

    private var lastFrontKey: String? = null

    private val listener = object : DomainFrontDialer.Listener {
        override fun onLogLine(line: String?) = Unit
        override fun onProxyState(running: Boolean, targetLabel: String?, trafficSummary: String?) {
            publishStatus()
        }
    }

    init {
        DomainFrontDialer.addListener(listener)
        publishStatus()
    }

    fun start(config: DomainFrontConfig): Boolean {
        if (!config.enabled) {
            stop()
            return false
        }
        val frontKey = listOf(
            config.frontAddress,
            config.fallbackAddress,
            config.frontPort,
            config.effectiveFakeSni,
            config.tuningMode,
            config.method
        ).joinToString("|")
        if (lastFrontKey != null && lastFrontKey != frontKey) {
            DomainFrontDialer.clearStrategyCache()
        }
        lastFrontKey = frontKey

        val cfg = DomainFrontDialer.Config().apply {
            listenHost = config.listenHost.ifBlank { DomainFrontDialer.DEFAULT_LISTEN_HOST }
            listenPort = config.listenPort
            frontAddress = config.frontAddress.ifBlank { DomainFrontDialer.DEFAULT_FRONT_ADDRESS }
            fallbackAddress = config.fallbackAddress
            frontPort = config.frontPort
            fakeSni = config.effectiveFakeSni.ifBlank { DomainFrontDialer.DEFAULT_FAKE_SNI }
            method = config.method.ifBlank { "combined" }
            tuning = config.toTuning()
        }
        val ok = dialer.start(cfg)
        publishStatus()
        return ok
    }

    /** Bind dialer sockets to the physical network via VpnService.protect. */
    fun setSocketProtector(protector: DomainFrontDialer.SocketProtector?) {
        dialer.setSocketProtector(protector)
    }

    fun stop() {
        dialer.stop()
        publishStatus()
    }

    fun clearStrategyCache() {
        DomainFrontDialer.clearStrategyCache()
    }

    private fun publishStatus() {
        _status.value = Status(
            isRunning = DomainFrontDialer.isRunning(),
            lastError = DomainFrontDialer.getLastError().orEmpty(),
            trafficSummary = DomainFrontDialer.getTrafficSummary().orEmpty(),
            activeTargetLabel = DomainFrontDialer.getActiveTargetLabel()
        )
    }
}

internal fun DomainFrontConfig.toTuning(): DomainFrontTuning {
    return when (tuningMode.lowercase()) {
        DomainFrontTuning.MODE_FAST -> DomainFrontTuning.fast()
        DomainFrontTuning.MODE_STEALTH -> DomainFrontTuning.stealth()
        DomainFrontTuning.MODE_CUSTOM -> DomainFrontTuning().apply {
            mode = DomainFrontTuning.MODE_CUSTOM
            fakeProbeEnabled = this@toTuning.fakeProbeEnabled
            fakeProbeCount = this@toTuning.fakeProbeCount
            fakeProbeDelayMs = this@toTuning.fakeProbeDelayMs
            multiFragmentSize = this@toTuning.multiFragmentSize
            sniSplitDelayMs = this@toTuning.sniSplitDelayMs
            tlsRecordDelayMs = this@toTuning.tlsRecordDelayMs
            multiDelayMs = this@toTuning.multiDelayMs
            halfDelayMs = this@toTuning.halfDelayMs
            routeProbeTimeoutMs = this@toTuning.routeProbeTimeoutMs
            strategyCacheEnabled = this@toTuning.strategyCacheEnabled
            strategyCacheTtlMs = this@toTuning.strategyCacheTtlMs
            logLevel = this@toTuning.logLevel
        }.sanitize()
        else -> DomainFrontTuning.balanced()
    }
}
