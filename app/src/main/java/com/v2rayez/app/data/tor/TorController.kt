package com.v2rayez.app.data.tor

import android.content.Context
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorEngineType
import com.v2rayez.app.domain.model.TorTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlin.coroutines.coroutineContext
import android.util.Log

/**
 * Orchestrates the Tor subsystem: selects the requested [TorEngine], resolves bridges for
 * the chosen [TorTransport] (from config, or fetched live via [BridgeProvider]), starts /
 * stops the engine, and exposes a single [status] stream. On a failed bootstrap with
 * auto-rotate enabled it re-fetches fresh bridges and retries once.
 *
 * CONNECTED means bootstrap 100% **or** a successful HTTP exit through Tor SOCKS —
 * never a bare TCP accept on the SOCKS port.
 */
@Singleton
class TorController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridgeProvider: BridgeProvider,
    private val addonPacks: AddonPackManager,
    nativeC: NativeCTorEngine
) {

    private val engines: Map<TorEngineType, TorEngine> = mapOf(
        TorEngineType.NATIVE_C to nativeC
    )

    private val _status = MutableStateFlow(TorStatus())
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    private var active: TorEngine? = null
    private var lastConfig: TorConfig? = null
    private val startMutex = Mutex()

    /** Long-lived scope for the post-connect watchdog (outlives any single [start]). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Polls the live engine after CONNECTED; restarts / ERRORs on process death. */
    private var watchdogJob: Job? = null

    /**
     * Set while the VPN tunnel owns Tor as its exit. Gates auto-rotate restarts so
     * bridge rotation cannot thrash an active VPN session.
     */
    @Volatile
    var vpnSessionActive: Boolean = false

    /** Which engines can actually run on this device right now. */
    fun availableEngines(): List<TorEngineType> =
        engines.filterValues { it.isAvailable(context) }.keys.toList()

    /**
     * Which bypass transports are usable right now. DIRECT/VANILLA always work; the
     * pluggable transports require their `exec` binary to be vendored in jniLibs.
     */
    fun availableTransports(): List<TorTransport> =
        TorTransport.entries.filter { PluggableTransports.isAvailable(addonPacks, it) }

    /** Start Tor per [config], resolving bridges and retrying with fresh ones on failure. */
    suspend fun start(config: TorConfig, readyTimeoutMs: Long = 75_000L) = startMutex.withLock {
        // Reuse a healthy CONNECTED daemon for the same transport/engine/socks endpoints.
        val cur = lastConfig
        if (_status.value.state == TorState.CONNECTED &&
            cur != null &&
            cur.engine == config.engine &&
            cur.transport == config.transport &&
            cur.socksHost == config.socksHost &&
            cur.socksPort == config.socksPort &&
            active != null
        ) {
            Log.i(TAG, "Reusing CONNECTED Tor (${config.transport.label})")
            lastConfig = config.copy(enabled = true)
            return@withLock
        }

        stopUnlocked()
        if (config.transport != TorTransport.DIRECT &&
            config.transport != TorTransport.VANILLA &&
            !PluggableTransports.isAvailable(addonPacks, config.transport)
        ) {
            _status.value = TorStatus(
                TorState.ERROR,
                0,
                "${config.transport.label} not installed — download the pack in Core manager",
                config.engine
            )
            return@withLock
        }
        val engine = engines[config.engine] ?: run {
            _status.value = TorStatus(TorState.ERROR, 0, "Unknown Tor engine", config.engine)
            return@withLock
        }
        active = engine
        lastConfig = config

        val bridges = resolveBridges(config)
        if (config.transport != TorTransport.DIRECT && bridges.isEmpty()) {
            active = null
            _status.value = TorStatus(
                TorState.ERROR,
                0,
                "No bridges available for ${config.transport.label}",
                config.engine
            )
            return@withLock
        }

        engine.start(context, config, bridges) { st ->
            val now = _status.value
            // Don't regress CONNECTED → BOOTSTRAPPING from late log lines.
            if (now.state == TorState.CONNECTED && st.state == TorState.BOOTSTRAPPING) return@start
            _status.value = st
        }

        var ready = awaitReady(config, readyTimeoutMs)

        // Auto-rotate: await finished first (bootstrap is async), then retry with fresh bridges.
        // Skip while VPN session owns Tor — rotating mid-tunnel causes connect/disconnect flaps.
        if (!ready &&
            config.autoRotateBridges &&
            config.transport != TorTransport.DIRECT &&
            !vpnSessionActive
        ) {
            val fresh = bridgeProvider.fetchBridges(config.transport)
                .filter { it.isNotBlank() && isPlausibleBridgeLine(it) && bridgeMatchesTransport(it, config.transport) }
            if (fresh.isNotEmpty() && fresh != bridges) {
                engine.stop()
                _status.value = TorStatus(TorState.STARTING, 0, "Rotating bridges…", config.engine)
                engine.start(context, config, fresh) { st ->
                    val now = _status.value
                    if (now.state == TorState.CONNECTED && st.state == TorState.BOOTSTRAPPING) return@start
                    _status.value = st
                }
                ready = awaitReady(config, readyTimeoutMs)
            }
        }

        if (!ready) {
            // Critical: leave no orphan Tor/PT processes after bootstrap timeout.
            runCatching { engine.stop() }
            if (active === engine) active = null
            if (_status.value.state != TorState.ERROR) {
                _status.value = TorStatus(
                    TorState.ERROR,
                    _status.value.bootstrapPercent,
                    "Tor bootstrap timed out (exit not ready)",
                    config.engine
                )
            }
        } else {
            // Post-connect watchdog: guard against a Tor/PT process that dies after bootstrap.
            startWatchdog(config)
        }
    }

    /**
     * Watch the live engine after CONNECTED. If the Tor/PT process dies, restart with the
     * same config when rotation is allowed and no VPN session owns Tor; otherwise surface
     * ERROR. Cancelled on any intentional stop/restart so it never misfires.
     */
    private fun startWatchdog(config: TorConfig) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val eng = active ?: break
                if (eng.isAlive()) continue
                Log.w(TAG, "Tor/PT process died post-connect (${config.transport.label})")
                if (config.autoRotateBridges && !vpnSessionActive) {
                    _status.value = TorStatus(
                        TorState.STARTING,
                        0,
                        "Tor process died — restarting",
                        config.engine
                    )
                    runCatching { restart(config) }
                        .onFailure {
                            _status.value = TorStatus(TorState.ERROR, 0, "Tor process died", config.engine)
                        }
                } else {
                    _status.value = TorStatus(TorState.ERROR, 0, "Tor process died", config.engine)
                }
                break
            }
        }
    }

    /** Restart with the last config (or [config] if provided) — used when transport/bridges change. */
    suspend fun restart(config: TorConfig? = null) {
        val cfg = config ?: lastConfig ?: return
        // Force a fresh start even if CONNECTED.
        stop()
        start(cfg)
    }

    suspend fun stop() = startMutex.withLock { stopUnlocked() }

    private suspend fun stopUnlocked() {
        watchdogJob?.cancel()
        watchdogJob = null
        active?.stop()
        active = null
        _status.value = TorStatus(TorState.OFF, 0, "Tor off", lastConfig?.engine ?: TorEngineType.NATIVE_C)
    }

    /**
     * Wait until bootstrap hits CONNECTED, or until an HTTP exit probe succeeds
     * (covers log-parse misses while circuits are actually up).
     */
    private suspend fun awaitReady(config: TorConfig, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastExitProbeAt = 0L
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            when (_status.value.state) {
                TorState.CONNECTED -> {
                    // Confirm exit once — bootstrap 100% can race before circuits are usable.
                    if (TorReachability.exitReachable(config, timeoutMs = 8_000)) return true
                    // Bootstrap claimed 100% but exit failed — keep waiting a bit.
                    delay(1_000)
                }
                TorState.ERROR, TorState.OFF -> return false
                else -> {
                    val now = System.currentTimeMillis()
                    // After SOCKS is up, periodically try exit (log lines may be missing).
                    if (now - lastExitProbeAt >= 5_000) {
                        lastExitProbeAt = now
                        if (TorReachability.socksAccepts(config, timeoutMs = 800) &&
                            TorReachability.exitReachable(config, timeoutMs = 6_000)
                        ) {
                            _status.value = TorStatus(
                                TorState.CONNECTED,
                                100,
                                "Tor exit reachable",
                                config.engine
                            )
                            return true
                        }
                    }
                    delay(400)
                }
            }
        }
        // Final chance.
        if (TorReachability.exitReachable(config, timeoutMs = 10_000)) {
            _status.value = TorStatus(TorState.CONNECTED, 100, "Tor exit reachable", config.engine)
            return true
        }
        return false
    }

    /** Fetch a fresh set of bridges for [transport] (used by the "Get new bridges" action). */
    suspend fun requestNewBridges(transport: TorTransport): List<String> =
        bridgeProvider.fetchBridges(transport)
            .filter { isPlausibleBridgeLine(it) && bridgeMatchesTransport(it, transport) }

    /**
     * One-tap setup via [TorBridgeProber]: try transports/bridges until CONNECTED.
     * [onEvent] receives human-readable progress lines for the Tor UI.
     */
    suspend fun autoSetup(
        base: TorConfig,
        timeoutMs: Long = 180_000,
        onEvent: (String) -> Unit = {}
    ): TorConfig {
        // Gate: never probe/rotate transports while a VPN session owns Tor — restarting
        // the daemon mid-tunnel flaps every routed app.
        if (vpnSessionActive) {
            onEvent("Auto-setup skipped — VPN session active")
            return base
        }
        val prober = TorBridgeProber(this, bridgeProvider)
        // Enough time for obfs4 bootstrap on slow links; multiple transports share the budget.
        val perAttempt = (timeoutMs / 3).coerceIn(45_000L, 75_000L)
        val (cfg, ok) = prober.probe(base, perAttemptTimeoutMs = perAttempt) { ev ->
            onEvent(ev.message)
        }
        if (!ok) {
            if (_status.value.state != TorState.CONNECTED) {
                _status.value = TorStatus(
                    TorState.ERROR,
                    _status.value.bootstrapPercent,
                    "Bridge auto-probe failed",
                    cfg.engine
                )
            }
        }
        return cfg
    }

    /** Prefer an obfuscating transport when its binary is vendored, else vanilla, else direct. */
    internal fun pickBestTransport(): TorTransport {
        val available = availableTransports().toSet()
        val preference = listOf(
            TorTransport.OBFS4,
            TorTransport.SNOWFLAKE,
            TorTransport.WEBTUNNEL,
            TorTransport.MEEK,
            TorTransport.VANILLA,
            TorTransport.DIRECT
        )
        return preference.firstOrNull { it in available } ?: TorTransport.DIRECT
    }

    /**
     * Probe the local SOCKS port to confirm the running Tor is actually reachable.
     * Returns true if a TCP connection to the SOCKS listener succeeds.
     */
    suspend fun testSocksReachable(config: TorConfig, timeoutMs: Int = 3000): Boolean =
        TorReachability.socksAccepts(config, timeoutMs)

    /** True when traffic can exit through Tor (post-bootstrap). */
    suspend fun testExitReachable(config: TorConfig, timeoutMs: Long = 12_000): Boolean =
        TorReachability.exitReachable(config, timeoutMs)

    /**
     * Resolve the bridge lines to hand the engine for [config]'s transport.
     *
     * Precedence:
     *  1. User-supplied bridges — but only the lines that actually match the selected
     *     transport (a pasted obfs4 set is ignored when snowflake is selected).
     *  2. Bundled bridges (`assets`). Preferred over Moat: Moat is a chicken-and-egg
     *     problem — it needs the open network we may not have — so bundled wins and the
     *     status surfaces "Using bundled bridges".
     *  3. Moat/network fetch, when nothing usable is bundled (e.g. all bundled lines were
     *     filtered out as dead documentation-address samples) and rotation is enabled.
     */
    private suspend fun resolveBridges(config: TorConfig): List<String> {
        if (config.transport == TorTransport.DIRECT) return emptyList()

        val configured = config.bridges
            .map { it.trim().removePrefix("Bridge ").trim() }
            .filter {
                it.isNotEmpty() &&
                    isPlausibleBridgeLine(it) &&
                    bridgeMatchesTransport(it, config.transport)
            }
        if (configured.isNotEmpty()) return configured

        val bundled = bridgeProvider.defaultBridges(config.transport)
            .filter { isPlausibleBridgeLine(it) && bridgeMatchesTransport(it, config.transport) }
        if (bundled.isNotEmpty()) {
            Log.i(TAG, "Using ${bundled.size} bundled ${config.transport.label} bridge(s)")
            _status.value = TorStatus(TorState.STARTING, 0, "Using bundled bridges", config.engine)
            return bundled
        }

        if (config.autoRotateBridges) {
            return bridgeProvider.fetchBridges(config.transport)
                .filter { isPlausibleBridgeLine(it) && bridgeMatchesTransport(it, config.transport) }
        }
        return emptyList()
    }

    companion object {
        private const val TAG = "TorController"

        /** How often the post-connect watchdog polls the engine for liveness. */
        private const val WATCHDOG_INTERVAL_MS = 3_000L

        /**
         * Whether [line] is a bridge for [transport]. Lets the controller drop bridge lines
         * that belong to a different pluggable transport than the one selected, so a torrc
         * never mixes e.g. obfs4 lines under a snowflake ClientTransportPlugin.
         */
        fun bridgeMatchesTransport(line: String, transport: TorTransport): Boolean {
            val t = line.trim().removePrefix("Bridge ").trim()
            if (t.isEmpty()) return false
            val head = t.substringBefore(' ').lowercase(Locale.US)
            val isVanillaLine = head.firstOrNull()?.isDigit() == true
            return when (transport) {
                TorTransport.DIRECT -> false
                TorTransport.VANILLA -> isVanillaLine
                TorTransport.OBFS4 -> head == "obfs4"
                TorTransport.SNOWFLAKE -> head == "snowflake"
                TorTransport.MEEK -> head == "meek" || head == "meek_lite"
                TorTransport.WEBTUNNEL -> head == "webtunnel"
            }
        }

        /**
         * Reject BridgeDB HTML / CAPTCHA junk so we never write garbage into torrc.
         *
         * Documentation-address handling: `2001:db8::/32` (RFC 3849) and the RFC 5737 IPv4
         * test nets (192.0.2.x / 198.51.100.x / 203.0.113.x) are *dead* only for transports
         * that dial the bridge address directly (obfs4 / vanilla). webtunnel and snowflake
         * distribute their real endpoint in the `url=` field and put a placeholder document
         * address in the address slot **by design** — the Tor Project's own builtin webtunnel
         * bridges use `[2001:db8:...]`. So a doc address is rejected only when the line carries
         * no `url=` endpoint; otherwise it's a legitimate url-dialed bridge.
         */
        fun isPlausibleBridgeLine(line: String): Boolean {
            val t = line.trim()
                .removePrefix("Bridge ")
                .trim()
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (t.isEmpty() || t.startsWith("<")) return false
            if (t.contains("</") || t.contains("captcha", ignoreCase = true)) return false
            val head = t.substringBefore(' ').lowercase(Locale.US)
            val knownTransports = setOf("obfs4", "snowflake", "meek_lite", "webtunnel", "meek")
            val tokens = t.split(Regex("""\s+"""))
            val addr = (if (head in knownTransports) tokens.getOrNull(1) else tokens.firstOrNull())
                ?.lowercase(Locale.US) ?: return false
            val dialsUrl = t.contains("url=", ignoreCase = true)
            val isDocAddress = addr.startsWith("[2001:db8:") ||
                addr.startsWith("192.0.2.") ||
                addr.startsWith("198.51.100.") ||
                addr.startsWith("203.0.113.")
            // A doc address with no url= endpoint is a dead sample (obfs4/vanilla dial the addr).
            if (isDocAddress && !dialsUrl) return false
            if (head in knownTransports) {
                return t.length > 20 && t.contains(' ')
            }
            // Vanilla: ip:port fingerprint
            return Regex("""^\d{1,3}(?:\.\d{1,3}){3}:\d+\s+[A-Fa-f0-9]{40}""").containsMatchIn(t)
        }
    }
}
