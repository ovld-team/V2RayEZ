package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.ProxyCoreType

/**
 * Abstraction over the active tunnel engine (bundled Xray AAR or a process core + hev).
 */
interface ProxyCore {
    val type: ProxyCoreType
    val isRunning: Boolean
    fun version(): String
    suspend fun start(configText: String, tunFd: Int): Boolean
    suspend fun stop()
    /** Uplink/downlink bytes since last query (or session), depending on implementation. */
    fun queryTrafficStats(): Pair<Long, Long>
    /**
     * Measure outbound delay for [configText]. Returns ms, or negative on failure.
     * Process cores may return -1 (latency probes use Xray separately).
     */
    suspend fun measureDelay(configText: String, url: String = "https://www.google.com/generate_204"): Long
}
