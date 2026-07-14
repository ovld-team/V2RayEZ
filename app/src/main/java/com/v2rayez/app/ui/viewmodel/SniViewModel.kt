package com.v2rayez.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.data.fronting.DomainFrontTuning
import com.v2rayez.app.data.sni.ByeDpiEngine
import com.v2rayez.app.data.sni.SniScanResult
import com.v2rayez.app.data.sni.SniScanner
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.DesyncConfig
import com.v2rayez.app.domain.model.DesyncMode
import com.v2rayez.app.domain.model.FragmentConfig
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.SniConfig
import com.v2rayez.app.domain.model.SniTuningMode
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Powers the reworked SNI tab: persists [SniConfig], applies tuning-mode presets to the
 * fragmentation params, and runs the [SniScanner] (UAC-SNI-Spoofer style, Cloudflare-trace
 * probing) over the bundled/overridden domain list to find and auto-apply the best SNI.
 * Progress is streamed to the shared [LogRepository].
 */
@HiltViewModel
class SniViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val scanner: SniScanner,
    private val byedpi: ByeDpiEngine,
    private val logs: LogRepository,
    private val vpn: VpnController
) : ViewModel() {

    /** True when the native byedpi (`libbyedpi.so`) desync binary is bundled. */
    fun byedpiAvailable(): Boolean = byedpi.isAvailable(context)

    val state: StateFlow<AppSettings> =
        settings.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** Whether a tunnel is up — SNI changes only take effect after a reconnect. */
    val connected: StateFlow<Boolean> =
        vpn.connectionState.map { it.status == ConnectionStatus.CONNECTED }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Result of the "test current SNI" one-tap probe (null until run). */
    private val _testResult = MutableStateFlow<SniScanResult?>(null)
    val testResult: StateFlow<SniScanResult?> = _testResult.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** Bundled candidate list from assets, cached after first read. */
    private val assetDomains: List<String> by lazy {
        runCatching {
            context.assets.open("sni-spoof/domains.txt").bufferedReader().useLines { seq ->
                seq.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().toList()
            }
        }.getOrDefault(SniConfig.DEFAULT_CANDIDATES)
    }

    /** Effective candidate domains: the user override, or the bundled default list. */
    val candidates: StateFlow<List<String>> =
        state.map { it.sni.candidateDomains.ifEmpty { assetDomains } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), assetDomains)

    private val _results = MutableStateFlow<List<SniScanResult>>(emptyList())
    val results: StateFlow<List<SniScanResult>> = _results.asStateFlow()

    private val _saved = MutableStateFlow<List<SniScanResult>>(emptyList())
    val saved: StateFlow<List<SniScanResult>> = _saved.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Scan progress as (done, total, okCount). */
    private val _progress = MutableStateFlow(Triple(0, 0, 0))
    val progress: StateFlow<Triple<Int, Int, Int>> = _progress.asStateFlow()

    private val _showSaved = MutableStateFlow(false)
    val showSaved: StateFlow<Boolean> = _showSaved.asStateFlow()

    private val bookmarkPrefs by lazy {
        context.getSharedPreferences("sni_lab_bookmarks", Context.MODE_PRIVATE)
    }

    private var scanJob: Job? = null
    @Volatile private var lastProgressUiMs = 0L

    init {
        _saved.value = loadBookmarks()
    }

    fun setShowSaved(v: Boolean) { _showSaved.value = v }

    private fun edit(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(transform) }
    private fun editSni(transform: (SniConfig) -> SniConfig) = edit { it.copy(sni = transform(it.sni)) }

    /**
     * Apply a tuning-mode preset. Updates [SniConfig] flags, [FragmentConfig] and the
     * native byedpi [DesyncConfig] (via [DesyncConfig.forTuningMode]) together so the
     * tuning modes drive the real desync flags. CUSTOM leaves desync untouched.
     */
    fun setMode(mode: SniTuningMode) = edit { s ->
        val sni = s.sni.copy(mode = mode)
        val desync = DesyncConfig.forTuningMode(mode, s.desync)
        val frontTuning = when (mode) {
            SniTuningMode.FAST -> DomainFrontTuning.fast()
            SniTuningMode.STEALTH -> DomainFrontTuning.stealth()
            SniTuningMode.BALANCED -> DomainFrontTuning.balanced()
            SniTuningMode.CUSTOM -> null
        }
        val base = when (mode) {
            SniTuningMode.FAST -> s.copy(
                sni = sni.copy(splitEnabled = true, omitEnabled = false),
                fragment = FragmentConfig(enabled = true, packets = "tlshello", length = "40-60", interval = "1-3"),
                desync = desync
            )
            SniTuningMode.BALANCED -> s.copy(
                sni = sni.copy(splitEnabled = true, omitEnabled = false),
                fragment = FragmentConfig(enabled = true, packets = "tlshello", length = "100-200", interval = "10-20"),
                desync = desync
            )
            SniTuningMode.STEALTH -> s.copy(
                sni = sni.copy(splitEnabled = true, omitEnabled = false),
                fragment = FragmentConfig(enabled = true, packets = "1-3", length = "10-40", interval = "20-50"),
                desync = desync
            )
            SniTuningMode.CUSTOM -> s.copy(sni = sni, desync = desync)
        }
        // Keep Domain Fronting Fast/Balanced/Stealth in sync with the SNI Lab presets.
        if (frontTuning != null) {
            base.copy(
                domainFront = base.domainFront.copy(
                    tuningMode = frontTuning.mode,
                    fakeProbeEnabled = frontTuning.fakeProbeEnabled,
                    fakeProbeCount = frontTuning.fakeProbeCount,
                    fakeProbeDelayMs = frontTuning.fakeProbeDelayMs,
                    multiFragmentSize = frontTuning.multiFragmentSize,
                    sniSplitDelayMs = frontTuning.sniSplitDelayMs,
                    tlsRecordDelayMs = frontTuning.tlsRecordDelayMs,
                    multiDelayMs = frontTuning.multiDelayMs,
                    halfDelayMs = frontTuning.halfDelayMs,
                    routeProbeTimeoutMs = frontTuning.routeProbeTimeoutMs,
                    strategyCacheEnabled = frontTuning.strategyCacheEnabled,
                    strategyCacheTtlMs = frontTuning.strategyCacheTtlMs,
                    logLevel = frontTuning.logLevel
                )
            )
        } else base
    }

    // Native byedpi desync engine controls (Custom-mode fine-tuning).
    fun toggleDesync(v: Boolean) = edit { it.copy(desync = it.desync.copy(enabled = v)) }

    /** Pick a desync method directly; selecting a real method also enables the engine. */
    fun setDesyncMode(mode: DesyncMode) = edit {
        it.copy(desync = it.desync.copy(mode = mode, enabled = mode != DesyncMode.NONE))
    }

    fun setSplitPos(pos: Int) = edit { it.copy(desync = it.desync.copy(splitPos = pos.coerceAtLeast(1))) }
    fun setFakeTtl(ttl: Int) = edit { it.copy(desync = it.desync.copy(fakeTtl = ttl.coerceAtLeast(1))) }

    // Domain fronting — kept for apply-from-scan; UI lives on Domain Fronting Tools.
    fun toggleFront(v: Boolean) = edit { it.copy(domainFront = it.domainFront.copy(enabled = v)) }
    fun setFrontDomain(s: String) {
        val trimmed = s.trim()
        edit {
            it.copy(
                domainFront = it.domainFront.copy(
                    frontDomain = trimmed,
                    fakeSni = trimmed.ifBlank { it.domainFront.fakeSni }
                )
            )
        }
    }

    fun toggleSpoof(v: Boolean) = editSni { it.copy(spoofEnabled = v) }
    fun setSpoofDomain(s: String) = editSni { it.copy(spoofDomain = s.trim()) }
    fun toggleSplit(v: Boolean) = edit { it.copy(sni = it.sni.copy(splitEnabled = v), fragment = it.fragment.copy(enabled = v)) }
    fun toggleOmit(v: Boolean) = editSni { it.copy(omitEnabled = v) }
    fun setFragmentLength(s: String) = edit { it.copy(fragment = it.fragment.copy(length = s)) }
    fun setFragmentInterval(s: String) = edit { it.copy(fragment = it.fragment.copy(interval = s)) }
    fun setCandidates(text: String) = editSni {
        it.copy(candidateDomains = text.split('\n', ',').map { d -> d.trim() }.filter { d -> d.isNotEmpty() })
    }

    /** Scan every candidate SNI via Cloudflare trace. Does not rewrite real URI SNI. */
    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scanning.value = true
            _showSaved.value = false
            _results.value = emptyList()
            _progress.value = Triple(0, 0, 0)
            lastProgressUiMs = 0L
            try {
                val cfg = settings.current()
                val domains = cfg.sni.candidateDomains.ifEmpty { assetDomains }
                if (domains.isEmpty()) {
                    log(LogLevel.WARNING, "SNI scan: no candidate domains")
                    return@launch
                }
                log(LogLevel.INFO, "SNI scan started (${domains.size} domains)")
                val okCounter = java.util.concurrent.atomic.AtomicInteger(0)
                val res = try {
                    scanner.scan(domains, onProgress = { done, total, result ->
                        if (result.success) okCounter.incrementAndGet()
                        // Throttle Compose recompositions — 318 domains × every result froze the UI.
                        val now = SystemClock.elapsedRealtime()
                        val flush = result.success || done >= total || now - lastProgressUiMs >= 250L
                        if (flush) {
                            lastProgressUiMs = now
                            _progress.value = Triple(done, total, okCounter.get())
                            if (result.success) {
                                _results.update { cur ->
                                    (cur + result).sortedWith(
                                        compareByDescending<SniScanResult> { r -> r.stability }
                                            .thenBy { r -> r.pingMs }
                                    )
                                }
                            }
                        }
                    })
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    emptyList()
                }
                _progress.value = Triple(domains.size, domains.size, res.size)
                _results.value = res
                val best = res.firstOrNull()
                if (best != null) {
                    log(
                        LogLevel.INFO,
                        "Best SNI: ${best.domain} (${best.pingMs} ms, ${best.stability}% via ${best.colo.ifBlank { "?" }})"
                    )
                    applyFronting(best)
                } else {
                    log(LogLevel.WARNING, "SNI scan: no working domain found")
                }
            } finally {
                _scanning.value = false
                scanJob = null
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
        log(LogLevel.INFO, "SNI scan stopped")
    }

    /**
     * Apply a scan result as the fake-SNI probe domain (and enable fronting).
     * Does NOT set [DomainFrontConfig.frontAddress] to the scanned domain's IP — that IP
     * usually cannot complete TLS for the real server SNI. On connect, the VPN service
     * resolves the server host/SNI for the TCP target instead.
     */
    fun applyFronting(result: SniScanResult) {
        if (result.domain.isBlank()) return
        edit {
            it.copy(
                domainFront = it.domainFront.copy(
                    enabled = true,
                    fakeSni = result.domain,
                    frontDomain = result.domain
                ),
                sni = it.sni.copy(bestSni = result.domain)
            )
        }
        log(
            LogLevel.INFO,
            "Applied fronting fake SNI: ${result.domain} " +
                "(front IP will resolve from server host/SNI on connect)"
        )
    }

    /** Persist a scan result as a bookmark (Saved tab). */
    fun saveResult(result: SniScanResult) {
        val next = (_saved.value.filterNot { it.domain == result.domain } + result).take(50)
        _saved.value = next
        persistBookmarks(next)
        log(LogLevel.INFO, "Saved SNI bookmark: ${result.domain}")
    }

    fun removeSaved(domain: String) {
        val next = _saved.value.filterNot { it.domain == domain }
        _saved.value = next
        persistBookmarks(next)
    }

    /** Tap-to-apply a scanned domain as fronting fake SNI (legacy name kept). */
    fun applyResult(domain: String) {
        val fromResults = _results.value.firstOrNull { it.domain == domain }
            ?: _saved.value.firstOrNull { it.domain == domain }
        if (fromResults != null) {
            applyFronting(fromResults)
        } else {
            edit {
                it.copy(
                    domainFront = it.domainFront.copy(enabled = true, fakeSni = domain, frontDomain = domain),
                    sni = it.sni.copy(bestSni = domain, spoofDomain = domain)
                )
            }
            log(LogLevel.INFO, "Applied SNI: $domain")
        }
    }

    private fun persistBookmarks(list: List<SniScanResult>) {
        val encoded = list.joinToString("\n") { r ->
            listOf(r.domain, r.resolvedIp, r.cfIp, r.colo, r.country, r.pingMs, r.stability)
                .joinToString("|")
        }
        bookmarkPrefs.edit().putString("items", encoded).apply()
    }

    private fun loadBookmarks(): List<SniScanResult> {
        val raw = bookmarkPrefs.getString("items", null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 7) return@mapNotNull null
            SniScanResult(
                domain = p[0],
                success = true,
                pingMs = p[5].toIntOrNull() ?: 9999,
                stability = p[6].toIntOrNull() ?: 0,
                resolvedIp = p[1],
                cfIp = p[2],
                colo = p[3],
                country = p[4]
            )
        }.toList()
    }

    /** Probe the currently configured spoof/best SNI once to confirm the bypass still works. */
    fun testCurrentSni() {
        if (_testing.value) return
        viewModelScope.launch {
            val cfg = settings.current().sni
            val domain = cfg.spoofDomain.ifBlank { cfg.bestSni }
            if (domain.isBlank()) {
                log(LogLevel.WARNING, "No SNI configured to test")
                return@launch
            }
            _testing.value = true
            _testResult.value = null
            log(LogLevel.INFO, "Testing current SNI: $domain")
            val res = runCatching {
                scanner.scan(listOf(domain), tries = 3, timeoutMs = 5000, concurrency = 1)
            }.getOrDefault(emptyList()).firstOrNull()
                ?: SniScanResult(domain = domain, success = false, pingMs = 9999)
            _testResult.value = res
            log(
                if (res.success) LogLevel.INFO else LogLevel.WARNING,
                if (res.success) "SNI $domain OK (${res.pingMs} ms, ${res.stability}%)" else "SNI $domain failed"
            )
            _testing.value = false
        }
    }

    /** Reconnect the active tunnel so SNI/desync changes take effect. */
    fun reconnect() {
        val server = vpn.connectionState.value.server ?: return
        vpn.connect(server)
        log(LogLevel.INFO, "Reconnecting to apply SNI changes")
    }

    private fun log(level: LogLevel, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.append(LogEntry(UUID.randomUUID().toString(), ts, level, message))
    }
}
