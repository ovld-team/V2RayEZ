package com.v2rayez.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.v2rayez.app.data.analytics.RemoteTelemetry
import com.v2rayez.app.data.core.ConfigBuilder
import com.v2rayez.app.data.core.GeoAssetManager
import com.v2rayez.app.data.core.ProcessProxyCore
import com.v2rayez.app.data.core.V2RayCore
import com.v2rayez.app.data.service.V2RayVpnService
import com.v2rayez.app.data.service.VpnStateHolder
import com.v2rayez.app.domain.model.ActivityItem
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.TestResult
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
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
    private val remoteTelemetry: RemoteTelemetry
) : VpnController {

    override val connectionState: StateFlow<ConnectionState> = stateHolder.connectionState
    override val weeklyTraffic: StateFlow<List<TrafficPoint>> = stateHolder.weeklyTraffic
    override val liveThroughput: StateFlow<List<ThroughputSample>> = stateHolder.liveThroughput
    override val recentActivity: StateFlow<List<ActivityItem>> = stateHolder.recentActivity

    override fun connect(server: Server) {
        val intent = Intent(context, V2RayVpnService::class.java)
            .setAction(V2RayVpnService.ACTION_CONNECT)
            .putExtra(V2RayVpnService.EXTRA_SERVER_ID, server.id)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun disconnect() {
        val intent = Intent(context, V2RayVpnService::class.java)
            .setAction(V2RayVpnService.ACTION_DISCONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun toggle() {
        when (stateHolder.connectionState.value.status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> disconnect()
            ConnectionStatus.DISCONNECTED -> {
                val intent = Intent(context, V2RayVpnService::class.java)
                    .setAction(V2RayVpnService.ACTION_CONNECT)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    override suspend fun testLatency(server: Server): TestResult = withContext(Dispatchers.IO) {
        val result = try {
            withTimeout(ACCURATE_TIMEOUT_MS) {
                testLatencyInner(server)
            }
        } catch (_: TimeoutCancellationException) {
            // Libv2ray's outbound-delay probe can consume the whole timeout even when the
            // endpoint itself is reachable. Do not discard that reachability signal: this
            // was making Free Servers row tests show every timed-out handshake as dead.
            tcpResult(
                serverId = server.id,
                tcpMs = tcpConnectMs(server.host, server.port, QUICK_TCP_TIMEOUT_MS),
                failureMessage = "Timed out"
            )
        }
        // Dead/slow public servers time out constantly — sampled at ~10% with a
        // false_positive_candidate tag (see RemoteTelemetry) instead of alerting on every one.
        if (!result.success && result.message == "Timed out") {
            runCatching { remoteTelemetry.captureFreeTestTimeout(server.id) }
        }
        result
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
        val start = System.currentTimeMillis()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.currentTimeMillis() - start).coerceAtLeast(1L)
            }
        }.getOrDefault(-1L)
    }

    companion object {
        private const val QUICK_TCP_TIMEOUT_MS = 1_500
        /** Hard ceiling for accurate (Xray + TCP) free/server probes — stalls must surface as Timed out. */
        private const val ACCURATE_TIMEOUT_MS = 10_000L

        internal fun tcpResult(serverId: String, tcpMs: Long, failureMessage: String): TestResult =
            if (tcpMs > 0) {
                TestResult(serverId, tcpMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), true)
            } else {
                TestResult(serverId, -1, false, failureMessage)
            }
    }
}
