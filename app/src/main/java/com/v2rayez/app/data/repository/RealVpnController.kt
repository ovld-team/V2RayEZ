package com.v2rayez.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.v2rayez.app.R
import com.v2rayez.app.data.analytics.FirebaseTelemetry
import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.core.GeoAssetManager
import com.v2rayez.app.data.core.ProcessProxyCore
import com.v2rayez.app.data.core.V2RayCore
import com.v2rayez.app.data.service.V2RayVpnService
import com.v2rayez.app.data.service.VpnStateHolder
import com.v2rayez.app.domain.model.ActivityItem
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.TestResult
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealVpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: VpnStateHolder,
    private val core: V2RayCore,
    private val processCore: ProcessProxyCore,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val geoAssets: GeoAssetManager,
    private val firebaseTelemetry: FirebaseTelemetry,
    private val logRepository: LogRepository
) : VpnController {

    override val connectionState: StateFlow<ConnectionState> = stateHolder.connectionState
    override val weeklyTraffic: StateFlow<List<TrafficPoint>> = stateHolder.weeklyTraffic
    override val liveThroughput: StateFlow<List<ThroughputSample>> = stateHolder.liveThroughput
    override val recentActivity: StateFlow<List<ActivityItem>> = stateHolder.recentActivity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun connect(server: Server) {
        val intent = Intent(context, V2RayVpnService::class.java)
            .setAction(V2RayVpnService.ACTION_CONNECT)
            .putExtra(V2RayVpnService.EXTRA_SERVER_ID, server.id)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun disconnect() {
        // stop/disconnect must NOT use startForegroundService — if the VPN service is not
        // already in the foreground, Android will crash with ForegroundServiceDidNotStartInTimeException
        // when onStartCommand never promotes to FG in time (or when the process is busy).
        val intent = Intent(context, V2RayVpnService::class.java)
            .setAction(V2RayVpnService.ACTION_DISCONNECT)
        context.startService(intent)
    }

    override fun toggle() {
        when (stateHolder.connectionState.value.status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> disconnect()
            ConnectionStatus.DISCONNECTED -> {
                // Resolve a real server before FGS — empty default/last must not trip
                // ForegroundServiceDidNotStartInTimeException on a doomed no-server connect.
                scope.launch {
                    val settings = runCatching { settingsRepository.current() }.getOrNull()
                    val id = settings?.defaultServerId ?: settings?.lastServerId
                    val server = id?.let { runCatching { serverRepository.getServer(it) }.getOrNull() }
                    if (server == null) {
                        stateHolder.setError(context.getString(R.string.vpn_error_no_server))
                        return@launch
                    }
                    val intent = Intent(context, V2RayVpnService::class.java)
                        .setAction(V2RayVpnService.ACTION_CONNECT)
                        .putExtra(V2RayVpnService.EXTRA_SERVER_ID, server.id)
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }
    }

    override suspend fun testLatency(server: Server): TestResult = withContext(Dispatchers.IO) {
        // List UI may pass a lite Server (fat columns omitted); hydrate for accurate probes.
        val probeTarget = serverRepository.getServer(server.id) ?: server
        firebaseTelemetry.traceSuspend("free_latency_test", mapOf("protocol" to probeTarget.protocol.name)) {
        val result = try {
            withTimeout(ACCURATE_TIMEOUT_MS) {
                testLatencyInner(probeTarget)
            }
        } catch (_: TimeoutCancellationException) {
            // Libv2ray's outbound-delay probe can consume the whole timeout even when the
            // endpoint itself is reachable. Do not discard that reachability signal: this
            // was making Free Servers row tests show every timed-out handshake as dead.
            tcpResult(
                serverId = probeTarget.id,
                tcpMs = tcpConnectMs(probeTarget.host, probeTarget.port, QUICK_TCP_TIMEOUT_MS),
                failureMessage = "Timed out"
            )
        }
        // Dead/slow public servers time out constantly — sampled at ~10% with a
        // false_positive_candidate tag (see FirebaseTelemetry) instead of alerting on every one.
        if (!result.success && result.message == "Timed out") {
            runCatching { firebaseTelemetry.captureFreeTestTimeout(probeTarget.id) }
            runCatching {
                logRepository.logFree(
                    LogLevel.WARNING,
                    "Free-server test timed out",
                    detail = "protocol=${probeTarget.protocol.name}"
                )
            }
        } else if (result.success) {
            runCatching {
                logRepository.logFree(
                    LogLevel.INFO,
                    "Free-server test ok (${result.pingMs}ms)",
                    detail = "protocol=${probeTarget.protocol.name}"
                )
            }
        } else {
            runCatching {
                logRepository.logFree(
                    LogLevel.WARNING,
                    "Free-server test failed: ${result.message ?: "unknown"}",
                    detail = "protocol=${probeTarget.protocol.name}"
                )
            }
        }
        putAttribute("result", if (result.success) "success" else "failed")
        if (result.success) putMetric("latency_ms", result.pingMs.toLong())
        result
        }
    }

    override suspend fun testSiteFetch(server: Server, url: String): TestResult = withContext(Dispatchers.IO) {
        val probeTarget = serverRepository.getServer(server.id) ?: server
        try {
            withTimeout(ACCURATE_TIMEOUT_MS) {
                testSiteFetchInner(probeTarget, url)
            }
        } catch (_: TimeoutCancellationException) {
            siteResult(probeTarget.id, -1L, false, "Timed out")
        }
    }

    private suspend fun testSiteFetchInner(server: Server, url: String): TestResult {
        val status = stateHolder.connectionState.value.status
        val connected = status == ConnectionStatus.CONNECTED || core.isRunning || processCore.isRunning
        val activeId = stateHolder.connectionState.value.server?.id

        if (connected) {
            if (activeId == server.id) {
                val ms = when {
                    core.isRunning -> core.measureConnectedDelay(url)
                    processCore.isRunning -> processCore.measureViaSocks(processCore.localSocksPort(), url)
                    else -> -1L
                }
                return if (ms > 0) {
                    siteResult(server.id, ms, true, "")
                } else {
                    siteResult(server.id, ms, false, "Site fetch probe failed")
                }
            }
            return siteFetchViaConfig(server, url, vpnActiveOther = true)
        }

        return siteFetchViaConfig(server, url, vpnActiveOther = false)
    }

    private suspend fun siteFetchViaConfig(
        server: Server,
        url: String,
        vpnActiveOther: Boolean
    ): TestResult {
        val settings = settingsRepository.current()
        val frontServer = server.frontProxyId
            ?.takeIf { it != server.id }
            ?.let { serverRepository.getServer(it) }
        val config = ConfigBuilder.buildForTest(
            server, settings, frontServer,
            geositeAvailable = geoAssets.geositeAvailable()
        )
        val delay = core.measureDelay(config, url)
        if (delay > 0) {
            return siteResult(server.id, delay, true, "")
        }
        val message = when {
            vpnActiveOther -> "Site fetch unavailable while VPN uses another server"
            delay < 0 -> "Site fetch probe failed"
            else -> "Timed out"
        }
        return siteResult(server.id, delay, false, message)
    }

    private suspend fun testLatencyInner(server: Server): TestResult {
        val status = stateHolder.connectionState.value.status
        val connected = status == ConnectionStatus.CONNECTED || core.isRunning || processCore.isRunning
        val activeId = stateHolder.connectionState.value.server?.id

        // While VPN is up: active server uses in-tunnel delay; others use TCP connect (no second Xray).
        if (connected) {
            if (activeId == server.id) {
                val ms = when {
                    core.isRunning -> core.measureConnectedDelay()
                    processCore.isRunning -> processCore.measureViaSocks(processCore.localSocksPort())
                    else -> -1L
                }
                return if (ms > 0) {
                    TestResult(server.id, ms.toInt(), true)
                } else {
                    TestResult(server.id, -1, false, "Tunnel latency probe failed")
                }
            }
            val tcp = tcpConnectMs(server.host, server.port)
            return if (tcp > 0) {
                TestResult(server.id, tcp.toInt(), true)
            } else {
                TestResult(server.id, -1, false, "TCP connect failed (VPN is on)")
            }
        }

        // Disconnected: Xray outbound delay, then TCP fallback.
        val settings = settingsRepository.current()
        val frontServer = server.frontProxyId
            ?.takeIf { it != server.id }
            ?.let { serverRepository.getServer(it) }
        val config = ConfigBuilder.buildForTest(
            server, settings, frontServer,
            geositeAvailable = geoAssets.geositeAvailable()
        )
        val delay = core.measureDelay(config)
        if (delay in 0..100_000) {
            return TestResult(server.id, delay.toInt(), true)
        }
        val tcp = tcpConnectMs(server.host, server.port)
        return if (tcp > 0) {
            TestResult(server.id, tcp.toInt(), true)
        } else {
            TestResult(server.id, -1, false, "Timed out")
        }
    }

    /**
     * TCP-connect-only reachability probe (1.5s). Never touches [V2RayCore.exclusive], so
     * bulk scans can run at high concurrency without serializing behind the proxy core or
     * blocking a concurrent VPN connect. Positive ms = endpoint accepts TCP (reachability
     * rank), not proof the proxy protocol handshakes — [testLatency] stays the accurate path.
     */
    override suspend fun testLatencyQuick(server: Server): TestResult = withContext(Dispatchers.IO) {
        val tcp = tcpConnectMs(server.host, server.port, timeoutMs = QUICK_TCP_TIMEOUT_MS)
        tcpResult(server.id, tcp, "TCP connect failed")
    }

    private fun tcpConnectMs(host: String, port: Int, timeoutMs: Int = 5_000): Long {
        if (host.isBlank() || port !in 1..65535) return -1L
        // Socket.connect's timeout does not include a potentially stuck hostname lookup.
        // Bound the whole resolve+connect operation so bulk scans always advance.
        val future = TCP_PROBE_EXECUTOR.submit<Long> {
            val start = System.nanoTime()
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1L)
                }
            }.getOrDefault(-1L)
        }
        return try {
            future.get((timeoutMs + DNS_GRACE_MS).toLong(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            -1L
        }
    }

    companion object {
        private const val QUICK_TCP_TIMEOUT_MS = 1_500
        /** Hard ceiling for accurate (Xray + TCP) free/server probes — stalls must surface as Timed out. */
        private const val ACCURATE_TIMEOUT_MS = 10_000L
        private const val DNS_GRACE_MS = 500
        private val TCP_PROBE_EXECUTOR = Executors.newFixedThreadPool(32) { runnable ->
            Thread(runnable, "free-server-tcp-probe").apply { isDaemon = true }
        }

        internal fun tcpResult(serverId: String, tcpMs: Long, failureMessage: String): TestResult =
            if (tcpMs > 0) {
                TestResult(serverId, tcpMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), true)
            } else {
                TestResult(serverId, -1, false, failureMessage)
            }

        internal fun siteResult(serverId: String, ms: Long, success: Boolean, message: String): TestResult {
            val siteMs = ms.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            return TestResult(
                serverId = serverId,
                pingMs = siteMs ?: -1,
                success = success,
                message = message,
                siteOk = success,
                siteMs = siteMs,
                siteMessage = message.takeIf { it.isNotBlank() }
            )
        }
    }
}
