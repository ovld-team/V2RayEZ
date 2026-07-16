package com.v2rayez.app.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.v2rayez.app.MainActivity
import com.v2rayez.app.R
import com.v2rayez.app.data.core.ClashConfigBuilder
import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.core.CoreBinaryManager
import com.v2rayez.app.data.core.CoreResolver
import com.v2rayez.app.data.core.GeoAssetManager
import com.v2rayez.app.data.core.HevTunBridge
import com.v2rayez.app.data.core.ProcessProxyCore
import com.v2rayez.app.data.core.SingBoxConfigBuilder
import com.v2rayez.app.data.core.V2RayCore
import com.v2rayez.app.data.analytics.FailureCategory
import com.v2rayez.app.data.analytics.FirebaseTelemetry
import com.v2rayez.app.data.cert.MitmCaStore
import com.v2rayez.app.data.download.DownloadTransport
import com.v2rayez.app.data.fronting.DomainFrontEngine
import com.v2rayez.app.data.fronting.FrontAddressResolver
import com.v2rayez.app.data.mitm.MitmConfigBuilder
import com.v2rayez.app.data.sni.ByeDpiEngine
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.data.tor.TorState
import com.v2rayez.app.data.local.SessionEntity
import com.v2rayez.app.data.local.SessionDao
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.DesyncMode
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.torEffectiveSettings
import com.v2rayez.app.domain.model.tunDnsEffectiveSettings
import com.v2rayez.app.data.repository.logCore
import com.v2rayez.app.data.repository.logVpn
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

internal fun needsReconnectTeardown(
    tunnelRunning: Boolean,
    anyEngineRunning: Boolean,
    tunPresent: Boolean
): Boolean = tunnelRunning || anyEngineRunning || tunPresent

/** True when a new CONNECT targets the same server as an in-flight connect (null == null). */
internal fun isSameConnectTarget(requestedId: String?, connectingId: String?): Boolean {
    if (requestedId == null && connectingId == null) return true
    return requestedId != null && requestedId == connectingId
}

/**
 * Death-watchdog consecutive non-null [tunnelDeathReason] counter.
 * Reset to 0 when healthy (null reason); otherwise increment.
 */
internal fun watchdogDeathStreak(previous: Int, deathReason: String?): Int =
    if (deathReason == null) 0 else previous + 1

internal fun watchdogShouldFail(streak: Int, threshold: Int = 3): Boolean = streak >= threshold

/** One-shot auto-reconnect after engine flap — not TUN revoke / no-server / synthetic sessions. */
internal fun shouldWatchdogAutoReconnect(reason: String, serverId: String?): Boolean {
    if (serverId.isNullOrBlank()) return false
    if (serverId == "tor-device" || serverId == "mitm") return false
    val lower = reason.lowercase(Locale.US)
    if (lower.contains("tunnel interface closed")) return false
    if (lower.contains("no server") || reason.contains("سروری")) return false
    return lower.contains("stopped unexpectedly") || lower.contains("tun bridge stopped")
}

internal fun looksLikeGeoFailure(reason: String): Boolean {
    val lower = reason.lowercase(Locale.US)
    return lower.contains("geosite") ||
        lower.contains("geodata") ||
        lower.contains("geoip") ||
        lower.contains("illegal domain")
}

internal fun isPortInUseFailure(error: String?): Boolean {
    val e = error.orEmpty().lowercase(Locale.US)
    return e.contains("address already in use") || e.contains("10808")
}

internal data class TunnelHealthSnapshot(
    val tunPresent: Boolean = true,
    val torRequired: Boolean = false,
    val torConnected: Boolean = true,
    val domainFrontRequired: Boolean = false,
    val domainFrontRunning: Boolean = true,
    val byeDpiRequired: Boolean = false,
    val byeDpiRunning: Boolean = true,
    val protocol: Protocol? = null,
    val processCore: Boolean = false,
    val primaryEngineHealthy: Boolean = true,
    val hevHealthy: Boolean = true,
    val coreLabel: String = "Proxy"
)

internal fun tunnelDeathReason(snapshot: TunnelHealthSnapshot): String? = with(snapshot) {
    when {
        !tunPresent -> "VPN tunnel interface closed unexpectedly"
        torRequired && !torConnected -> "Tor engine stopped unexpectedly"
        domainFrontRequired && !domainFrontRunning -> "Domain fronting engine stopped unexpectedly"
        byeDpiRequired && !byeDpiRunning -> "ByeDPI engine stopped unexpectedly"
        protocol == Protocol.PSIPHON && !primaryEngineHealthy -> "Psiphon engine stopped unexpectedly"
        protocol == Protocol.DNSTUNNEL && !primaryEngineHealthy -> "DNS tunnel engine stopped unexpectedly"
        (protocol == Protocol.PSIPHON || protocol == Protocol.DNSTUNNEL) && !hevHealthy ->
            "TUN bridge stopped unexpectedly"
        processCore && !primaryEngineHealthy -> "$coreLabel process stopped unexpectedly"
        processCore && !hevHealthy -> "TUN bridge stopped unexpectedly"
        !processCore && !primaryEngineHealthy -> "$coreLabel core stopped unexpectedly"
        else -> null
    }
}

internal fun torSupportsServerProtocol(protocol: Protocol): Boolean =
    protocol != Protocol.SSH && !protocol.usesStandaloneEngine()

/**
 * Pure readiness gate for protocols whose runtime is not provided by the bundled Xray AAR.
 * [addonAvailable] is relevant to Psiphon/dnstt; [singBoxAvailable] is relevant to SSH.
 * Keeping this pure makes the missing-pack and present-pack connect decisions unit-testable.
 */
internal fun protocolRuntimeAvailable(
    protocol: Protocol,
    singBoxAvailable: Boolean,
    addonAvailable: Boolean
): Boolean = when (protocol) {
    Protocol.SSH -> singBoxAvailable
    Protocol.PSIPHON, Protocol.DNSTUNNEL -> addonAvailable
    else -> true
}

/** Stable peer address advertised to Android while Xray forwards port 53 to Tor DNSPort. */
internal const val TOR_TUN_DNS_SERVER = "10.10.14.2"

internal fun dnsIpsForTun(settings: AppSettings): List<String> {
    // VpnService.Builder accepts only an IP and always implies port 53. Advertising
    // 127.0.0.1 from "127.0.0.1:9053" silently drops Tor's DNSPort and bypasses the TUN.
    // Use the TUN peer instead; Xray's first-match port-53 rule sends these queries to
    // dns-out, whose upstream remains 127.0.0.1:<Tor DNSPort>.
    if (settings.tor.enabled && (settings.enableLocalDns || settings.dns.enableFakeDns)) {
        return listOf(TOR_TUN_DNS_SERVER)
    }
    val ipv4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    fun extract(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val host = raw.substringAfter("//", raw).substringBefore("/").substringBefore(":")
        return host.takeIf { ipv4.matches(it) }
    }
    return listOfNotNull(extract(settings.dns.remoteDns), extract(settings.dns.domesticDns))
        .distinct()
}

@AndroidEntryPoint
class V2RayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.v2rayez.app.CONNECT"
        const val ACTION_DISCONNECT = "com.v2rayez.app.DISCONNECT"
        const val EXTRA_SERVER_ID = "server_id"
        private const val TAG = "V2RayVpnService"
        private const val CHANNEL_ID = "vpn_status"
        /** Silent, minimal channel used when the user has turned notifications off. */
        private const val CHANNEL_ID_QUIET = "vpn_status_quiet"
        private const val NOTIFICATION_ID = 1
        private const val STATS_INTERVAL_MS = 1000L
        private const val STATS_INTERVAL_BATTERY_SAVER_MS = 4000L
        private const val PING_INTERVAL_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private const val TOR_READY_TIMEOUT_MS = 90_000L
        private const val WATCHDOG_DEATH_THRESHOLD = 3
        private const val AUTO_RECONNECT_DELAY_MS = 1_500L
        /** Process-scoped: one auto-reconnect attempt until user disconnect. */
        private val reconnectAttempted = AtomicBoolean(false)
        /**
         * When true, an async onDestroy worker must not call [stopCoresBlocking] — a watchdog
         * reconnect is about to (or already did) start a new service that shares singleton cores.
         */
        private val skipAsyncCoreStop = AtomicBoolean(false)
    }

    @Inject lateinit var core: V2RayCore
    @Inject lateinit var processCore: ProcessProxyCore
    @Inject lateinit var binaryManager: CoreBinaryManager
    @Inject lateinit var hevTunBridge: HevTunBridge
    @Inject lateinit var byedpi: ByeDpiEngine
    @Inject lateinit var psiphonEngine: com.v2rayez.app.data.psiphon.PsiphonEngine
    @Inject lateinit var dnsTunnelEngine: com.v2rayez.app.data.dnstunnel.DnsTunnelEngine
    @Inject lateinit var domainFrontEngine: DomainFrontEngine
    @Inject lateinit var torController: TorController
    @Inject lateinit var stateHolder: VpnStateHolder
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var downloadTransport: DownloadTransport
    @Inject lateinit var geoAssets: GeoAssetManager
    @Inject lateinit var firebaseTelemetry: FirebaseTelemetry
    @Inject lateinit var mitmProxyState: MitmProxyStateHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var connectJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var pingJob: Job? = null
    private var watchdogJob: Job? = null
    private var activeServer: Server? = null
    private var sessionStartMs: Long = 0L
    /**
     * Local SOCKS inbound port of the Xray (AAR) core once a tunnel is up, so on-demand pack
     * downloads can ride the live tunnel even when NO process core is running (the common case
     * now that only Xray is bundled). 0 = no Xray socks inbound to route through. Process cores
     * report their own port via [ProcessProxyCore.localSocksPort]; both feed the endpoint provider.
     */
    @Volatile private var activeXraySocksPort: Int = 0
    @Volatile private var lastNotificationText: String? = null
    /**
     * Honesty: a foreground VPN service MUST show a notification, but when the user has turned
     * notifications off we route it through a silent IMPORTANCE_MIN channel instead of pretending
     * the toggle is off while still buzzing an ongoing notification.
     */
    @Volatile private var notificationsEnabled: Boolean = true
    private val connectGeneration = AtomicInteger(0)
    /** True when the active tunnel uses a process core + hev instead of in-process Xray. */
    @Volatile private var usingProcessCore: Boolean = false
    @Volatile private var activeCoreType: ProxyCoreType = ProxyCoreType.XRAY
    @Volatile private var activeTorRequired: Boolean = false
    @Volatile private var activeDomainFrontRequired: Boolean = false
    @Volatile private var activeByeDpiRequired: Boolean = false

    private enum class ProbePolicy(val hardFailure: Boolean, val label: String) {
        ORDINARY(false, "ordinary"),
        DOMAIN_FRONT(false, "domain-front"),
        TOR(true, "Tor"),
        MITM(true, "MITM"),
        STANDALONE(false, "standalone")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // This service is always launched via startForegroundService() for CONNECT, so Android
        // REQUIRES a startForeground() call within ~5s on EVERY path (including failures /
        // no-server / disconnect-via-FGS) or it crashes with ForegroundServiceDidNotStartInTimeException.
        // Post a placeholder notification synchronously here before any async work.
        ensureForegroundStarted()

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                connectJob?.cancel()
                connectJob = null
                connectGeneration.incrementAndGet()
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_CONNECT or always-on start (null intent) -> connect requested/last server.
                val serverId = intent?.getStringExtra(EXTRA_SERVER_ID)
                val state = stateHolder.connectionState.value
                val connectingId = state.server?.id ?: activeServer?.id
                if (state.status == ConnectionStatus.CONNECTING &&
                    connectJob?.isActive == true &&
                    isSameConnectTarget(serverId, connectingId)
                ) {
                    Log.i(TAG, "Ignoring duplicate CONNECT while already connecting")
                    return START_STICKY
                }
                // Immediate CONNECTING UI for reconnect taps (per-app / SNI banners).
                state.server?.let { stateHolder.setConnecting(it) }
                connectJob?.cancel()
                val gen = connectGeneration.incrementAndGet()
                connectJob = scope.launch { startTunnel(serverId, gen) }
            }
        }
        return START_STICKY
    }

    /** Best-effort FG promotion; retry once if the first attempt fails (channel race / OEM quirks). */
    private fun ensureForegroundStarted() {
        fun promote(): Boolean = runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    activeServer,
                    getString(R.string.vpn_notif_starting),
                    getString(R.string.vpn_notif_preparing)
                )
            )
            true
        }.onFailure { Log.e(TAG, "startForeground failed", it) }.getOrDefault(false)

        if (promote()) return
        // Channel may not exist yet on cold start — create both channels then retry once.
        runCatching {
            createChannel(quiet = false)
            createChannel(quiet = true)
        }
        if (!promote()) {
            Log.e(TAG, "startForeground failed after retry — FGS deadline at risk")
            runCatching {
                firebaseTelemetry.addBreadcrumb(
                    "vpn",
                    "startForeground failed after retry",
                    com.v2rayez.app.data.analytics.TelemetryLevel.ERROR
                )
            }
        }
    }

    private suspend fun startTunnel(requestedId: String?, generation: Int) =
        lifecycleMutex.withLock { startTunnelLocked(requestedId, generation) }

    private suspend fun startTunnelLocked(requestedId: String?, generation: Int) {
        val connectStartedNs = System.nanoTime()
        val connectTrace = firebaseTelemetry.startTrace("vpn_connect")
        fun phase(name: String) {
            val elapsedMs = (System.nanoTime() - connectStartedNs) / 1_000_000
            log(LogLevel.INFO, "Connect phase [$name] ${elapsedMs}ms")
        }
        try {
            if (generation != connectGeneration.get()) return
            val connectState = stateHolder.connectionState.value
            val connectingId = connectState.server?.id ?: activeServer?.id
            val selfJob = currentCoroutineContext()[Job]
            // Debounce: another in-flight connect for the same target (mutex double-entry).
            if (connectState.status == ConnectionStatus.CONNECTING &&
                isSameConnectTarget(requestedId, connectingId) &&
                connectJob?.isActive == true &&
                connectJob !== selfJob
            ) {
                log(LogLevel.INFO, "Connect already in progress for same server — skip duplicate")
                return
            }
            if (mitmProxyState.running.value) {
                val message = "Standalone MITM proxy is running. Stop it before starting a VPN tunnel."
                log(LogLevel.ERROR, message)
                runCatching { firebaseTelemetry.captureVpnFailure(FailureCategory.MITM, message) }
                stateHolder.setError(message)
                stopForegroundCompat()
                stopSelf()
                return
            }
            val anyEngineRunning = core.isRunning || processCore.isRunning || hevTunBridge.isRunning ||
                psiphonEngine.isRunning || dnsTunnelEngine.isRunning || domainFrontEngine.isRunning ||
                byedpi.isRunning
            if (needsReconnectTeardown(tunnelRunning(), anyEngineRunning, tunInterface != null)) {
                stopTunnelInternal()
            }
            clearActiveRequirements()
            currentCoroutineContext().ensureActive()
            if (generation != connectGeneration.get()) return
            // While this VPN session owns the TUN, any DIRECT on-demand pack download must be
            // protected so it escapes this app's own tunnel instead of looping back into it.
            downloadTransport.setSocketProtector { socket -> runCatching { protect(socket) }.getOrDefault(false) }
            downloadTransport.setProxyEndpointProvider {
                // Prefer a running process core's SOCKS; otherwise ride the Xray AAR socks
                // inbound (activeXraySocksPort, set once the Xray tunnel is confirmed up) so a
                // "mere Xray config" can still proxy on-demand downloads through the tunnel.
                // isRunning gate: localSocksPort() can hold a stale port after a failed spawn.
                val port = processCore.localSocksPort()
                    .takeIf { processCore.isRunning && it in 1..65535 }
                    ?: activeXraySocksPort
                if (port in 1..65535) java.net.InetSocketAddress("127.0.0.1", port) else null
            }
            val settings = settingsRepository.current().tunDnsEffectiveSettings()
            phase("settings")
            runCatching { geoAssets.repairCorruptPackIfNeeded() }
            if (settings.enableLocalDns && !settingsRepository.current().enableLocalDns) {
                log(LogLevel.INFO, "TUN DNS: forcing LocalDNS+sniff so apps resolve through Xray")
            }
            // Foreground service still requires a notification; honor the user's choice by using
            // the silent channel instead of a fully-alerting ongoing notification.
            notificationsEnabled = settings.notifications
            // Device-wide MITM capture only — standalone proxy uses MitmProxyService.
            if (settings.mitm.enabled && settings.mitm.captureAllApps) {
                startMitmTunnel(settings, generation)
                return
            }
            // Tor full-device: no remote server; catch-all → Tor SOCKS.
            if (settings.tor.enabled && settings.tor.routeAllDevice) {
                startTorDeviceTunnel(settings, generation)
                return
            }
            // Power-button / toggle (no explicit id) prefers the user-pinned default, then last used.
            val id = requestedId ?: settings.defaultServerId ?: settings.lastServerId
            val server = id?.let { serverRepository.getServer(it) }
            if (server == null) {
                failAndStop(getString(R.string.vpn_error_no_server))
                return
            }
            ConfigBuilder.validate(server)?.let { reason ->
                failAndStop(reason)
                return
            }
            ConfigBuilder.validateRouting(settings)?.let { reason ->
                failAndStop(reason)
                return
            }
            if (settings.tor.enabled && settings.domainFront.enabled) {
                failAndStop(
                    getString(R.string.vpn_error_tor_fronting_mutex),
                    stopTor = true,
                    category = FailureCategory.TOR
                )
                return
            }
            if (settings.tor.enabled && !torSupportsServerProtocol(server.protocol)) {
                log(LogLevel.WARNING, "Tor is unavailable for ${server.protocol.label}")
                failAndStop(getString(R.string.vpn_error_tor_xray), category = FailureCategory.TOR)
                return
            }
            activeServer = server
            stateHolder.setConnecting(server)
            postNotification(server, getString(R.string.vpn_notif_connecting), getString(R.string.vpn_notif_starting_server, server.name))
            log(LogLevel.INFO, "Connecting to ${server.name} (${server.protocol.label})")

            installCoreStatusLogger()

            // Fail before creating a TUN when the selected protocol has no runnable process.
            // The engines repeat this check immediately before exec to cover a concurrent delete.
            val singBoxAvailable = server.protocol != Protocol.SSH || hasRunnableSingBox(settings)
            val addonAvailable = when (server.protocol) {
                Protocol.PSIPHON -> psiphonEngine.isAvailable()
                Protocol.DNSTUNNEL -> dnsTunnelEngine.isAvailable()
                else -> true
            }
            if (!protocolRuntimeAvailable(server.protocol, singBoxAvailable, addonAvailable)) {
                val message = if (server.protocol == Protocol.SSH) {
                    getString(R.string.vpn_error_singbox_missing, server.protocol.label)
                } else {
                    getString(R.string.vpn_error_pack_missing, server.protocol.label)
                }
                failAndStop(message, needsCoreManager = true)
                return
            }

            val frontServer = server.frontProxyId
                ?.takeIf { it != server.id }
                ?.let { serverRepository.getServer(it) }

            // Start Tor before the core and wait until CONNECTED (or fail).
            if (settings.tor.enabled) {
                currentCoroutineContext().ensureActive()
                if (generation != connectGeneration.get()) {
                    abortConnectCleanup()
                    return
                }
                // Bootstrap-phase auto-rotate in TorController stays enabled during VPN connect;
                // the flag only blocks rotation once the tunnel owns Tor (set after CONNECTED).
                if (torController.status.value.state != TorState.CONNECTED) {
                    log(LogLevel.INFO, "Starting Tor (${settings.tor.transport.label})…")
                    torController.start(settings.tor)
                    val ready = withTimeoutOrNull(TOR_READY_TIMEOUT_MS) {
                        while (isActive) {
                            val st = torController.status.value.state
                            if (st == TorState.CONNECTED) return@withTimeoutOrNull true
                            if (st == TorState.ERROR || st == TorState.OFF) return@withTimeoutOrNull false
                            delay(250)
                        }
                        false
                    } == true
                    if (!ready) {
                        val detail = torController.status.value.message
                            .takeIf { it.isNotBlank() }
                            ?: "Tor did not reach a usable exit"
                        failAndStop(detail, stopTor = true, category = FailureCategory.TOR)
                        return
                    }
                    torController.vpnSessionActive = true
                } else {
                    log(LogLevel.INFO, "Reusing running Tor for VPN tunnel")
                    torController.vpnSessionActive = true
                }
                activeTorRequired = true
                phase("tor-ready")
            }

            currentCoroutineContext().ensureActive()
            if (generation != connectGeneration.get()) {
                abortConnectCleanup()
                return
            }
            val tun = establishTun(server, settings)
            if (tun == null) {
                failAndStop(
                    getString(R.string.vpn_error_tun),
                    stopTor = settings.tor.enabled,
                    category = if (settings.tor.enabled) FailureCategory.TOR else FailureCategory.VPN_CONNECT
                )
                return
            }
            tunInterface = tun
            phase("tun")

            // Domain fronting / byedpi only apply when we will use in-process Xray.
            // Tor standalone tunnel requires Xray (process cores have no Tor-exit wiring).
            // DNS tunnel / Psiphon use a dedicated addon process + hev — before core selection.
            if (server.protocol.usesStandaloneEngine()) {
                startStandaloneEngineTunnel(server, settings, tun.fd, generation)
                return
            }
            var coreType = CoreResolver.resolve(server, settings)
            // WireGuard / SSH run on sing-box (WG endpoint / ssh outbound); Xray can only do WG.
            if (server.protocol.requiresSingBox()) {
                // WireGuard: prefer sing-box, but fall through to the Xray wireguard outbound when
                // no sing-box binary is installed (both are wired in the config builders).
                coreType = if (server.protocol == Protocol.WIREGUARD && !hasRunnableSingBox(settings)) {
                    ProxyCoreType.XRAY
                } else {
                    ProxyCoreType.SING_BOX
                }
            }
            if (settings.tor.enabled && coreType != ProxyCoreType.XRAY) {
                log(LogLevel.WARNING, getString(R.string.vpn_tor_forces_xray, coreType.label))
                coreType = ProxyCoreType.XRAY
            }
            // v0.9.56: sing-box/mihomo are download-only now (libsingbox/libmihomo stripped from
            // the APK). A stale persisted selection pointing at a core with no runnable binary
            // must not hard-fail every connect — fall back to the always-present Xray AAR.
            if (coreType != ProxyCoreType.XRAY) {
                val selVersion = settings.selectedCoreVersions[coreType] ?: CORE_VERSION_BUNDLED
                if (binaryManager.resolveBinary(coreType, selVersion) == null) {
                    log(
                        LogLevel.WARNING,
                        "${coreType.label} $selVersion has no runnable binary on this device — using Xray instead"
                    )
                    coreType = ProxyCoreType.XRAY
                }
            }
            activeCoreType = coreType
            // Built-in Xray uses in-process AAR + TUN. sing-box / Clash Meta use process + hev.
            val useXrayAar = coreType == ProxyCoreType.XRAY
            usingProcessCore = !useXrayAar

            if (!useXrayAar) {
                if (settings.domainFront.enabled) {
                    failAndStop(
                        "Domain fronting requires Xray; selected runtime is ${coreType.label}.",
                        category = FailureCategory.VPN_CONNECT
                    )
                    return
                }
                if (settings.desync.enabled && settings.desync.mode != DesyncMode.NONE) {
                    log(LogLevel.WARNING, "ByeDPI desync is Xray-only; ignored for ${coreType.label}")
                }
                if (settings.fragment.enabled) {
                    log(LogLevel.WARNING, "Xray fragment is Xray-only; ignored for ${coreType.label}")
                }
                if (settings.warp.enabled) {
                    log(LogLevel.WARNING, "WARP outbound is Xray-only; ignored for ${coreType.label}")
                }
            }

            // UAC-style domain fronting: local dialer must be up before Xray dials localhost.
            var domainFrontRunning = false
            if (useXrayAar && settings.domainFront.enabled) {
                currentCoroutineContext().ensureActive()
                if (generation != connectGeneration.get()) return
                domainFrontEngine.setSocketProtector { socket ->
                    runCatching { protect(socket) }.getOrDefault(false)
                }
                domainFrontEngine.onLog = { line ->
                    val failure = listOf(
                        " ERROR ", " TRY_FAIL ", " BLOCKED ", " FAIL ", "SVR_ALERT"
                    ).any(line::contains)
                    log(if (failure) LogLevel.ERROR else LogLevel.DEBUG, "Domain fronting: $line")
                }
                val (frontConfig, resolveNote) = FrontAddressResolver.withResolvedFronts(
                    settings.domainFront,
                    server
                )
                if (resolveNote != null) {
                    log(LogLevel.INFO, "Domain fronting $resolveNote")
                } else {
                    log(
                        LogLevel.WARNING,
                        "Domain fronting: could not resolve ${server.sni.ifBlank { server.host }}; " +
                            "using configured front ${frontConfig.frontAddress}"
                    )
                }
                val ok = domainFrontEngine.start(frontConfig)
                if (!ok) {
                    failAndStop(
                        domainFrontEngine.lastError.ifBlank {
                            getString(
                                R.string.vpn_error_fronting,
                                "${frontConfig.listenHost}:${frontConfig.listenPort}"
                            )
                        }
                    )
                    return
                }
                domainFrontRunning = true
                activeDomainFrontRequired = true
                log(
                    LogLevel.INFO,
                    "Domain fronting dialer started: ${domainFrontEngine.activeTargetLabel}"
                )
            }

            // Start byedpi only when fronting is off and Xray AAR is used.
            var desyncRunning = false
            if (useXrayAar &&
                !domainFrontRunning &&
                settings.desync.enabled &&
                settings.desync.mode != DesyncMode.NONE
            ) {
                currentCoroutineContext().ensureActive()
                if (generation != connectGeneration.get()) return
                byedpi.onLog = { msg -> log(LogLevel.DEBUG, msg) }
                if (!byedpi.isAvailable(this)) {
                    failAndStop(getString(R.string.vpn_error_desync_missing), needsCoreManager = true)
                    return
                }
                desyncRunning = byedpi.start(this, settings.desync)
                if (!desyncRunning) {
                    failAndStop(getString(R.string.vpn_error_desync))
                    return
                }
                activeByeDpiRequired = true
                log(LogLevel.INFO, "Desync engine (byedpi) started: ${settings.desync.mode.label}")
            }

            currentCoroutineContext().ensureActive()
            if (generation != connectGeneration.get()) {
                abortConnectCleanup()
                return
            }

            val started = if (useXrayAar) {
                val config = ConfigBuilder.build(
                    server = server,
                    settings = settings,
                    frontServer = frontServer,
                    desyncRunning = desyncRunning,
                    domainFrontRunning = domainFrontRunning,
                    geositeAvailable = geoAssets.geositeAvailable()
                )
                startCoreLoopWithPortRetry(config, tun.fd)
            } else {
                startProcessCoreTunnel(server, settings, coreType, tun.fd)
            }

            currentCoroutineContext().ensureActive()
            if (generation != connectGeneration.get()) {
                abortConnectCleanup()
                return
            }
            val running = if (useXrayAar) started && core.isRunning else started
            if (!running) {
                failAndStop(coreStartFailureMessage())
                return
            }
            phase("core-running")
            if (domainFrontRunning &&
                !probeTunnelConnectivity(useXrayAar = true, policy = ProbePolicy.DOMAIN_FRONT)
            ) {
                val detail = domainFrontEngine.lastError.ifBlank {
                    "no successful TLS route through the front"
                }
                failAndStop(
                    "Domain fronting connectivity check failed — $detail",
                    category = FailureCategory.VPN_CONNECT
                )
                return
            }
            if (domainFrontRunning) phase("domain-front-probe")
            // Expose the Xray socks inbound so on-demand downloads can proxy through the tunnel
            // (127.0.0.1 reaches it whether it binds loopback or 0.0.0.0 for LAN sharing). Not a
            // full-device Tor session (that's handled in startTorDeviceTunnel).
            activeXraySocksPort = if (useXrayAar) settings.socksPort.takeIf { it in 1..65535 } ?: 0 else 0

            // Ordinary tunnels soft-fail public generate_204 probes: censorship commonly blocks
            // the probe hosts even when the selected proxy is usable. Feature pipelines that
            // would otherwise advertise a dead local hop (domain front, Tor, MITM) hard-fail.
            val torChained = settings.tor.enabled
            if (torChained && !probeTunnelConnectivity(useXrayAar, ProbePolicy.TOR)) {
                val detail = if (torChained) {
                    "${getString(R.string.vpn_error_no_internet)} — Tor SOCKS/exit/DNS probe failed"
                } else {
                    getString(R.string.vpn_error_no_internet)
                }
                failAndStop(
                    coreFailureMessage(detail),
                    stopTor = settings.tor.enabled,
                    category = if (settings.tor.enabled) FailureCategory.TOR else FailureCategory.VPN_CONNECT
                )
                return
            }
            if (torChained) phase("tor-chain-probe")

            // Soft-probe ordinary tunnels before advertising CONNECTED (still soft-fail under
            // censored generate_204). Tor chain already gated above; MITM/Tor-standalone have
            // their own fail-closed probes.
            if (!torChained && !domainFrontRunning) {
                probeTunnelConnectivity(useXrayAar, ProbePolicy.ORDINARY)
                phase("ordinary-probe")
            }

            sessionStartMs = System.currentTimeMillis()
            stateHolder.setConnected(server)
            firebaseTelemetry.logVpnState(connected = true)
            reflectAlwaysOnState()
            settingsRepository.update { it.copy(lastServerId = server.id) }
            postNotification(server, getString(R.string.vpn_notif_connected), "0 B/s ↓  0 B/s ↑")
            val ver = if (useXrayAar) core.coreVersion() else processCore.version()
            log(LogLevel.INFO, "Connected via ${coreType.label}. Core $ver")
            phase("connected")
            connectTrace.putAttribute("result", "success")
            connectTrace.putAttribute("core", coreType.name)
            startStatsLoop(settings.batterySaver, generation)
            startDeathWatchdog(generation)
        } catch (ce: CancellationException) {
            log(LogLevel.INFO, "Connect cancelled")
            connectTrace.putAttribute("result", "cancelled")
            // Superseded by a newer connect (generation bumped): the winner tears down the old
            // core itself at its start; running stopTunnelInternal() here would rip the fresh
            // core / activeXraySocksPort out from under it. Explicit disconnect bumps the
            // generation AND calls stopTunnel() directly, so nothing is leaked by skipping.
            if (generation == connectGeneration.get()) runCatching { stopTunnelInternal() }
            throw ce
        } catch (t: Throwable) {
            connectTrace.putAttribute("result", "failed")
            connectTrace.putAttribute("error_type", t.javaClass.simpleName)
            failAndStop(getString(R.string.vpn_error_generic, t.message ?: t.javaClass.simpleName))
        } finally {
            connectTrace.stop()
        }
    }

    /**
     * Serverless MITM Domain Fronting tunnel: no remote proxy server. A local intercepting
     * Xray config (built by [MitmConfigBuilder]) terminates HTTPS with the on-device CA and
     * re-establishes each connection with a fronted SNI. Forces the in-process Xray AAR and
     * deliberately skips the SNI Front Dialer, ByeDPI/desync and Tor (mutually exclusive).
     */
    private suspend fun startMitmTunnel(settings: AppSettings, generation: Int) {
        val startedNs = System.nanoTime()
        fun phase(name: String) {
            log(LogLevel.INFO, "MITM phase [$name] ${(System.nanoTime() - startedNs) / 1_000_000}ms")
        }
        notificationsEnabled = settings.notifications
        log(LogLevel.INFO, "MITM Domain Fronting session starting")

        // Gate: the on-device CA must exist and be acknowledged before we can decrypt anything.
        if (!MitmCaStore.isPresent(this) || !settings.mitm.caInstallAcknowledged) {
            failAndStop(getString(R.string.vpn_error_mitm_ca), category = FailureCategory.MITM)
            return
        }
        // MITM decrypt/repack is an Xray-only pipeline; refuse non-Xray cores.
        if (settings.defaultCore != ProxyCoreType.XRAY) {
            log(LogLevel.WARNING, getString(R.string.vpn_error_mitm_xray))
            failAndStop(getString(R.string.vpn_error_mitm_xray), category = FailureCategory.MITM)
            return
        }
        activeCoreType = ProxyCoreType.XRAY
        usingProcessCore = false

        // Capture-all is XOR with Tor whole-device.
        if (settings.tor.enabled) {
            failAndStop(getString(R.string.mitm_mutex_tor), category = FailureCategory.MITM)
            return
        }

        val server = mitmSyntheticServer()
        activeServer = server
        stateHolder.setConnecting(server)
        postNotification(server, getString(R.string.vpn_notif_connecting), server.name)

        installCoreStatusLogger()

        // Force whole-device routing for this session (ignore per-app bypass lists).
        val tunnelSettings = settings.copy(fullDeviceTunnel = true)

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) return
        val tun = establishTun(server, tunnelSettings)
        if (tun == null) {
            failAndStop(getString(R.string.vpn_error_tun), category = FailureCategory.MITM)
            return
        }
        tunInterface = tun
        phase("tun")

        // MITM is mutually exclusive with the dialer / ByeDPI; Tor is skipped for this session.
        log(LogLevel.INFO, "Skipping SNI Front Dialer / ByeDPI (MITM capture-all / full-device)")
        if (settings.tor.enabled) log(LogLevel.INFO, "Skipping Tor for MITM session")

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) return

        val certPath = MitmCaStore.crtFile(this).absolutePath
        val keyPath = MitmCaStore.keyFile(this).absolutePath
        val config = MitmConfigBuilder.build(
            settings.mitm,
            certPath,
            keyPath,
            mtu = tunnelSettings.mtu.coerceIn(1280, 1400),
            includeTun = true,
            geositeAvailable = geoAssets.geositeAvailable()
        )
        val started = startCoreLoopWithPortRetry(config, tun.fd)

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) {
            runCatching { stopCoresBlocking() }
            return
        }
        if (!started || !core.isRunning) {
            failAndStop(
                coreStartFailureMessage(),
                category = FailureCategory.MITM
            )
            return
        }
        phase("core-running")

        if (!probeTunnelConnectivity(useXrayAar = true, policy = ProbePolicy.MITM)) {
            failAndStop(
                coreFailureMessage(
                    "${getString(R.string.vpn_error_no_internet)} — MITM tunnel probe failed"
                ),
                category = FailureCategory.MITM
            )
            return
        }
        phase("probe")

        sessionStartMs = System.currentTimeMillis()
        stateHolder.setConnected(server)
        firebaseTelemetry.logVpnState(connected = true)
        reflectAlwaysOnState()
        postNotification(server, getString(R.string.vpn_notif_connected), "0 B/s \u2193  0 B/s \u2191")
        log(LogLevel.INFO, "Connected via MITM Domain Fronting. Core ${core.coreVersion()}")
        phase("connected")
        startStatsLoop(settings.batterySaver, generation)
        startDeathWatchdog(generation)
    }

    /**
     * Tor-only full-device VPN: no remote proxy server. Starts/awaits Tor SOCKS, builds an
     * Xray config with catch-all → [ConfigBuilder.TAG_TOR], and forces [AppSettings.fullDeviceTunnel]
     * semantics for this session.
     */
    private suspend fun startTorDeviceTunnel(settings: AppSettings, generation: Int) {
        val startedNs = System.nanoTime()
        fun phase(name: String) {
            log(LogLevel.INFO, "Tor phase [$name] ${(System.nanoTime() - startedNs) / 1_000_000}ms")
        }
        notificationsEnabled = settings.notifications
        log(LogLevel.INFO, "Tor full-device session starting")

        if (settings.mitm.enabled && settings.mitm.captureAllApps) {
            failAndStop(getString(R.string.tor_mutex_mitm), category = FailureCategory.TOR)
            return
        }
        if (settings.defaultCore != ProxyCoreType.XRAY) {
            log(LogLevel.WARNING, getString(R.string.vpn_error_tor_xray))
            failAndStop(getString(R.string.vpn_error_tor_xray), category = FailureCategory.TOR)
            return
        }
        activeCoreType = ProxyCoreType.XRAY
        usingProcessCore = false

        val server = torSyntheticServer()
        activeServer = server
        stateHolder.setConnecting(server)
        postNotification(server, getString(R.string.vpn_notif_connecting), server.name)

        installCoreStatusLogger()

        // DNS hardening is already shared by torEffectiveSettings(); this path additionally
        // ignores per-app selection because it is explicitly the whole-device Tor mode.
        val tunnelSettings = settings.torEffectiveSettings().copy(fullDeviceTunnel = true)
        ConfigBuilder.validateRouting(tunnelSettings)?.let { reason ->
            failAndStop(reason, stopTor = true, category = FailureCategory.TOR)
            return
        }

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) return

        // Bootstrap-phase auto-rotate in TorController stays enabled during VPN connect;
        // the flag only blocks rotation once the tunnel owns Tor (set after CONNECTED).
        if (torController.status.value.state != TorState.CONNECTED) {
            log(LogLevel.INFO, "Starting Tor (${settings.tor.transport.label})…")
            torController.start(settings.tor)
            val ready = withTimeoutOrNull(TOR_READY_TIMEOUT_MS) {
                while (isActive) {
                    val st = torController.status.value.state
                    if (st == TorState.CONNECTED) return@withTimeoutOrNull true
                    if (st == TorState.ERROR || st == TorState.OFF) return@withTimeoutOrNull false
                    delay(250)
                }
                false
            } == true
            if (!ready) {
                val detail = torController.status.value.message
                    .takeIf { it.isNotBlank() }
                    ?: "Tor did not reach a usable exit"
                failAndStop(detail, stopTor = true, category = FailureCategory.TOR)
                return
            }
            torController.vpnSessionActive = true
        } else {
            log(LogLevel.INFO, "Reusing running Tor for full-device tunnel")
            torController.vpnSessionActive = true
        }
        activeTorRequired = true
        phase("tor-ready")

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) {
            abortConnectCleanup()
            return
        }
        val tun = establishTun(server, tunnelSettings)
        if (tun == null) {
            torController.vpnSessionActive = false
            failAndStop(getString(R.string.vpn_error_tun), stopTor = true, category = FailureCategory.TOR)
            return
        }
        tunInterface = tun
        phase("tun")

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) {
            abortConnectCleanup()
            return
        }

        val config = ConfigBuilder.build(
            server = server,
            settings = tunnelSettings.copy(
                tor = tunnelSettings.tor.copy(enabled = true),
                domainFront = tunnelSettings.domainFront.copy(enabled = false),
                desync = tunnelSettings.desync.copy(enabled = false),
                fragment = tunnelSettings.fragment.copy(enabled = false),
                warp = tunnelSettings.warp.copy(enabled = false)
            ),
            includeTun = true,
            geositeAvailable = geoAssets.geositeAvailable()
        )
        val started = startCoreLoopWithPortRetry(config, tun.fd)

        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) {
            abortConnectCleanup()
            return
        }
        if (!started || !core.isRunning) {
            failAndStop(
                coreStartFailureMessage(),
                stopTor = true,
                category = FailureCategory.TOR
            )
            return
        }
        phase("core-running")
        // Xray socks inbound (→ Tor catch-all) is a valid download proxy here too.
        activeXraySocksPort = tunnelSettings.socksPort.takeIf { it in 1..65535 } ?: 0

        if (!probeTunnelConnectivity(useXrayAar = true, policy = ProbePolicy.TOR)) {
            failAndStop(
                coreFailureMessage(
                    "${getString(R.string.vpn_error_no_internet)} — Tor SOCKS/exit/DNS probe failed"
                ),
                stopTor = true,
                category = FailureCategory.TOR
            )
            return
        }
        phase("probe")

        sessionStartMs = System.currentTimeMillis()
        stateHolder.setConnected(server)
        firebaseTelemetry.logVpnState(connected = true)
        reflectAlwaysOnState()
        postNotification(server, getString(R.string.vpn_notif_connected), "0 B/s \u2193  0 B/s \u2191")
        log(LogLevel.INFO, "Connected via Tor full-device. Core ${core.coreVersion()}")
        phase("connected")
        startStatsLoop(settings.batterySaver, generation)
        startDeathWatchdog(generation)
    }

    /** Synthetic loopback server used for MITM sessions so UI/state/notifications have a subject. */
    private fun mitmSyntheticServer(): Server = Server(
        id = "mitm",
        name = getString(R.string.mitm_session_name),
        country = "",
        countryCode = "",
        protocol = Protocol.VLESS,
        transport = "",
        security = "",
        sni = "",
        address = "127.0.0.1",
        pingMs = -1,
        signal = 0,
        group = ServerGroup.MANUAL,
        host = "127.0.0.1",
        port = 443,
        uuid = "00000000-0000-0000-0000-000000000000"
    )

    /** Synthetic subject for Tor full-device sessions (proxy outbound unused; catch-all → Tor). */
    private fun torSyntheticServer(): Server = Server(
        id = "tor-device",
        name = getString(R.string.tor_session_name),
        country = "",
        countryCode = "",
        protocol = Protocol.VLESS,
        transport = "",
        security = "",
        sni = "",
        address = "127.0.0.1",
        pingMs = -1,
        signal = 0,
        group = ServerGroup.MANUAL,
        host = "127.0.0.1",
        port = 443,
        uuid = "00000000-0000-0000-0000-000000000001"
    )

    private suspend fun startProcessCoreTunnel(
        server: Server,
        settings: AppSettings,
        coreType: ProxyCoreType,
        tunFd: Int
    ): Boolean {
        val port = settings.socksPort.coerceIn(1024, 65535)
        val version = settings.selectedCoreVersions[coreType] ?: CORE_VERSION_BUNDLED
        val runType = if (coreType == ProxyCoreType.CLASH) ProxyCoreType.CLASH else ProxyCoreType.SING_BOX
        val runVersion = if (runType == ProxyCoreType.CLASH) version
        else settings.selectedCoreVersions[ProxyCoreType.SING_BOX] ?: CORE_VERSION_BUNDLED
        val configText = when (runType) {
            ProxyCoreType.CLASH -> ClashConfigBuilder.build(server, settings, port)
            else -> SingBoxConfigBuilder.build(server, settings, port)
        }
        val ok = processCore.startProcess(
            type = runType,
            configText = configText,
            selectedVersion = runVersion,
            listenPort = port
        )
        if (!ok) {
            val ver = settings.selectedCoreVersions[runType] ?: CORE_VERSION_BUNDLED
            val why = binaryManager.lastResolveError(runType, ver)
            log(LogLevel.ERROR, getString(R.string.vpn_error_core_missing, runType.label) + " ($why)")
            return false
        }

        val hevOk = hevTunBridge.start(tunFd, "127.0.0.1", port, settings.mtu.coerceIn(1280, 1400))
        if (!hevOk) {
            processCore.stopProcess()
            log(LogLevel.ERROR, "hev-socks5-tunnel failed to start")
            return false
        }
        usingProcessCore = true
        return true
    }

    /** True when a runnable sing-box binary (selected version, else bundled) exists on this device. */
    private fun hasRunnableSingBox(settings: AppSettings): Boolean {
        val v = settings.selectedCoreVersions[ProxyCoreType.SING_BOX] ?: CORE_VERSION_BUNDLED
        return binaryManager.resolveBinary(ProxyCoreType.SING_BOX, v) != null
    }

    /**
     * DNS-tunnel / Psiphon: addon process opens a local SOCKS forwarder that [HevTunBridge]
     * bridges to TUN. No proxy core, domain fronting, byedpi, or Tor.
     */
    private suspend fun startStandaloneEngineTunnel(
        server: Server,
        settings: AppSettings,
        tunFd: Int,
        generation: Int
    ) {
        val port = settings.socksPort.coerceIn(1024, 65535)
        activeCoreType = ProxyCoreType.SING_BOX
        usingProcessCore = true

        val engineLabel: String
        val started: Boolean
        val socksPort: Int
        when (server.protocol) {
            Protocol.PSIPHON -> {
                engineLabel = server.protocol.label
                psiphonEngine.onLog = { msg -> log(LogLevel.DEBUG, msg) }
                if (!psiphonEngine.isAvailable()) {
                    failAndStop(getString(R.string.vpn_error_pack_missing, engineLabel), needsCoreManager = true)
                    return
                }
                started = psiphonEngine.start(server, port)
                socksPort = psiphonEngine.localSocksPort().takeIf { it in 1..65535 } ?: port
            }
            Protocol.DNSTUNNEL -> {
                engineLabel = server.protocol.label
                dnsTunnelEngine.onLog = { msg -> log(LogLevel.DEBUG, msg) }
                if (!dnsTunnelEngine.isAvailable()) {
                    failAndStop(getString(R.string.vpn_error_pack_missing, engineLabel), needsCoreManager = true)
                    return
                }
                started = dnsTunnelEngine.start(server, port)
                socksPort = dnsTunnelEngine.localTcpPort().takeIf { it in 1..65535 } ?: port
            }
            else -> {
                failAndStop(getString(R.string.vpn_error_core))
                return
            }
        }
        currentCoroutineContext().ensureActive()
        if (generation != connectGeneration.get()) {
            abortConnectCleanup()
            return
        }
        if (!started) {
            failAndStop(getString(R.string.vpn_error_protocol_engine, engineLabel))
            return
        }
        val hevOk = hevTunBridge.start(tunFd, "127.0.0.1", socksPort, settings.mtu.coerceIn(1280, 1400))
        if (!hevOk) {
            runCatching { psiphonEngine.stop() }
            runCatching { dnsTunnelEngine.stop() }
            failAndStop(getString(R.string.vpn_error_protocol_engine, engineLabel))
            return
        }
        probeTunnelConnectivity(
            useXrayAar = false,
            policy = ProbePolicy.STANDALONE,
            socksPortOverride = socksPort
        )
        sessionStartMs = System.currentTimeMillis()
        stateHolder.setConnected(server)
        firebaseTelemetry.logVpnState(connected = true)
        reflectAlwaysOnState()
        settingsRepository.update { it.copy(lastServerId = server.id) }
        postNotification(server, getString(R.string.vpn_notif_connected), "0 B/s \u2193  0 B/s \u2191")
        log(LogLevel.INFO, "Connected via $engineLabel")
        startStatsLoop(settings.batterySaver, generation)
        startDeathWatchdog(generation)
    }

    private fun stopCoresBlocking() {
        runCatching { hevTunBridge.stop() }
        runCatching { runBlocking { processCore.stopProcess() } }
        runCatching { runBlocking { psiphonEngine.stop() } }
        runCatching { runBlocking { dnsTunnelEngine.stop() } }
        runCatching { core.stopLoopBlocking() }
    }

    /**
     * VPN connect generation was superseded mid-connect — tear down TUN/Tor/cores so
     * [vpnSessionActive] and orphan processes do not linger.
     */
    private fun abortConnectCleanup(reason: String = "connect superseded") {
        log(LogLevel.WARNING, reason)
        statsJob?.cancel()
        statsJob = null
        pingJob?.cancel()
        pingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        runCatching { runBlocking { byedpi.stop() } }
        runCatching { domainFrontEngine.stop() }
        torController.vpnSessionActive = false
        val keepTor = runCatching {
            runBlocking { settingsRepository.current().tor.enabled }
        }.getOrDefault(false)
        if (!keepTor) {
            runCatching { runBlocking { torController.stop() } }
        }
        stopCoresBlocking()
        usingProcessCore = false
        activeXraySocksPort = 0
        clearActiveRequirements()
        runCatching { tunInterface?.close() }
        tunInterface = null
        activeServer = null
        sessionStartMs = 0L
        stateHolder.setDisconnected()
        firebaseTelemetry.logVpnState(connected = false)
    }

    /**
     * Clean up any partial tunnel state, surface [message] to the UI, and stop the service safely.
     * [needsCoreManager] flags a missing on-demand pack/core (sing-box, Psiphon, DNS tunnel,
     * ByeDPI, …) or geo-data failure so Home can render an "Open Core manager" CTA independent
     * of the (possibly localized) message text.
     *
     * [scheduleReconnectServerId]: when set (watchdog engine-flap), schedule one delayed CONNECT
     * after teardown. Uses applicationContext so stopSelf()/onDestroy cannot cancel it.
     */
    private fun failAndStop(
        message: String,
        stopTor: Boolean = false,
        category: FailureCategory = FailureCategory.VPN_CONNECT,
        needsCoreManager: Boolean = false,
        scheduleReconnectServerId: String? = null
    ) {
        log(LogLevel.ERROR, message)
        runCatching { firebaseTelemetry.captureVpnFailure(category, message) }
        // A watchdog failure can happen after a long valid session. Persist its final counters
        // before setError() resets the state-holder totals.
        runCatching { runBlocking { recordSession() } }
        val geoCta = looksLikeGeoFailure(message)
        stateHolder.setError(message, needsCoreManager = needsCoreManager || geoCta)
        statsJob?.cancel()
        statsJob = null
        pingJob?.cancel()
        pingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        runCatching { runBlocking { byedpi.stop() } }
        runCatching { domainFrontEngine.stop() }
        torController.vpnSessionActive = false
        val keepTor = !stopTor && runCatching {
            runBlocking { settingsRepository.current().tor.enabled }
        }.getOrDefault(false)
        if (!keepTor) {
            runCatching { runBlocking { torController.stop() } }
        }
        stopCoresBlocking()
        usingProcessCore = false
        activeXraySocksPort = 0
        clearActiveRequirements()
        runCatching { tunInterface?.close() }
        tunInterface = null
        activeServer = null
        sessionStartMs = 0L
        stopForegroundCompat()
        firebaseTelemetry.logVpnState(connected = false)
        val reconnectId = scheduleReconnectServerId
            ?.takeIf { shouldWatchdogAutoReconnect(message, it) }
            ?.takeIf { reconnectAttempted.compareAndSet(false, true) }
        if (reconnectId != null) {
            skipAsyncCoreStop.set(true)
        }
        stopSelf()
        if (reconnectId != null) {
            scheduleOneShotReconnect(reconnectId)
        }
    }

    private fun scheduleOneShotReconnect(serverId: String) {
        val app = applicationContext
        Thread({
            try {
                Thread.sleep(AUTO_RECONNECT_DELAY_MS)
                val intent = Intent(app, V2RayVpnService::class.java)
                    .setAction(ACTION_CONNECT)
                    .putExtra(EXTRA_SERVER_ID, serverId)
                ContextCompat.startForegroundService(app, intent)
                Log.i(TAG, "Watchdog one-shot auto-reconnect launched")
            } catch (t: Throwable) {
                Log.w(TAG, "Watchdog auto-reconnect failed", t)
            }
        }, "vpn-watchdog-reconnect").apply { isDaemon = true }.start()
    }

    private fun establishTun(server: Server, settings: AppSettings): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setSession(server.name)
        builder.setMtu(settings.mtu.coerceIn(1280, 1400))
        builder.addAddress("10.10.14.1", 30)
        builder.addRoute("0.0.0.0", 0)
        if (settings.enableIpv6) {
            builder.addAddress("fdfe:dcba:9876::1", 126)
            builder.addRoute("::", 0)
        }
        // DNS: only IP literals are valid for the tun interface. Tor uses a stable TUN peer
        // here so Android's implicit :53 reaches Xray, then dns-out reaches Tor's real DNSPort.
        val dnsIps = dnsIpsForTun(settings).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        dnsIps.forEach { runCatching { builder.addDnsServer(it) } }

        configurePerApp(builder, settings)

        return runCatching {
            // API 29+: non-blocking matches hev/UAC; gVisor expects a non-blocking fd.
            // Calling these on API 26–28 throws and used to abort establish() entirely.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setBlocking(false)
                runCatching { builder.setMetered(false) }
            }
            builder.establish()
        }.onFailure {
            log(LogLevel.ERROR, "TUN establish failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * One connectivity policy for every tunnel mode.
     *
     * Ordinary Xray/process, MITM, and standalone addon tunnels soft-fail because public
     * generate_204 hosts are frequently blocked by the censorship the app is bypassing. Tor is a
     * hard gate: both its SOCKS listener and an exit HTTP request must work before CONNECTED.
     */
    private suspend fun probeTunnelConnectivity(
        useXrayAar: Boolean,
        policy: ProbePolicy,
        socksPortOverride: Int? = null
    ): Boolean {
        delay(150) // let gVisor/hev settle
        val urls = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204"
        )
        if (policy == ProbePolicy.TOR) {
            val torCfg = settingsRepository.current().tor
            val socksOk = torController.testSocksReachable(torCfg, timeoutMs = 4_000)
            if (!socksOk) {
                log(LogLevel.ERROR, "Connectivity probe failed (Tor SOCKS down)")
                return false
            }
            for (url in urls) {
                val ms = if (useXrayAar) {
                    core.measureConnectedDelay(url)
                } else {
                    processCore.measureViaSocks(
                        processCore.localSocksPort().takeIf { it > 0 } ?: torCfg.socksPort,
                        url,
                        timeoutMs = 15_000
                    )
                }
                if (ms > 0) {
                    stateHolder.setPing(ms.toInt())
                    return true
                }
                delay(200)
            }
            log(LogLevel.ERROR, "Connectivity probe failed (Tor exit / DNS)")
            return false
        }

        val port = socksPortOverride?.takeIf { it in 1..65535 }
            ?: processCore.localSocksPort().takeIf { it in 1..65535 }
            ?: settingsRepository.current().socksPort
        for (url in urls) {
            val ms = if (useXrayAar) {
                core.measureConnectedDelay(url)
            } else {
                processCore.measureViaSocks(port, url)
            }
            if (ms > 0) {
                stateHolder.setPing(ms.toInt())
                return true
            }
            delay(100)
        }
        val endpoint = if (useXrayAar) "Xray" else "SOCKS port=$port"
        val level = if (policy.hardFailure) LogLevel.ERROR else LogLevel.WARNING
        log(level, "${policy.label} connectivity probe failed ($endpoint) — keeping live tunnel")
        return !policy.hardFailure
    }

    /**
     * Query the OS for the real Always-on / lockdown state of this session (API 29+) and push it
     * to [VpnStateHolder] so Settings can reflect the truth rather than a fake app toggle.
     */
    private fun reflectAlwaysOnState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val alwaysOn = runCatching { isAlwaysOn }.getOrDefault(false)
            val lockdown = runCatching { isLockdownEnabled }.getOrDefault(false)
            stateHolder.setAlwaysOnState(alwaysOn, lockdown)
        }
    }

    private fun configurePerApp(builder: Builder, settings: AppSettings) {
        val decision = com.v2rayez.app.data.vpn.PerAppTunnelPolicy.decide(settings, packageName)
        if (decision.degradedToFullDevice || decision.conflictWithFullDeviceTunnel) {
            log(
                LogLevel.WARNING,
                com.v2rayez.app.data.vpn.PerAppTunnelPolicy.conflictMessage(settings)
                    ?: "Per-app policy degraded to full-device (except self)"
            )
        }
        val failures = com.v2rayez.app.data.vpn.PerAppTunnelPolicy.applyToBuilder(
            addDisallowed = { pkg -> builder.addDisallowedApplication(pkg) },
            addAllowed = { pkg -> builder.addAllowedApplication(pkg) },
            decision = decision
        )
        if (failures.isNotEmpty()) {
            val detail = failures.sorted().joinToString(", ")
            throw IllegalStateException(
                "Per-app routing could not apply package list: $detail. " +
                    "Refresh the app list or remove unavailable apps, then reconnect."
            )
        }
    }

    private fun installCoreStatusLogger() {
        core.onStatus = { code, message ->
            if (message.isNotBlank()) {
                val lower = message.lowercase()
                val failure = code != 0L || listOf(
                    "error", "failed", "failure", "invalid", "cannot", "unable", "panic", "fatal"
                ).any(lower::contains)
                val level = if (failure) LogLevel.ERROR else LogLevel.DEBUG
                val text = "Xray status $code: $message"
                logRepository.logCore(level, text)
                firebaseTelemetry.addLogBreadcrumb("core", level, text)
            }
        }
    }

    private fun coreFailureMessage(fallback: String): String =
        core.lastStartError()?.takeIf { it.isNotBlank() }?.let { "$fallback — $it" } ?: fallback

    /**
     * Start Xray after an explicit stop; on "address already in use" / 10808, stop + delay + retry once.
     */
    private suspend fun startCoreLoopWithPortRetry(configJson: String, tunFd: Int): Boolean {
        core.stopLoopBlocking()
        var started = core.startLoop(configJson, tunFd)
        if (!started && isPortInUseFailure(core.lastStartError())) {
            log(LogLevel.WARNING, "Local proxy port in use — stopping core and retrying once")
            core.stopLoopBlocking()
            delay(300)
            started = core.startLoop(configJson, tunFd)
        }
        return started
    }

    private fun coreStartFailureMessage(): String {
        val detail = core.lastStartError().orEmpty()
        return if (isPortInUseFailure(detail)) {
            "Local SOCKS port is already in use — close other proxy apps and retry"
        } else {
            coreFailureMessage(getString(R.string.vpn_error_core))
        }
    }

    private fun startStatsLoop(batterySaver: Boolean, generation: Int) {
        statsJob?.cancel()
        pingJob?.cancel()
        val intervalMs = if (batterySaver) STATS_INTERVAL_BATTERY_SAVER_MS else STATS_INTERVAL_MS
        statsJob = scope.launch {
            while (isActive && generation == connectGeneration.get() && tunnelRunning()) {
                val (down, up) = if (usingProcessCore || standaloneEngineRunning()) {
                    hevTunBridge.queryDeltaBytes()
                } else {
                    val snap = core.queryTrafficStats()
                    snap.totalDown to snap.totalUp
                }
                if (generation != connectGeneration.get()) return@launch
                stateHolder.onStatsTick(down, up, intervalMs / 1000.0)
                val st = stateHolder.connectionState.value
                val speedLine = "${st.downloadLabel} \u2193  ${st.uploadLabel} \u2191"
                val sessionLine = "Session ${st.sessionDownLabel} \u2193 / ${st.sessionUpLabel} \u2191"
                val uptime = Formatters.uptime(st.uptimeSeconds)
                updateNotification(
                    activeServer,
                    title = "$speedLine · $uptime",
                    text = sessionLine
                )
                delay(intervalMs)
            }
        }
        pingJob = scope.launch {
            while (isActive && generation == connectGeneration.get() && tunnelRunning()) {
                val ms = when {
                    psiphonEngine.isRunning -> processCore.measureViaSocks(psiphonEngine.localSocksPort())
                    dnsTunnelEngine.isRunning -> processCore.measureViaSocks(dnsTunnelEngine.localTcpPort())
                    usingProcessCore -> processCore.measureViaSocks(processCore.localSocksPort())
                    else -> core.measureConnectedDelay()
                }
                if (generation != connectGeneration.get()) return@launch
                if (ms > 0) stateHolder.setPing(ms.toInt())
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun startDeathWatchdog(generation: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var deathStreak = 0
            while (isActive && generation == connectGeneration.get()) {
                delay(WATCHDOG_INTERVAL_MS)
                if (generation != connectGeneration.get()) return@launch
                lifecycleMutex.withLock {
                    if (generation != connectGeneration.get()) return@launch
                    if (stateHolder.connectionState.value.status != ConnectionStatus.CONNECTED) {
                        deathStreak = 0
                        return@withLock
                    }
                    val reason = tunnelDeathReason()
                    deathStreak = watchdogDeathStreak(deathStreak, reason)
                    if (reason == null || !watchdogShouldFail(deathStreak, WATCHDOG_DEATH_THRESHOLD)) {
                        return@withLock
                    }
                    if (generation != connectGeneration.get()) return@launch
                    val serverId = activeServer?.id
                    val reconnectId = serverId?.takeIf { shouldWatchdogAutoReconnect(reason, it) }
                    failAndStop(
                        message = reason,
                        stopTor = serverId == "tor-device",
                        category = FailureCategory.VPN_WATCHDOG,
                        scheduleReconnectServerId = reconnectId
                    )
                }
                if (stateHolder.connectionState.value.status != ConnectionStatus.CONNECTED) return@launch
            }
        }
    }

    private fun tunnelDeathReason(): String? {
        val protocol = activeServer?.protocol
        val primaryHealthy = when (protocol) {
            Protocol.PSIPHON -> psiphonEngine.isRunning
            Protocol.DNSTUNNEL -> dnsTunnelEngine.isRunning
            else -> if (usingProcessCore) processCore.isHealthy else core.isRunning
        }
        return tunnelDeathReason(
            TunnelHealthSnapshot(
                tunPresent = tunInterface != null,
                torRequired = activeTorRequired,
                torConnected = torController.status.value.state == TorState.CONNECTED,
                domainFrontRequired = activeDomainFrontRequired,
                domainFrontRunning = domainFrontEngine.isRunning,
                byeDpiRequired = activeByeDpiRequired,
                byeDpiRunning = byedpi.isRunning,
                protocol = protocol,
                processCore = usingProcessCore,
                primaryEngineHealthy = primaryHealthy,
                hevHealthy = hevTunBridge.isHealthy(),
                coreLabel = activeCoreType.label
            )
        )
    }

    private fun clearActiveRequirements() {
        activeTorRequired = false
        activeDomainFrontRequired = false
        activeByeDpiRequired = false
    }

    /** Psiphon / DNS-tunnel PIE + hev TUN — not `ProcessProxyCore`. */
    private fun standaloneEngineRunning(): Boolean =
        (psiphonEngine.isRunning || dnsTunnelEngine.isRunning) && hevTunBridge.isRunning

    private fun tunnelRunning(): Boolean = when {
        standaloneEngineRunning() -> true
        usingProcessCore -> processCore.isRunning && hevTunBridge.isRunning
        else -> core.isRunning
    }

    private fun stopTunnel() {
        connectJob?.cancel()
        connectJob = null
        connectGeneration.incrementAndGet()
        // User/system disconnect resets the one-shot watchdog reconnect budget.
        reconnectAttempted.set(false)
        skipAsyncCoreStop.set(false)
        scope.launch {
            lifecycleMutex.withLock {
                stopTunnelInternal()
                stopSelf()
            }
        }
    }

    private suspend fun stopTunnelInternal() {
        statsJob?.cancel()
        statsJob = null
        pingJob?.cancel()
        pingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        runCatching { byedpi.stop() }
        runCatching { domainFrontEngine.stop() }
        torController.vpnSessionActive = false
        val keepTor = runCatching { settingsRepository.current().tor.enabled }.getOrDefault(false)
        if (!keepTor) {
            runCatching { torController.stop() }
        } else {
            log(LogLevel.INFO, "Leaving Tor running (tor.enabled)")
        }
        // Always tear down standalone PIE engines — not covered by processCore/Xray alone.
        runCatching { hevTunBridge.stop() }
        runCatching { processCore.stopProcess() }
        runCatching { psiphonEngine.stop() }
        runCatching { dnsTunnelEngine.stop() }
        try {
            core.stopLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "stopLoop failed", t)
        }
        usingProcessCore = false
        activeXraySocksPort = 0
        clearActiveRequirements()
        runCatching { tunInterface?.close() }
        tunInterface = null
        recordSession()
        stateHolder.setDisconnected()
        firebaseTelemetry.logVpnState(connected = false)
        log(LogLevel.INFO, "Disconnected")
        stopForegroundCompat()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private suspend fun recordSession() {
        val server = activeServer ?: return
        if (sessionStartMs == 0L) return
        val down = stateHolder.sessionDown.value
        val up = stateHolder.sessionUp.value
        runCatching {
            sessionDao.insert(
                SessionEntity(
                    serverId = server.id,
                    serverName = server.name,
                    countryCode = server.countryCode,
                    protocol = server.protocol.name,
                    downBytes = down,
                    upBytes = up,
                    startedAt = sessionStartMs,
                    endedAt = System.currentTimeMillis()
                )
            )
        }
        sessionStartMs = 0L
        activeServer = null
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val establishedForegroundVpn = tunInterface != null &&
            stateHolder.connectionState.value.status == com.v2rayez.app.domain.model.ConnectionStatus.CONNECTED &&
            tunnelRunning()
        if (establishedForegroundVpn) {
            log(LogLevel.INFO, "Task removed; keeping established foreground VPN active")
        } else {
            stopTunnel()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val pendingConnect = connectJob
        pendingConnect?.cancel()
        connectJob = null
        statsJob?.cancel()
        statsJob = null
        pingJob?.cancel()
        pingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        downloadTransport.setSocketProtector(null)
        downloadTransport.setProxyEndpointProvider(null)
        // Close TUN immediately on the calling thread (cheap, must not leak).
        runCatching { tunInterface?.close() }
        tunInterface = null
        // Crashlytics ANR: never runBlocking on the main thread in onDestroy. Offload
        // session persistence + engine stops to a short-lived worker; do not join.
        val keepMitmCore = mitmProxyState.running.value
        val destroyGeneration = connectGeneration.get()
        Thread({
            runCatching {
                runBlocking(Dispatchers.IO) {
                    runCatching { pendingConnect?.join() }
                    // If a newer CONNECT (e.g. watchdog auto-reconnect) already started,
                    // do not wipe UI state or kill the new core from this stale destroy worker.
                    if (destroyGeneration != connectGeneration.get() || skipAsyncCoreStop.get()) {
                        Log.i(TAG, "onDestroy cleanup skipped — reconnect in flight or generation advanced")
                        return@runBlocking
                    }
                    runCatching {
                        recordSession()
                        stateHolder.setDisconnected()
                        firebaseTelemetry.logVpnState(connected = false)
                    }
                    runCatching { byedpi.stop() }
                    runCatching { domainFrontEngine.stop() }
                    torController.vpnSessionActive = false
                    val keepTor = runCatching { settingsRepository.current().tor.enabled }.getOrDefault(false)
                    if (!keepTor) {
                        runCatching { torController.stop() }
                    }
                }
            }.onFailure { Log.e(TAG, "onDestroy cleanup failed", it) }
            if (destroyGeneration != connectGeneration.get()) return@Thread
            // Skip core teardown when a watchdog reconnect is in flight — V2RayCore/ProcessProxyCore
            // are process singletons and would kill the new tunnel.
            val skipCores = skipAsyncCoreStop.getAndSet(false) || keepMitmCore
            if (!skipCores) stopCoresBlocking()
            usingProcessCore = false
            activeXraySocksPort = 0
            clearActiveRequirements()
        }, "vpn-ondestroy").apply { isDaemon = true }.start()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- notification
    private fun buildNotification(server: Server?, title: String, text: String): Notification {
        val quiet = !notificationsEnabled
        val channelId = if (quiet) CHANNEL_ID_QUIET else CHANNEL_ID
        createChannel(quiet)
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1, Intent(this, V2RayVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(server?.let { "${it.name} · $title" } ?: title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$text"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.vpn_notif_disconnect), disconnectIntent)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(server: Server?, title: String, text: String) {
        val key = "$title|$text"
        if (key == lastNotificationText) return
        postNotification(server, title, text)
    }

    private fun updateNotification(server: Server?, text: String) {
        updateNotification(server, getString(R.string.vpn_notif_connected), text)
    }

    private fun postNotification(server: Server?, title: String, text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(server, title, text))
            lastNotificationText = "$title|$text"
        }.onFailure { Log.e(TAG, "notify failed", it) }
    }

    private fun createChannel(quiet: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (quiet) {
            if (nm.getNotificationChannel(CHANNEL_ID_QUIET) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID_QUIET, "VPN Status (silent)", NotificationManager.IMPORTANCE_MIN)
                        .apply {
                            setShowBadge(false)
                            setSound(null, null)
                            enableVibration(false)
                        }
                )
            }
            return
        }
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null || existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            if (existing != null) nm.deleteNotificationChannel(CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        }
    }

    private fun log(level: LogLevel, message: String) {
        logRepository.logVpn(level, message)
        firebaseTelemetry.addLogBreadcrumb("vpn", level, message)
    }
}
