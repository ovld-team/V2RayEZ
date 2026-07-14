package com.v2rayez.app.data.mock

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
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.MitmProxyController
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.StatsRepository
import com.v2rayez.app.domain.repository.VpnController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** Mock implementations used only for Compose @Preview and design-time rendering. */
class MockVpnController : VpnController {
    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(MockData.connectionState)
    override val weeklyTraffic: StateFlow<List<TrafficPoint>> = MutableStateFlow(MockData.weeklyTraffic)
    override val liveThroughput: StateFlow<List<ThroughputSample>> = MutableStateFlow(MockData.liveThroughput)
    override val recentActivity: StateFlow<List<ActivityItem>> = MutableStateFlow(MockData.recentActivity)
    override fun connect(server: Server) = Unit
    override fun disconnect() = Unit
    override fun toggle() = Unit
    override suspend fun testLatency(server: Server): TestResult =
        TestResult(server.id, server.pingMs, true)
    override suspend fun testLatencyQuick(server: Server): TestResult =
        TestResult(server.id, server.pingMs, true)
}

class MockMitmProxyController : MitmProxyController {
    override val running: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun stop() = Unit
    override fun clearError() = Unit
}

class MockServerRepository : ServerRepository {
    override fun servers(): Flow<List<Server>> = flowOf(MockData.servers)
    override fun subscriptions(): Flow<List<Subscription>> = flowOf(emptyList())
    override suspend fun getServer(id: String): Server? = MockData.servers.firstOrNull { it.id == id }
    override suspend fun toggleFavorite(id: String) = Unit
    override suspend fun upsert(server: Server) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun duplicate(id: String): Server? = null
    override suspend fun importFromText(text: String): ImportResult = ImportResult(true, 1)
    override suspend fun previewFromUrl(url: String): List<Server> = MockData.servers
    override suspend fun addSubscription(name: String, url: String): ImportResult = ImportResult(true, 0)
    override suspend fun refreshSubscription(id: String): ImportResult = ImportResult(true, 0)
    override suspend fun deleteSubscription(id: String) = Unit
    override suspend fun backupSnapshot(): BackupSnapshot =
        BackupSnapshot(MockData.servers, emptyList())
    override suspend fun restoreBackup(
        subscriptions: List<Subscription>,
        manualUris: List<String>,
        subscriptionServers: Map<String, List<String>>
    ): ImportResult = ImportResult(true, manualUris.size + subscriptionServers.values.sumOf { it.size })
    override suspend fun setSubscriptionEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun renameSubscription(id: String, name: String) = Unit
    override suspend fun updateSubscriptionUrl(id: String, url: String) = Unit
    override suspend fun deleteAll(ids: List<String>) = Unit
    override suspend fun setFavoriteAll(ids: List<String>, favorite: Boolean) = Unit
    override suspend fun setCustomGroup(ids: List<String>, group: String?) = Unit
    override fun exportUri(server: Server): String = server.rawUri
}

class MockStatsRepository : StatsRepository {
    override fun totals(since: Long): Flow<StatsTotals> = flowOf(
        StatsTotals("1.25 GB", "512 MB", "86.7 Mbps", "42 ms")
    )
    override fun weeklyTraffic(since: Long): Flow<List<TrafficPoint>> = flowOf(MockData.weeklyTraffic)
    override fun topServers(since: Long): Flow<List<TopServer>> = flowOf(MockData.topServers)
    override fun dataUsage(since: Long): Flow<List<UsageSlice>> = flowOf(MockData.dataUsage)
    override fun serverUsage(): Flow<Map<String, Long>> = flowOf(emptyMap())
}

class MockLogRepository : LogRepository {
    override fun logs(): Flow<List<LogEntry>> = flowOf(MockData.logs)
    override fun append(entry: LogEntry) = Unit
    override fun clear() = Unit
    override suspend fun exportToFile(): File? = null
}

class MockSettingsRepository : SettingsRepository {
    override fun settings(): Flow<AppSettings> = flowOf(AppSettings())
    override suspend fun current(): AppSettings = AppSettings()
    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
}
