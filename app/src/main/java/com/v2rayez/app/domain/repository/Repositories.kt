package com.v2rayez.app.domain.repository

import com.v2rayez.app.domain.model.ActivityItem
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.BackupSnapshot
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.ImportResult
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.StatsTotals
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.model.TestResult
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.TopServer
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.model.UsageSlice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Default URL for HTTP site-fetch connectivity probes (204/no-content). */
const val DEFAULT_SITE_FETCH_URL = "https://www.gstatic.com/generate_204"

/**
 * Backend-facing contracts. The UI depends only on these interfaces; the real
 * V2Ray-backed implementations live under `data/`, mocks under `data/mock`.
 */
interface VpnController {
    /** Live connection snapshot (status, server, uptime, traffic, ping). */
    val connectionState: StateFlow<ConnectionState>

    /** Live weekly traffic buckets for the Home chart. */
    val weeklyTraffic: StateFlow<List<TrafficPoint>>

    /** Rolling ~60s window of per-second throughput samples for the live Home graph. */
    val liveThroughput: StateFlow<List<ThroughputSample>>

    /** Live recent-activity feed for the Home screen. */
    val recentActivity: StateFlow<List<ActivityItem>>

    /** Start (or switch) the tunnel to [server]. */
    fun connect(server: Server)

    /** Stop the tunnel. */
    fun disconnect()

    /** Reconnect the current / last server. */
    fun toggle()

    /** Measure real handshake latency to [server] (no active tunnel required). */
    suspend fun testLatency(server: Server): TestResult

    /**
     * Fast reachability rank: TCP connect only, short timeout, no proxy-core involvement —
     * safe to run at high concurrency for bulk scans. A positive result means the endpoint
     * accepts TCP, not that the proxy protocol works; use [testLatency] for an accurate probe.
     */
    suspend fun testLatencyQuick(server: Server): TestResult

    /**
     * HTTP fetch through the server (or active tunnel) to verify the proxy can reach the internet.
     * [url] defaults to [DEFAULT_SITE_FETCH_URL].
     */
    suspend fun testSiteFetch(
        server: Server,
        url: String = DEFAULT_SITE_FETCH_URL
    ): TestResult
}

interface ServerRepository {
    fun servers(): Flow<List<Server>>
    fun subscriptions(): Flow<List<Subscription>>

    suspend fun getServer(id: String): Server?
    suspend fun toggleFavorite(id: String)
    suspend fun upsert(server: Server)
    /** Update only latency without touching credential / blob columns. */
    suspend fun updatePing(id: String, pingMs: Int)
    suspend fun delete(id: String)
    suspend fun duplicate(id: String): Server?

    /** Import one or many servers from pasted text / URI / base64 subscription blob. */
    suspend fun importFromText(text: String): ImportResult

    /**
     * Fetch + parse servers from a remote URL WITHOUT persisting them. Used by the
     * Free Servers browser so the user can preview and pick which ones to add.
     */
    suspend fun previewFromUrl(url: String): List<Server>

    suspend fun addSubscription(name: String, url: String): ImportResult
    suspend fun refreshSubscription(id: String): ImportResult
    suspend fun deleteSubscription(id: String)

    /** Read servers and subscriptions in one database transaction for portable export. */
    suspend fun backupSnapshot(): BackupSnapshot

    /** Validate and atomically restore the Room-owned portion of a portable backup. */
    suspend fun restoreBackup(
        subscriptions: List<Subscription>,
        manualUris: List<String>,
        subscriptionServers: Map<String, List<String>>
    ): ImportResult

    /** Enable/disable a subscription (disabled ones are skipped by auto/bulk refresh). */
    suspend fun setSubscriptionEnabled(id: String, enabled: Boolean)

    suspend fun renameSubscription(id: String, name: String)
    suspend fun updateSubscriptionUrl(id: String, url: String)

    // --- Batch operations (multi-select) ---
    suspend fun deleteAll(ids: List<String>)
    suspend fun setFavoriteAll(ids: List<String>, favorite: Boolean)

    /** Assign servers to a user-defined group (null clears the assignment). */
    suspend fun setCustomGroup(ids: List<String>, group: String?)

    /** Serialize a server back to a share URI (vless://, vmess://, ...). */
    fun exportUri(server: Server): String

    /** Load full rows and serialize share URIs (export / multi-select share). */
    suspend fun exportUris(ids: List<String>): String
}

interface StatsRepository {
    /** [since] is an epoch-millis lower bound (0 = all time). */
    fun totals(since: Long = 0L): Flow<StatsTotals>
    fun weeklyTraffic(since: Long = 0L): Flow<List<TrafficPoint>>
    fun topServers(since: Long = 0L): Flow<List<TopServer>>
    fun dataUsage(since: Long = 0L): Flow<List<UsageSlice>>

    /**
     * Lifetime traffic (down + up bytes) per server id, folding in the live session for the
     * currently-connected server. Powers the compact traffic labels on the Servers list.
     */
    fun serverUsage(): Flow<Map<String, Long>>
}

interface LogRepository {
    fun logs(): Flow<List<LogEntry>>
    fun append(entry: LogEntry)
    fun clear()

    /** Write the current buffer to a shareable file, or null on failure. */
    suspend fun exportToFile(): File?
}

interface SettingsRepository {
    fun settings(): Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
