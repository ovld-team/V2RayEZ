package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import hev.htproxy.TProxyService
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

    val isRunning: Boolean get() = running

    fun start(tunFd: Int, socksHost: String, socksPort: Int, mtu: Int): Boolean {
        stop()
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

    fun stop() {
        if (!running) return
        if (TProxyService.ensureLoaded()) {
            runCatching { TProxyService.TProxyStopService() }
                .onFailure { Log.e(TAG, "hev stop failed", it) }
        }
        running = false
    }

    /** Returns (txBytes, rxBytes) from hev stats, or 0/0. */
    fun queryBytes(): Pair<Long, Long> {
        if (!TProxyService.ensureLoaded()) return 0L to 0L
        val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: return 0L to 0L
        // [tx_packets, tx_bytes, rx_packets, rx_bytes]
        if (stats.size < 4) return 0L to 0L
        return stats[1] to stats[3]
    }
}
