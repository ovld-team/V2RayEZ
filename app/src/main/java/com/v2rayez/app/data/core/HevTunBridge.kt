package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import hev.htproxy.TProxyService
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal fun hevTrafficDeltas(txDelta: Long, rxDelta: Long): Pair<Long, Long> =
    rxDelta to txDelta

/**
 * Bridges Android VpnService TUN traffic to a local SOCKS5 proxy via hev-socks5-tunnel.
 */
@Singleton
class HevTunBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HevTunBridge"
    }

    @Volatile private var running = false
    private var configFile: File? = null
    private val byteDelta = AbsoluteByteDelta()

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(tunFd: Int, socksHost: String, socksPort: Int, mtu: Int): Boolean {
        stop()
        byteDelta.reset()
        return runCatching {
            val conf = File(context.cacheDir, "hev-tunnel.yml").also { configFile = it }
            conf.writeText(
                """
                |tunnel:
                |  mtu: ${mtu.coerceIn(1280, 1400)}
                |socks5:
                |  port: $socksPort
                |  address: '$socksHost'
                |  udp: 'udp'
                |misc:
                |  task-stack-size: 81920
                """.trimMargin()
            )
            if (!TProxyService.ensureLoaded()) {
                Log.e(TAG, "hev library missing: ${TProxyService.loadError()}")
                return false
            }
            TProxyService.TProxyStartService(conf.absolutePath, tunFd)
            running = true
            true
        }.onFailure { Log.e(TAG, "hev start failed", it) }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        if (running && TProxyService.ensureLoaded()) {
            runCatching { TProxyService.TProxyStopService() }
                .onFailure { Log.e(TAG, "hev stop failed", it) }
        }
        running = false
        byteDelta.reset()
    }

    /**
     * Returns per-query `(downloadDelta, uploadDelta)`.
     *
     * hev exposes cumulative `[tx_packets, tx_bytes, rx_packets, rx_bytes]`: TX is device→SOCKS
     * (upload), RX is SOCKS→device (download). Counter reset/wrap starts a fresh baseline.
     */
    @Synchronized
    fun queryDeltaBytes(): Pair<Long, Long> {
        if (!TProxyService.ensureLoaded()) return 0L to 0L
        val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: return 0L to 0L
        if (stats.size < 4) return 0L to 0L
        val (txDelta, rxDelta) = byteDelta.update(stats[1], stats[3])
        return hevTrafficDeltas(txDelta, rxDelta)
    }

    /** Best available native liveness signal: loaded bridge with a readable stats vector. */
    @Synchronized
    fun isHealthy(): Boolean {
        if (!running || !TProxyService.ensureLoaded()) return false
        return runCatching { TProxyService.TProxyGetStats().size >= 4 }.getOrDefault(false)
    }
}

/** Converts monotonic absolute byte counters to non-negative deltas. */
internal class AbsoluteByteDelta {
    private var previousTx: Long? = null
    private var previousRx: Long? = null

    @Synchronized
    fun update(tx: Long, rx: Long): Pair<Long, Long> {
        val txDelta = delta(previousTx, tx)
        val rxDelta = delta(previousRx, rx)
        previousTx = tx
        previousRx = rx
        return txDelta to rxDelta
    }

    @Synchronized
    fun reset() {
        previousTx = null
        previousRx = null
    }

    private fun delta(previous: Long?, current: Long): Long =
        if (previous == null || current < previous) 0L else current - previous
}
