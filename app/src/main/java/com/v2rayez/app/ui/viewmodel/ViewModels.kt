package com.v2rayez.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.v2rayez.app.R
import com.v2rayez.app.data.mock.MockLogRepository
import com.v2rayez.app.data.mock.MockServerRepository
import com.v2rayez.app.data.mock.MockSettingsRepository
import com.v2rayez.app.data.mock.MockStatsRepository
import com.v2rayez.app.data.mock.MockVpnController
import com.v2rayez.app.data.parser.CountryGuesser
import com.v2rayez.app.data.repository.logRouting
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.domain.model.ActivityItem
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.OnboardingWants
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.HostMapping
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.model.RoutingMode
import com.v2rayez.app.domain.model.RoutingRule
import com.v2rayez.app.domain.model.RuleOutbound
import com.v2rayez.app.domain.model.RuleProvider
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.WarpConfig
import com.v2rayez.app.domain.model.WarpMode
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.StatsTotals
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.model.TestResult
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.TopServer
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.model.UsageSlice
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.StatsRepository
import com.v2rayez.app.domain.repository.VpnController
import com.v2rayez.app.ui.SupportedLanguages
import com.v2rayez.app.ui.tor.TorConflictHandler
import com.v2rayez.app.ui.tor.TorConflictUi
import com.v2rayez.app.data.routing.RuleProviderFetcher
import com.v2rayez.app.data.warp.WarpRegistrar
import com.v2rayez.app.domain.model.StatsRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val vpn: VpnController,
    private val stats: StatsRepository,
    private val settings: SettingsRepository,
    private val servers: ServerRepository,
    private val torController: TorController? = null
) : ViewModel() {
    constructor() : this(
        MockVpnController(),
        MockStatsRepository(),
        MockSettingsRepository(),
        MockServerRepository(),
        null
    )

    private val torConflict = TorConflictHandler(settings, vpn, viewModelScope, torController)
    val torConflictDialog: StateFlow<TorConflictUi?> = torConflict.dialog
    fun confirmTorConflict() = torConflict.confirm()
    fun dismissTorConflict() = torConflict.dismiss()

    val connectionState: StateFlow<ConnectionState> = vpn.connectionState
    val liveThroughput: StateFlow<List<ThroughputSample>> = vpn.liveThroughput
    val recentActivity: StateFlow<List<ActivityItem>> = vpn.recentActivity

    /** Real last-7-days history for the disconnected-state Home chart. */
    val dailyTraffic: StateFlow<List<TrafficPoint>> =
        stats.weeklyTraffic(StatsRange.WEEK.since())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val autoConnect: StateFlow<Boolean> =
        settings.settings().map { it.autoConnect }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val domainFront: StateFlow<com.v2rayez.app.domain.model.DomainFrontConfig> =
        settings.settings().map { it.domainFront }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.v2rayez.app.domain.model.DomainFrontConfig()
            )

    /** Toggle connect/disconnect from the Home power button (legacy; prefer [connectAutoOrToggle]). */
    fun toggleConnection() = connectAutoOrToggle()

    /**
     * Home power button: disconnect if active; when Auto Mode is on and disconnected,
     * ping all servers and connect to the fastest; otherwise plain [VpnController.toggle].
     */
    fun connectAutoOrToggle() {
        viewModelScope.launch {
            when (vpn.connectionState.value.status) {
                ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> vpn.disconnect()
                ConnectionStatus.DISCONNECTED -> gateTorThenConnect {
                    if (autoConnect.value) connectFastest() else vpn.toggle()
                }
            }
        }
    }

    private suspend fun gateTorThenConnect(action: suspend () -> Unit) {
        val current = settings.current()
        val tor = current.tor
        when {
            tor.enabled && current.domainFront.enabled ->
                torConflict.runOrPrompt(
                    blocked = true,
                    messageRes = R.string.tor_conflict_domain_front,
                    stopDaemon = true,
                    action = action
                )
            tor.routeAllDevice ->
                torConflict.runOrPrompt(
                    blocked = true,
                    messageRes = R.string.tor_conflict_vpn_connect,
                    stopDaemon = false,
                    action = action
                )
            else -> action()
        }
    }

    private suspend fun connectFastest() {
        val targets = servers.servers().first()
        if (targets.isEmpty()) {
            vpn.toggle()
            return
        }
        // Don't scan the whole library before connecting: quick-score a small candidate set
        // (previously-working servers first, then untested), TCP-only so nothing serializes
        // behind the Xray core. Full-library ranking lives in the Servers screen.
        val candidates = (
            targets.filter { it.pingMs > 0 }.sortedBy { it.pingMs } +
                targets.filter { it.pingMs <= 0 }
            ).take(HOME_CONNECT_CANDIDATES)
        val gate = Semaphore(HOME_PING_CONCURRENCY)
        val measured = kotlinx.coroutines.coroutineScope {
            candidates.map { server ->
                async {
                    gate.withPermit {
                        val ping = runCatching { vpn.testLatencyQuick(server).pingMs }.getOrDefault(-1)
                        runCatching { servers.upsert(server.copy(pingMs = ping)) }
                        server to ping
                    }
                }
            }.awaitAll()
        }
        val fastest = measured.filter { it.second > 0 }.minByOrNull { it.second }
        if (fastest != null) vpn.connect(fastest.first) else vpn.toggle()
    }

    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch {
        settings.update { it.copy(autoConnect = enabled) }
    }

    private companion object {
        /** TCP-quick probes carry no Xray mutex, so parallel scoring is safe. */
        const val HOME_PING_CONCURRENCY = 8
        /** Auto-connect scores at most this many candidates before picking one. */
        const val HOME_CONNECT_CANDIDATES = 12
    }
}

// ---------------------------------------------------------------------------
// Notifications (in-app inbox backed by the live activity feed)
// ---------------------------------------------------------------------------
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val vpn: VpnController
) : ViewModel() {
    constructor() : this(MockVpnController())

    val items: StateFlow<List<ActivityItem>> = vpn.recentActivity
}

// ---------------------------------------------------------------------------
// Servers
// ---------------------------------------------------------------------------
enum class ServerSortMode { DEFAULT, PING, NAME, PROTOCOL }

enum class ServerSectionType { FAVORITES, SUBSCRIPTION, CUSTOM_GROUP, MANUAL }

/** One collapsible section of the Servers list. */
data class ServerSection(
    /** Stable key: "favorites", "manual", "sub:<id>", "group:<name>". */
    val id: String,
    val type: ServerSectionType,
    /** Subscription / custom-group display name (FAVORITES and MANUAL are localized in UI). */
    val title: String = "",
    val subscription: Subscription? = null,
    val servers: List<Server> = emptyList(),
    /** Total servers in the section before query/protocol filtering. */
    val totalCount: Int = 0
) {
    val bestPingMs: Int get() = servers.filter { it.pingMs > 0 }.minOfOrNull { it.pingMs } ?: -1
    val avgPingMs: Int
        get() = servers.filter { it.pingMs > 0 }.let { if (it.isEmpty()) -1 else it.sumOf { s -> s.pingMs } / it.size }
}

data class ServersUiState(
    val servers: List<Server> = emptyList(),
    val sections: List<ServerSection> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** Distinct user-defined group names (for the move-to-group sheet). */
    val customGroups: List<String> = emptyList(),
    val query: String = "",
    val connectedId: String? = null,
    val defaultServerId: String? = null,
    val testing: Set<String> = emptySet(),
    val sortMode: ServerSortMode = ServerSortMode.DEFAULT,
    val selected: Set<String> = emptySet(),
    /** Lifetime traffic (down + up bytes) per server id — drives Servers-list traffic labels. */
    val usageByServer: Map<String, Long> = emptyMap(),
    /** Last ping failure reason for snackbar / inline hint. */
    val lastPingMessage: String? = null
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()

    /** All servers currently visible across sections (deduped: favorites mirrors other sections). */
    val visible: List<Server> get() = sections.flatMap { it.servers }.distinctBy { it.id }
}

private fun sortServers(list: List<Server>, mode: ServerSortMode): List<Server> = when (mode) {
    ServerSortMode.DEFAULT -> list
    ServerSortMode.PING -> list.sortedBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
    ServerSortMode.NAME -> list.sortedBy { it.name.lowercase() }
    ServerSortMode.PROTOCOL -> list.sortedWith(compareBy({ it.protocol.ordinal }, { it.name.lowercase() }))
}

private fun buildSections(
    servers: List<Server>,
    subs: List<Subscription>,
    query: String,
    sortMode: ServerSortMode
): List<ServerSection> {
    val matches: (Server) -> Boolean = { s ->
        query.isBlank() || s.name.contains(query, true) || s.country.contains(query, true)
    }
    val filtering = query.isNotBlank()
    val sections = mutableListOf<ServerSection>()

    val favorites = servers.filter { it.isFavorite }
    if (favorites.isNotEmpty()) {
        val visible = sortServers(favorites.filter(matches), sortMode)
        if (visible.isNotEmpty() || !filtering) {
            sections += ServerSection(
                id = "favorites", type = ServerSectionType.FAVORITES,
                servers = visible, totalCount = favorites.size
            )
        }
    }

    // A custom-group assignment takes precedence over subscription/manual placement.
    val grouped = servers.filter { !it.customGroup.isNullOrBlank() }
    grouped.groupBy { it.customGroup!!.trim() }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, list) ->
        val visible = sortServers(list.filter(matches), sortMode)
        if (visible.isNotEmpty() || !filtering) {
            sections += ServerSection(
                id = "group:$name", type = ServerSectionType.CUSTOM_GROUP,
                title = name, servers = visible, totalCount = list.size
            )
        }
    }

    val ungrouped = servers.filter { it.customGroup.isNullOrBlank() }
    subs.sortedBy { it.name.lowercase() }.forEach { sub ->
        val list = ungrouped.filter { it.subscriptionId == sub.id }
        val visible = sortServers(list.filter(matches), sortMode)
        if (visible.isNotEmpty() || !filtering) {
            sections += ServerSection(
                id = "sub:${sub.id}", type = ServerSectionType.SUBSCRIPTION,
                title = sub.name, subscription = sub, servers = visible, totalCount = list.size
            )
        }
    }

    val subIds = subs.map { it.id }.toSet()
    val manual = ungrouped.filter { it.subscriptionId == null || it.subscriptionId !in subIds }
    if (manual.isNotEmpty()) {
        val visible = sortServers(manual.filter(matches), sortMode)
        if (visible.isNotEmpty() || !filtering) {
            sections += ServerSection(
                id = "manual", type = ServerSectionType.MANUAL,
                servers = visible, totalCount = manual.size
            )
        }
    }
    return sections
}

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val repo: ServerRepository,
    private val vpn: VpnController,
    private val settings: SettingsRepository,
    private val stats: StatsRepository,
    private val torController: TorController? = null
) : ViewModel() {
    constructor() : this(
        MockServerRepository(),
        MockVpnController(),
        MockSettingsRepository(),
        MockStatsRepository(),
        null
    )

    private val torConflict = TorConflictHandler(settings, vpn, viewModelScope, torController)
    val torConflictDialog: StateFlow<TorConflictUi?> = torConflict.dialog
    fun confirmTorConflict() = torConflict.confirm()
    fun dismissTorConflict() = torConflict.dismiss()

    private val query = MutableStateFlow("")
    private val testing = MutableStateFlow<Set<String>>(emptySet())
    private val sortMode = MutableStateFlow(ServerSortMode.DEFAULT)
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** ids of subscriptions currently being refreshed individually. */
    private val _refreshingSubs = MutableStateFlow<Set<String>>(emptySet())
    val refreshingSubs: StateFlow<Set<String>> = _refreshingSubs

    private val _lastPingMessage = MutableStateFlow<String?>(null)
    val lastPingMessage: StateFlow<String?> = _lastPingMessage

    /** Surfaces pull-to-refresh / auto-refresh failures the old silent `runCatching` swallowed. */
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError

    private var pingJob: kotlinx.coroutines.Job? = null

    val subscriptions: StateFlow<List<Subscription>> =
        repo.subscriptions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Auto-refresh stale, enabled subscriptions when the app opens. Free servers are
        // no longer auto-seeded — the user browses and picks them from the Free Servers page.
        viewModelScope.launch {
            val subs = repo.subscriptions().first()
            val now = System.currentTimeMillis()
            subs.filter { it.enabled && now - it.lastUpdated > STALE_THRESHOLD_MS }
                .forEach { runCatching { repo.refreshSubscription(it.id) } }
        }
    }

    val state: StateFlow<ServersUiState> =
        combine(
            combine(repo.servers(), repo.subscriptions()) { servers, subs -> servers to subs },
            combine(query, sortMode) { q, sort -> q to sort },
            combine(vpn.connectionState, testing, selected) { conn, testSet, sel -> Triple(conn, testSet, sel) },
            settings.settings().map { it.defaultServerId },
            stats.serverUsage()
        ) { serversAndSubs, filters, connTestSel, defaultId, usage ->
            val (servers, subs) = serversAndSubs
            val (q, sort) = filters
            val (conn, testSet, sel) = connTestSel
            ServersUiState(
                servers = servers,
                sections = buildSections(servers, subs, q, sort),
                subscriptions = subs,
                customGroups = servers.mapNotNull { it.customGroup?.trim()?.takeIf { g -> g.isNotBlank() } }
                    .distinct().sortedBy { it.lowercase() },
                query = q,
                connectedId = conn.server?.id?.takeIf { conn.status == ConnectionStatus.CONNECTED },
                defaultServerId = defaultId,
                testing = testSet,
                sortMode = sort,
                usageByServer = usage,
                // Prune selections for servers that no longer exist.
                selected = sel.filterTo(mutableSetOf()) { id -> servers.any { it.id == id } }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServersUiState())

    fun setQuery(value: String) = query.update { value }
    fun setSortMode(mode: ServerSortMode) = sortMode.update { mode }

    fun connect(server: Server) {
        viewModelScope.launch {
            val current = settings.current()
            val tor = current.tor
            when {
                tor.enabled && current.domainFront.enabled ->
                    torConflict.runOrPrompt(
                        blocked = true,
                        messageRes = R.string.tor_conflict_domain_front,
                        stopDaemon = true
                    ) { vpn.connect(server) }
                tor.routeAllDevice ->
                    torConflict.runOrPrompt(
                        blocked = true,
                        messageRes = R.string.tor_conflict_vpn_connect,
                        stopDaemon = false
                    ) { vpn.connect(server) }
                else -> vpn.connect(server)
            }
        }
    }

    /** Pin (or clear) the default server used by the Home power button. */
    fun setDefaultServer(serverId: String?) = viewModelScope.launch {
        settings.update { it.copy(defaultServerId = serverId) }
    }

    /** Pull-to-refresh: re-fetch every enabled subscription; surfaces a summary on failure. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.update { true }
            val targets = subscriptions.value.filter { it.enabled }
            var failed = 0
            targets.forEach { sub ->
                val ok = runCatching { repo.refreshSubscription(sub.id) }.getOrNull()?.success == true
                if (!ok) failed++
            }
            _refreshing.update { false }
            _refreshError.value = when {
                targets.isEmpty() || failed == 0 -> null
                failed == targets.size -> "Refresh failed for all subscriptions"
                else -> "Refresh failed for $failed of ${targets.size} subscription(s)"
            }
        }
    }

    fun clearRefreshError() {
        _refreshError.value = null
    }

    fun toggleFavorite(id: String) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun duplicate(id: String) = viewModelScope.launch { repo.duplicate(id) }
    fun exportUri(server: Server): String = repo.exportUri(server)

    /** Assign a single server to a custom group (null/blank clears it). */
    fun setCustomGroup(serverId: String, group: String?) =
        viewModelScope.launch { repo.setCustomGroup(listOf(serverId), group) }

    fun testLatency(server: Server) {
        viewModelScope.launch {
            testing.update { it + server.id }
            try {
                val result = runCatching { vpn.testLatency(server) }.getOrNull()
                // Persist the REAL result: keep -1 for blocked/timed-out so the UI can show "—".
                repo.upsert(server.copy(pingMs = result?.pingMs ?: -1))
                if (result != null && !result.success && result.message.isNotBlank()) {
                    _lastPingMessage.value = result.message
                }
            } finally {
                testing.update { it - server.id }
            }
        }
    }

    fun clearPingMessage() {
        _lastPingMessage.value = null
    }

    // --- Multi-select ---
    fun toggleSelect(id: String) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() = selected.update { emptySet() }
    fun selectAllVisible() = selected.update { state.value.visible.map { s -> s.id }.toSet() }

    fun deleteSelected() = viewModelScope.launch {
        repo.deleteAll(selected.value.toList())
        clearSelection()
    }

    fun favoriteSelected(favorite: Boolean = true) = viewModelScope.launch {
        repo.setFavoriteAll(selected.value.toList(), favorite)
        clearSelection()
    }

    fun moveSelectedToGroup(group: String?) = viewModelScope.launch {
        repo.setCustomGroup(selected.value.toList(), group)
        clearSelection()
    }

    /** Share URIs of the selected servers, newline-separated. */
    fun exportSelected(): String {
        val ids = selected.value
        return state.value.servers.filter { it.id in ids }
            .joinToString("\n") { repo.exportUri(it) }
            .trim()
    }

    // --- Latency ---
    // Bulk path uses the TCP-quick probe: high concurrency, no Xray core mutex contention,
    // so a scan never queues behind (or blocks) a live connect. Single-row testLatency()
    // above stays the accurate Xray-handshake measurement.
    private suspend fun pingServers(targets: List<Server>): List<Pair<Server, Int>> =
        kotlinx.coroutines.coroutineScope {
            testing.update { it + targets.map { s -> s.id } }
            try {
                val gate = Semaphore(PING_CONCURRENCY)
                targets.map { server ->
                    async {
                        gate.withPermit {
                            val ping = runCatching { vpn.testLatencyQuick(server).pingMs }.getOrDefault(-1)
                            repo.upsert(server.copy(pingMs = ping))
                            testing.update { it - server.id }
                            server to ping
                        }
                    }
                }.awaitAll()
            } finally {
                testing.update { emptySet() }
            }
        }

    /** Ping every visible server; one bulk job at a time, cores queued process-wide. */
    fun pingAll() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            val targets = state.value.visible
            if (targets.isNotEmpty()) pingServers(targets)
        }
    }

    /** Ping only the servers of one section (group header action). */
    fun pingSection(sectionId: String) {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            val targets = state.value.sections.firstOrNull { it.id == sectionId }?.servers.orEmpty()
            if (targets.isNotEmpty()) pingServers(targets)
        }
    }

    /** Cancel an in-flight bulk ping. */
    fun cancelPing() {
        pingJob?.cancel()
        pingJob = null
        testing.update { emptySet() }
    }

    /** Ping all visible servers, then connect to the one with the lowest latency. */
    fun connectFastest(onResult: (String) -> Unit = {}) {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            val targets = state.value.visible
            if (targets.isEmpty()) { onResult("No servers to test"); return@launch }
            val measured = pingServers(targets)
            val fastest = measured.filter { it.second > 0 }.minByOrNull { it.second }
            if (fastest != null) {
                connect(fastest.first)
                onResult("Connecting to ${fastest.first.name} (${fastest.second} ms)")
            } else {
                onResult("No reachable server")
            }
        }
    }

    fun import(text: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val r = repo.importFromText(text)
            onResult(if (r.success) "Imported ${r.importedCount} server(s)" else r.message.ifBlank { "Import failed" })
        }
    }

    // --- Subscription management (inline on the Servers screen) ---
    fun addSubscription(name: String, url: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val r = repo.addSubscription(name, url)
            onResult(if (r.success) "Added ${r.importedCount} server(s)" else r.message.ifBlank { "Failed" })
        }
    }

    fun refreshSubscription(id: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _refreshingSubs.update { it + id }
            val r = runCatching { repo.refreshSubscription(id) }.getOrNull()
            _refreshingSubs.update { it - id }
            onResult(
                when {
                    r == null -> "Refresh failed"
                    r.success -> "Updated ${r.importedCount} server(s)"
                    else -> r.message.ifBlank { "Refresh failed" }
                }
            )
        }
    }

    fun renameSubscription(id: String, name: String) =
        viewModelScope.launch { repo.renameSubscription(id, name) }

    /** Update a subscription's URL, then immediately re-fetch it (old nodes were previously stale until the next manual/auto refresh). */
    fun updateSubscriptionUrl(id: String, url: String, onResult: (String) -> Unit = {}) {
        if (url.isBlank()) return
        viewModelScope.launch {
            repo.updateSubscriptionUrl(id, url)
            _refreshingSubs.update { it + id }
            val r = runCatching { repo.refreshSubscription(id) }.getOrNull()
            _refreshingSubs.update { it - id }
            onResult(
                when {
                    r == null -> "URL updated, but refresh failed"
                    r.success -> "URL updated — ${r.importedCount} server(s)"
                    else -> "URL updated, but refresh failed: ${r.message.ifBlank { "unknown error" }}"
                }
            )
        }
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { repo.setSubscriptionEnabled(id, enabled) }

    /** Delete the subscription together with all of its servers. */
    fun deleteSubscription(id: String) =
        viewModelScope.launch { repo.deleteSubscription(id) }

    /** Rename a custom group across all of its servers. */
    fun renameCustomGroup(oldName: String, newName: String) {
        viewModelScope.launch {
            if (newName.isBlank()) return@launch
            val ids = state.value.servers.filter { it.customGroup?.trim() == oldName }.map { it.id }
            repo.setCustomGroup(ids, newName)
        }
    }

    /** Dissolve a custom group: its servers return to their manual/subscription sections. */
    fun dissolveCustomGroup(name: String) {
        viewModelScope.launch {
            val ids = state.value.servers.filter { it.customGroup?.trim() == name }.map { it.id }
            repo.setCustomGroup(ids, null)
        }
    }

    private companion object {
        const val STALE_THRESHOLD_MS = 12 * 60 * 60 * 1000L
        /** Bulk pings are TCP-quick (no Xray mutex), so real parallelism is safe. */
        const val PING_CONCURRENCY = 16
    }
}

// ---------------------------------------------------------------------------
// Free servers (browse a public open-source list, test, and pick which to add)
// ---------------------------------------------------------------------------
data class FreeServersUiState(
    val servers: List<Server> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Fetched-server ids already present in the user's list (host:port+protocol identity). */
    val added: Set<String> = emptySet(),
    /** Subset of [added] whose saved counterpart came from a subscription. */
    val addedFromSubscription: Set<String> = emptySet(),
    /** id -> measured ping (ms); -1 = tested and unreachable. Untested ids are absent. */
    val pings: Map<String, Int> = emptyMap(),
    val testing: Set<String> = emptySet(),
    /** Progress and failures for the active bulk probe; null otherwise. */
    val testProgress: FreeProbeProgress? = null,
    /** Last actionable load/probe failure. Cleared when a new operation starts. */
    val probeError: String? = null,
    val sortByPing: Boolean = false,
    val workingOnly: Boolean = false
) {
    val testedCount: Int get() = pings.size
    val workingCount: Int get() = pings.count { it.value > 0 }
}

enum class FreeProbeMode { QUICK, ALL, BEST_TEN }

data class FreeProbeProgress(
    val mode: FreeProbeMode,
    val completed: Int,
    val total: Int,
    val failed: Int = 0
)

internal fun FreeProbeProgress.afterBatch(successes: Iterable<Boolean>): FreeProbeProgress {
    val batch = successes.toList()
    return copy(
        completed = (completed + batch.size).coerceAtMost(total),
        failed = failed + batch.count { !it }
    )
}

@HiltViewModel
class FreeServersViewModel @Inject constructor(
    private val repo: ServerRepository,
    private val vpn: VpnController
) : ViewModel() {
    constructor() : this(MockServerRepository(), MockVpnController())

    private val fetched = MutableStateFlow<List<Server>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val testing = MutableStateFlow<Set<String>>(emptySet())
    private val testProgress = MutableStateFlow<FreeProbeProgress?>(null)
    private val probeError = MutableStateFlow<String?>(null)
    private val sortByPing = MutableStateFlow(false)
    private val workingOnly = MutableStateFlow(false)

    private var bulkTestJob: kotlinx.coroutines.Job? = null
    private val singleTestJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Stable identity across refreshes: the preview ids are random per fetch. */
    private fun key(s: Server) = "${s.host}:${s.port}|${s.protocol.name}"

    val state: StateFlow<FreeServersUiState> =
        combine(
            combine(fetched, repo.servers()) { list, saved ->
                val savedKeys = saved.map(::key).toSet()
                val subKeys = saved.filter { it.subscriptionId != null }.map(::key).toSet()
                Triple(
                    list,
                    list.filter { key(it) in savedKeys }.map { it.id }.toSet(),
                    list.filter { key(it) in subKeys }.map { it.id }.toSet()
                )
            },
            combine(loading, error, testProgress, probeError) { l, e, p, probeErr ->
                LoadAndProbeState(l, e, p, probeErr)
            },
            combine(pings, testing, sortByPing, workingOnly) { pg, t, sort, working ->
                FreeFilters(pg, t, sort, working)
            }
        ) { (list, added, fromSub), loadState, filters ->
            var display = list
            if (filters.workingOnly) display = display.filter { (filters.pings[it.id] ?: 0) > 0 }
            if (filters.sortByPing) {
                display = display.sortedBy { filters.pings[it.id]?.takeIf { p -> p > 0 } ?: Int.MAX_VALUE }
            }
            FreeServersUiState(
                servers = display,
                loading = loadState.loading,
                error = loadState.error,
                added = added,
                addedFromSubscription = fromSub,
                pings = filters.pings,
                testing = filters.testing,
                testProgress = loadState.progress,
                probeError = loadState.probeError,
                sortByPing = filters.sortByPing,
                workingOnly = filters.workingOnly
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FreeServersUiState())

    private data class FreeFilters(
        val pings: Map<String, Int>,
        val testing: Set<String>,
        val sortByPing: Boolean,
        val workingOnly: Boolean
    )

    private data class LoadAndProbeState(
        val loading: Boolean,
        val error: String?,
        val progress: FreeProbeProgress?,
        val probeError: String?
    )

    init { load() }

    fun load() {
        viewModelScope.launch {
            cancelTests()
            loading.update { true }
            error.update { null }
            probeError.update { null }
            val list = runCatching { repo.previewFromUrl(FREE_SUB_URL) }.getOrDefault(emptyList())
                .let(::withStableDisplayNames)
            // Carry test results over for servers that survived the refresh (stable key).
            val oldPings = fetched.value.associate { key(it) to pings.value[it.id] }
            fetched.update { list }
            pings.update { list.mapNotNull { s -> oldPings[key(s)]?.let { p -> s.id to p } }.toMap() }
            if (list.isEmpty()) error.update { "Couldn't load free servers. Pull to retry." }
            loading.update { false }
        }
    }

    fun toggleSortByPing() = sortByPing.update { !it }
    fun toggleWorkingOnly() = workingOnly.update { !it }

    /**
     * Test a free-list row with the same bounded TCP probe used by bulk scans.
     * A throwaway Xray handshake can block for ten seconds per tap and is serialized with
     * core startup, which made this ping affordance appear to do nothing.
     */
    fun test(server: Server) {
        singleTestJobs.remove(server.id)?.cancel()
        val job = viewModelScope.launch {
            val myJob = coroutineContext[kotlinx.coroutines.Job]
            testing.update { it + server.id }
            try {
                val ping = try {
                    val result = vpn.testLatency(server)
                    if (result.success) result.pingMs else {
                        probeError.update { result.message.ifBlank { "Proxy handshake failed for ${server.name}" } }
                        -1
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    -1
                }
                if (isActive) pings.update { it + (server.id to ping) }
            } finally {
                if (singleTestJobs[server.id] === myJob) {
                    testing.update { it - server.id }
                    singleTestJobs.remove(server.id)
                }
            }
        }
        singleTestJobs[server.id] = job
    }

    /**
     * Quick-scan a random sample of the fetched list. TCP-only reachability at high
     * concurrency — a 6800-entry aggregator list can't be Xray-tested one by one, and
     * a sample is enough to surface working candidates for "Add fastest".
     */
    fun quickScanSample(sample: Int = SCAN_SAMPLE) {
        startBulkScan(
            targets = pickScanSample(fetched.value, sample, pings.value.keys),
            mode = FreeProbeMode.QUICK
        )
    }

    /** Quick-scan every fetched server; cancellable and re-entrant. */
    fun testAll() {
        startBulkScan(fetched.value, FreeProbeMode.ALL)
    }

    private fun startBulkScan(
        targets: List<Server>,
        mode: FreeProbeMode
    ): kotlinx.coroutines.Job {
        bulkTestJob?.cancel()
        bulkTestJob = null
        singleTestJobs.values.forEach { it.cancel() }
        singleTestJobs.clear()
        testing.update { emptySet() }
        testProgress.update { null }
        probeError.update { null }

        val job = viewModelScope.launch {
            val myJob = coroutineContext[kotlinx.coroutines.Job]
            if (targets.isEmpty()) return@launch
            var failed = 0
            testProgress.update { FreeProbeProgress(mode, 0, targets.size) }
            testing.update { it + targets.map { s -> s.id } }
            try {
                // Keep only one bounded batch alive. Creating one coroutine per item in a
                // 6k+ source list caused memory pressure and made "Test all" appear stalled.
                targets.chunked(TEST_CONCURRENCY).forEach { batch ->
                    val results = batch.map { server ->
                        async {
                            val result = try {
                                vpn.testLatencyQuick(server)
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                TestResult(server.id, -1, false, e.message ?: "TCP probe failed")
                            }
                            server to result
                        }
                    }.awaitAll()
                    if (bulkTestJob !== myJob) return@launch
                    results.forEach { (server, result) ->
                        pings.update { it + (server.id to (result.pingMs.takeIf { result.success } ?: -1)) }
                        testing.update { it - server.id }
                    }
                    val progress = testProgress.value
                        ?.afterBatch(results.map { it.second.success })
                        ?: FreeProbeProgress(mode, results.size, targets.size)
                    failed = progress.failed
                    testProgress.update { progress }
                }
                if (failed > 0) {
                    probeError.update { "$failed of ${targets.size} servers did not accept a TCP connection." }
                }
            } finally {
                if (bulkTestJob === myJob) {
                    testing.update { emptySet() }
                    testProgress.update { null }
                }
            }
        }
        bulkTestJob = job
        return job
    }

    /** Stop both Quick scan / Test all bulk jobs and any in-flight single-row accurate tests. */
    fun cancelTests() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        singleTestJobs.values.forEach { it.cancel() }
        singleTestJobs.clear()
        testing.update { emptySet() }
        testProgress.update { null }
    }

    fun add(server: Server, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            if (server.id in state.value.added) {
                val suffix = if (server.id in state.value.addedFromSubscription) " (from a subscription)" else ""
                onResult("${server.name} is already in your list$suffix")
                return@launch
            }
            repo.upsert(
                server.copy(
                    group = ServerGroup.MANUAL,
                    subscriptionId = null,
                    pingMs = state.value.pings[server.id] ?: server.pingMs
                )
            )
            onResult("Added ${server.name}")
        }
    }

    fun addAll(onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            val added = state.value.added
            val list = fetched.value.filter { it.id !in added }
            list.forEach {
                repo.upsert(it.copy(group = ServerGroup.MANUAL, subscriptionId = null, pingMs = state.value.pings[it.id] ?: 0))
            }
            onResult("Added ${list.size} server(s)")
        }
    }

    /** Add the [n] fastest tested-working servers that aren't already in the list. */
    fun addFastest(n: Int = 10, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            if (rankByLatency(fetched.value, pings.value, n).size < n) {
                startBulkScan(
                    pickScanSample(fetched.value, SCAN_SAMPLE, pings.value.keys),
                    FreeProbeMode.BEST_TEN
                ).join()
            }
            val st = state.value
            val candidates = rankByLatency(
                fetched.value.filter { it.id !in st.added },
                pings.value,
                n
            )
            if (candidates.isEmpty()) {
                onResult("No tested working servers yet — run a test first")
                return@launch
            }
            candidates.forEach {
                repo.upsert(it.copy(group = ServerGroup.MANUAL, subscriptionId = null, pingMs = st.pings[it.id] ?: 0))
            }
            onResult("Added ${candidates.size} fastest server(s)")
        }
    }

    companion object {
        /** Built-in open-source free server aggregator. */
        const val FREE_SUB_URL = "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"

        /** Quick scans are TCP-only (no Xray mutex), so wide fan-out is safe. */
        private const val TEST_CONCURRENCY = 24

        /** Default sample size for a quick scan of a huge aggregator list. */
        const val SCAN_SAMPLE = 200

        /**
         * Untested rows first (shuffled), topped up with already-tested ones — so repeated
         * scans keep discovering new servers instead of re-hitting the same sample.
         */
        internal fun pickScanSample(
            servers: List<Server>,
            sample: Int,
            testedIds: Set<String> = emptySet()
        ): List<Server> {
            if (servers.size <= sample) return servers
            val (tested, untested) = servers.partition { it.id in testedIds }
            return (untested.shuffled() + tested.shuffled()).take(sample)
        }

        /**
         * Display names as `PROTOCOL · host:port`, appending ` #2` / `#3` … for duplicate
         * host:port+protocol keys so list rows stay unique and scannable.
         */
        internal fun withStableDisplayNames(servers: List<Server>): List<Server> {
            val counts = LinkedHashMap<String, Int>()
            return servers.map { s ->
                val baseKey = "${s.host}:${s.port}|${s.protocol.name}"
                val n = (counts[baseKey] ?: 0) + 1
                counts[baseKey] = n
                val baseName = "${s.protocol.label} · ${s.host}:${s.port}"
                val name = if (n == 1) baseName else "$baseName #$n"
                s.copy(name = name, address = "${s.host}:${s.port}")
            }
        }

        /** Reachable servers ordered by measured latency; untested/dead rows never qualify. */
        internal fun rankByLatency(
            servers: List<Server>,
            measuredPings: Map<String, Int>,
            limit: Int = 10
        ): List<Server> = servers
            .filter { (measuredPings[it.id] ?: -1) > 0 }
            .sortedWith(compareBy<Server> { measuredPings.getValue(it.id) }.thenBy { it.id })
            .take(limit.coerceAtLeast(0))
    }
}

// ---------------------------------------------------------------------------
// Server editor (add / edit a manual server)
// ---------------------------------------------------------------------------
@HiltViewModel
class ServerEditorViewModel @Inject constructor(
    private val repo: ServerRepository
) : ViewModel() {
    constructor() : this(MockServerRepository())

    /** All saved servers, used to offer a chain/front-proxy selection. */
    val servers: StateFlow<List<Server>> =
        repo.servers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Load an existing server for editing, or null for a new one. */
    suspend fun load(id: String): Server? = repo.getServer(id)

    /** Build (or update) a manual server from raw form fields and persist it. */
    fun save(
        existing: Server?,
        name: String,
        protocol: Protocol,
        host: String,
        port: Int,
        secret: String,
        network: String = "tcp",
        streamSecurity: String = "",
        sni: String = "",
        alpn: String = "",
        path: String = "",
        requestHost: String = "",
        flow: String = "",
        alterId: Int = 0,
        method: String = "",
        fingerprint: String = "",
        allowInsecure: Boolean = false,
        publicKey: String = "",
        shortId: String = "",
        ssPlugin: String = "",
        ssPluginOptions: String = "",
        sshUser: String = "",
        sshPrivateKey: String = "",
        sshHostKey: String = "",
        wgPrivateKey: String = "",
        wgPeerPublicKey: String = "",
        wgPreSharedKey: String = "",
        wgLocalAddresses: List<String> = emptyList(),
        wgAllowedIps: List<String> = emptyList(),
        wgReserved: List<Int> = emptyList(),
        wgMtu: Int = 0,
        dnsTunnelDomain: String = "",
        dnsTunnelPubKey: String = "",
        dnsTunnelResolver: String = "",
        dnsTunnelMode: String = "doh",
        psiphonConfig: String = "",
        frontProxyId: String? = null,
        customGroup: String? = null,
        preferredCore: com.v2rayez.app.domain.model.CorePreference =
            com.v2rayez.app.domain.model.CorePreference.SYSTEM
    ) {
        viewModelScope.launch {
            val (code, country) = CountryGuesser.guess(name.ifBlank { host })
            val usesPassword = protocol == Protocol.TROJAN ||
                protocol == Protocol.SHADOWSOCKS || protocol == Protocol.SSH
            val net = network.ifBlank { "tcp" }.lowercase()
            val security = streamSecurity.lowercase()
            // Editing a subscription-owned server keeps it attached and flags it so a future
            // refresh won't overwrite the user's edits. New/manual servers detach to MANUAL.
            val isSubscriptionServer = existing?.subscriptionId != null
            val base = existing ?: Server(
                id = java.util.UUID.randomUUID().toString(),
                name = "",
                country = "",
                countryCode = "",
                protocol = protocol,
                transport = "TCP",
                security = "",
                sni = "",
                address = "",
                pingMs = 0,
                signal = 0,
                group = ServerGroup.MANUAL
            )
            val server = base.copy(
                name = name.ifBlank { host },
                country = country,
                countryCode = code,
                protocol = protocol,
                transport = transportLabel(net),
                security = securityLabel(security),
                host = host,
                port = port,
                address = "$host:$port",
                uuid = if (usesPassword) base.uuid else secret,
                password = if (usesPassword) secret else base.password,
                method = method.ifBlank { base.method },
                ssPlugin = ssPlugin,
                ssPluginOptions = ssPluginOptions,
                alterId = alterId,
                flow = flow,
                network = net,
                path = path,
                requestHost = requestHost,
                streamSecurity = security,
                sni = sni,
                alpn = alpn,
                fingerprint = fingerprint,
                allowInsecure = allowInsecure,
                publicKey = publicKey,
                shortId = shortId,
                sshUser = sshUser,
                sshPrivateKey = sshPrivateKey,
                sshHostKey = sshHostKey,
                wgPrivateKey = wgPrivateKey,
                wgPeerPublicKey = wgPeerPublicKey,
                wgPreSharedKey = wgPreSharedKey,
                wgLocalAddresses = wgLocalAddresses,
                wgAllowedIps = wgAllowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") },
                wgReserved = wgReserved,
                wgMtu = wgMtu,
                dnsTunnelDomain = dnsTunnelDomain,
                dnsTunnelPubKey = dnsTunnelPubKey,
                dnsTunnelResolver = dnsTunnelResolver,
                dnsTunnelMode = dnsTunnelMode,
                psiphonConfig = psiphonConfig,
                frontProxyId = frontProxyId,
                customGroup = customGroup,
                preferredCore = preferredCore,
                group = if (isSubscriptionServer) ServerGroup.SUBSCRIPTION else ServerGroup.MANUAL,
                subscriptionId = if (isSubscriptionServer) existing?.subscriptionId else null,
                userModified = isSubscriptionServer,
                // Advanced manual edits invalidate any original share URI so ConfigBuilder uses the fields.
                rawUri = ""
            )
            repo.upsert(server)
        }
    }

    private fun transportLabel(network: String): String = when (network) {
        "ws" -> "WS"
        "grpc" -> "gRPC"
        "h2", "http" -> "HTTP/2"
        "quic" -> "QUIC"
        "httpupgrade" -> "HTTPUpgrade"
        else -> "TCP"
    }

    private fun securityLabel(security: String): String = when (security) {
        "tls" -> "TLS"
        "reality" -> "Reality"
        "xtls" -> "XTLS"
        else -> "None"
    }
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------
data class StatisticsUiState(
    val range: StatsRange = StatsRange.WEEK,
    val totals: StatsTotals = StatsTotals("0 B", "0 B", "0 Mbps", "0 ms"),
    val weeklyTraffic: List<TrafficPoint> = emptyList(),
    val topServers: List<TopServer> = emptyList(),
    val dataUsage: List<UsageSlice> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val stats: StatsRepository
) : ViewModel() {
    constructor() : this(MockStatsRepository())

    private val range = MutableStateFlow(StatsRange.WEEK)

    fun setRange(value: StatsRange) = range.update { value }

    val state: StateFlow<StatisticsUiState> =
        range.flatMapLatest { r ->
            val since = r.since()
            combine(
                stats.totals(since),
                stats.weeklyTraffic(since),
                stats.topServers(since),
                stats.dataUsage(since)
            ) { totals, weekly, top, usage ->
                StatisticsUiState(r, totals, weekly, top, usage)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatisticsUiState())
}

// ---------------------------------------------------------------------------
// Backup & restore (config portability)
// ---------------------------------------------------------------------------
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository
) : ViewModel() {
    constructor() : this(MockSettingsRepository(), MockServerRepository())

    private val json = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() = _message.update { null }

    /** Serialize settings, subscription metadata, and server share URIs into portable JSON. */
    suspend fun exportJson(): String {
        val settings = settingsRepo.current()
        val snapshot = serverRepo.backupSnapshot()
        val allServers = snapshot.servers
        val manualUris = allServers
            .filter { it.subscriptionId == null }
            .mapNotNull { runCatching { serverRepo.exportUri(it) }.getOrNull() }
            .filter { it.isNotBlank() }
        val subscriptionServers = allServers
            .filter { it.subscriptionId != null }
            .groupBy { requireNotNull(it.subscriptionId) }
            .mapValues { (_, servers) ->
                servers.mapNotNull { runCatching { serverRepo.exportUri(it) }.getOrNull() }
                    .filter { it.isNotBlank() }
            }
        return json.encodeToString(com.v2rayez.app.domain.model.BackupData.serializer(),
            com.v2rayez.app.domain.model.BackupData(
                settings = settings,
                servers = manualUris,
                subscriptions = snapshot.subscriptions,
                subscriptionServers = subscriptionServers
            ))
    }

    /** Restore settings, subscription metadata, and servers from a portable backup. */
    suspend fun importJson(text: String): Boolean = runCatching {
        val backup = json.decodeFromString(com.v2rayez.app.domain.model.BackupData.serializer(), text)
        val restoreResult = serverRepo.restoreBackup(
            subscriptions = backup.subscriptions,
            manualUris = backup.servers,
            subscriptionServers = backup.subscriptionServers
        )
        check(restoreResult.success) { restoreResult.message }
        settingsRepo.update { SupportedLanguages.normalizeSettings(backup.settings) }
        _message.update { "Backup restored" }
        true
    }.getOrElse {
        _message.update { "Invalid backup file" }
        false
    }

    fun notify(msg: String) = _message.update { msg }
}

// ---------------------------------------------------------------------------
// Cloudflare WARP
// ---------------------------------------------------------------------------
@HiltViewModel
class WarpViewModel @Inject constructor(
    private val repo: SettingsRepository
) : ViewModel() {
    constructor() : this(MockSettingsRepository())

    val state: StateFlow<AppSettings> =
        repo.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _registering = MutableStateFlow(false)
    val registering: StateFlow<Boolean> = _registering

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() = _message.update { null }

    fun setEnabled(v: Boolean) = viewModelScope.launch {
        val cur = repo.current().warp
        if (v && !cur.configured) {
            _message.update { "Register WARP first — identity is missing" }
            return@launch
        }
        repo.update { it.copy(warp = it.warp.copy(enabled = v)) }
    }
    fun setMode(m: WarpMode) = viewModelScope.launch { repo.update { it.copy(warp = it.warp.copy(mode = m)) } }
    fun setManual(config: WarpConfig) = viewModelScope.launch { repo.update { it.copy(warp = config) } }

    /** Auto-register a fresh WARP identity with Cloudflare and persist it. */
    fun register() {
        if (_registering.value) return
        viewModelScope.launch {
            _registering.update { true }
            try {
                val cfg = withContext(Dispatchers.IO) { WarpRegistrar.register() }
                repo.update { it.copy(warp = cfg.copy(mode = it.warp.mode, enabled = true)) }
                _message.update { "WARP registered successfully" }
            } catch (t: Throwable) {
                _message.update { t.message ?: "WARP registration failed" }
            } finally {
                _registering.update { false }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Logs
// ---------------------------------------------------------------------------
data class LogsUiState(
    val logs: List<LogEntry> = emptyList(),
    val filtered: List<LogEntry> = emptyList(),
    val query: String = "",
    val levelFilter: LogLevel? = null,
    val autoScroll: Boolean = true
)

private fun filterLogs(logs: List<LogEntry>, query: String, levelFilter: LogLevel?): List<LogEntry> =
    logs.filter { e ->
        (levelFilter == null || e.level == levelFilter) &&
            (query.isBlank() || e.message.contains(query, true) || (e.detail?.contains(query, true) == true))
    }

private fun com.v2rayez.app.data.analytics.BugReportResult.statusToken(): String = when (this) {
    is com.v2rayez.app.data.analytics.BugReportResult.Completed -> {
        val sentryToken = when (sentry) {
            is com.v2rayez.app.data.analytics.SentryBugReportStatus.Sent -> "sentry_ok"
            com.v2rayez.app.data.analytics.SentryBugReportStatus.DsnMissing -> "sentry_dsn_missing"
            is com.v2rayez.app.data.analytics.SentryBugReportStatus.Failed -> "sentry_failed"
        }
        "$sentryToken:${if (firebaseSent) "firebase_ok" else "firebase_failed"}"
    }
    is com.v2rayez.app.data.analytics.BugReportResult.Failed -> "report_failed"
}

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val repo: LogRepository,
    private val bugReports: com.v2rayez.app.data.analytics.BugReporter
) : ViewModel() {
    constructor() : this(MockLogRepository(), com.v2rayez.app.data.analytics.MockBugReporter())

    private val query = MutableStateFlow("")
    private val level = MutableStateFlow<LogLevel?>(null)
    private val autoScroll = MutableStateFlow(true)
    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus

    val state: StateFlow<LogsUiState> =
        combine(
            repo.logs(),
            query,
            level,
            autoScroll
        ) { logs, q, lvl, auto ->
            LogsUiState(
                logs = logs,
                filtered = filterLogs(logs, q, lvl),
                query = q,
                levelFilter = lvl,
                autoScroll = auto
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogsUiState())

    fun setQuery(value: String) = query.update { value }
    fun setLevel(value: LogLevel?) = level.update { value }
    fun setAutoScroll(enabled: Boolean) = autoScroll.update { enabled }
    fun clear() = repo.clear()
    fun clearReportStatus() { _reportStatus.value = null }

    suspend fun export(): java.io.File? = repo.exportToFile()

    fun reportBug() = viewModelScope.launch {
        _reportStatus.value = bugReports.send().statusToken()
    }
}

// ---------------------------------------------------------------------------
// Settings (shared across Settings + Advanced VPN)
// ---------------------------------------------------------------------------
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val vpn: VpnController,
    private val logs: LogRepository,
    private val bugReports: com.v2rayez.app.data.analytics.BugReporter
) : ViewModel() {
    constructor() : this(
        MockSettingsRepository(),
        MockVpnController(),
        MockLogRepository(),
        com.v2rayez.app.data.analytics.MockBugReporter()
    )

    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus
    fun clearReportStatus() { _reportStatus.value = null }

    fun reportBug() = viewModelScope.launch {
        _reportStatus.value = bugReports.send().statusToken()
    }

    /** False until the first DataStore emission so first-run gates do not flash. */
    private val _hydrated = MutableStateFlow(false)
    val hydrated: StateFlow<Boolean> = _hydrated

    val state: StateFlow<AppSettings> =
        repo.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /**
     * Live connection state — used by the Advanced VPN screen to honestly reflect the OS
     * Always-on / lockdown state (see [ConnectionState.alwaysOn]) instead of a fake app toggle.
     */
    val connectionState: StateFlow<ConnectionState> = vpn.connectionState

    init {
        viewModelScope.launch {
            repo.settings().first()
            _hydrated.value = true
        }
    }

    private fun edit(transform: (AppSettings) -> AppSettings) =
        viewModelScope.launch { repo.update(transform) }

    fun toggleNotifications(v: Boolean) = edit { it.copy(notifications = v) }
    fun toggleAutoConnect(v: Boolean) = edit { it.copy(autoConnect = v) }
    /** Reconnect the last server after a device reboot (independent of in-app auto-connect). */
    fun toggleBootAutoConnect(v: Boolean) = edit { it.copy(bootAutoConnect = v) }
    fun toggleBatterySaver(v: Boolean) = edit { it.copy(batterySaver = v) }
    fun toggleVpnAlwaysOn(v: Boolean) = edit { it.copy(vpnAlwaysOn = v) }
    fun toggleBlockWithoutVpn(v: Boolean) = edit { it.copy(blockWithoutVpn = v) }
    fun toggleFullDeviceTunnel(v: Boolean) = edit { it.copy(fullDeviceTunnel = v) }
    fun toggleAllowLan(v: Boolean) = edit { it.copy(allowLan = v) }
    fun toggleIpv6(v: Boolean) = edit { it.copy(enableIpv6 = v) }
    fun toggleReduceData(v: Boolean) = edit { it.copy(reduceData = v) }
    fun setTheme(v: String) = edit { it.copy(theme = v) }
    fun setLanguage(v: String) = edit { it.copy(language = SupportedLanguages.normalizeLabel(v)) }
    fun setAccent(v: String) = edit { it.copy(accentColor = v) }
    fun setDefaultCore(type: ProxyCoreType) = edit { it.copy(defaultCore = type) }

    /** Permanently hide the first-launch "join our channels" promo. */
    fun dismissPromo() = edit { it.copy(promoDismissed = true) }

    // --- Routing ---
    fun setRoutingMode(m: RoutingMode) = edit {
        logs.logRouting(LogLevel.INFO, "Routing mode → ${m.name}")
        it.copy(routing = it.routing.copy(mode = m))
    }
    fun toggleBypassLan(v: Boolean) = edit {
        logs.logRouting(LogLevel.INFO, "Bypass LAN → $v")
        it.copy(routing = it.routing.copy(bypassLan = v))
    }
    fun toggleBypassMainland(v: Boolean) = edit {
        logs.logRouting(LogLevel.INFO, "Bypass mainland → $v")
        it.copy(routing = it.routing.copy(bypassMainland = v))
    }
    fun toggleBypassIran(v: Boolean) = edit {
        // Always latch the one-shot when the user touches the toggle (on or off) so auto-config
        // never fights an explicit choice after process restart.
        it.copy(iranBypassAutoApplied = true, routing = it.routing.copy(bypassIran = v))
    }
    fun toggleBlockAds(v: Boolean) = edit { it.copy(routing = it.routing.copy(blockAds = v)) }
    fun setDomainStrategy(s: String) = edit { it.copy(routing = it.routing.copy(domainStrategy = s)) }

    // --- DNS ---
    fun setRemoteDns(s: String) = edit { it.copy(dns = it.dns.copy(remoteDns = s)) }
    fun setDomesticDns(s: String) = edit { it.copy(dns = it.dns.copy(domesticDns = s)) }
    fun toggleFakeDns(v: Boolean) = edit { it.copy(dns = it.dns.copy(enableFakeDns = v)) }
    fun addHost(domain: String, value: String) = edit {
        it.copy(dns = it.dns.copy(hosts = it.dns.hosts + HostMapping(domain, value)))
    }
    fun removeHost(index: Int) = edit {
        it.copy(dns = it.dns.copy(hosts = it.dns.hosts.filterIndexed { i, _ -> i != index }))
    }

    // --- App proxy ---
    fun setAppProxyEnabled(v: Boolean) = edit { it.copy(appProxy = it.appProxy.copy(enabled = v)) }
    fun setAppProxyBypassMode(v: Boolean) = edit { it.copy(appProxy = it.appProxy.copy(bypassMode = v)) }
    fun toggleAppPackage(pkg: String) = edit {
        val set = it.appProxy.packages
        it.copy(appProxy = it.appProxy.copy(packages = if (pkg in set) set - pkg else set + pkg))
    }

    /** Per-app rules are applied only when VpnService rebuilds the TUN — reconnect to apply. */
    fun reconnectVpn() {
        val state = vpn.connectionState.value
        val server = state.server ?: return
        // Always re-enter CONNECTING so banners / Home react; service tears down the prior TUN.
        vpn.connect(server)
    }

    // --- Fragment (SNI Tunnel) ---
    fun toggleFragment(v: Boolean) = edit { it.copy(fragment = it.fragment.copy(enabled = v)) }
    fun setFragmentPackets(s: String) = edit { it.copy(fragment = it.fragment.copy(packets = s)) }
    fun setFragmentLength(s: String) = edit { it.copy(fragment = it.fragment.copy(length = s)) }
    fun setFragmentInterval(s: String) = edit { it.copy(fragment = it.fragment.copy(interval = s)) }

    // --- Tor ---
    fun toggleTor(v: Boolean) = edit { it.copy(tor = it.tor.copy(enabled = v)) }
    fun setTorHost(s: String) = edit { it.copy(tor = it.tor.copy(socksHost = s)) }
    fun setTorPort(p: Int) = edit { it.copy(tor = it.tor.copy(socksPort = p)) }
    fun setTorEngine(e: com.v2rayez.app.domain.model.TorEngineType) = edit { it.copy(tor = it.tor.copy(engine = e)) }
    fun setTorTransport(t: com.v2rayez.app.domain.model.TorTransport) = edit { it.copy(tor = it.tor.copy(transport = t)) }
    fun setTorBridges(lines: List<String>) = edit { it.copy(tor = it.tor.copy(bridges = lines)) }
    fun toggleTorAutoRotate(v: Boolean) = edit { it.copy(tor = it.tor.copy(autoRotateBridges = v)) }

    // --- Domain fronting ---
    fun toggleDomainFront(v: Boolean) = edit { it.copy(domainFront = it.domainFront.copy(enabled = v)) }
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
    fun setFakeSni(s: String) = edit { it.copy(domainFront = it.domainFront.copy(fakeSni = s.trim())) }

    // --- TLS / Certificates ---
    fun toggleAllowInsecure(v: Boolean) = edit { it.copy(tls = it.tls.copy(allowInsecure = v)) }
    fun addPinnedCert(sha256: String) = edit {
        it.copy(tls = it.tls.copy(pinnedSha256 = it.tls.pinnedSha256 + sha256))
    }
    fun removePinnedCert(index: Int) = edit {
        it.copy(tls = it.tls.copy(pinnedSha256 = it.tls.pinnedSha256.filterIndexed { i, _ -> i != index }))
    }

    // --- Connection tuning ---
    fun setMtu(v: Int) = edit { it.copy(mtu = v) }
    fun setSocksPort(v: Int) = edit { it.copy(socksPort = v) }
    fun toggleSniffing(v: Boolean) = edit { it.copy(enableSniffing = v) }
    fun toggleLocalDns(v: Boolean) = edit { it.copy(enableLocalDns = v) }
    fun toggleMux(v: Boolean) = edit { it.copy(enableMux = v) }
    fun setMuxConcurrency(v: Int) = edit { it.copy(muxConcurrency = v.coerceIn(1, 1024)) }

    // --- LAN / hotspot sharing ---
    fun setLanSharing(v: Boolean) = edit { it.copy(enableLanSharing = v) }
    /**
     * Unified hotspot toggle: ConfigBuilder binds 0.0.0.0 when `allowLan || enableLanSharing`,
     * so keep both flags in lockstep to avoid a half-on state that looks enabled but binds
     * to loopback only.
     */
    fun setHotspotSharing(v: Boolean) = edit { it.copy(enableLanSharing = v, allowLan = v) }
    fun setHttpPort(v: Int) = edit { it.copy(httpPort = v) }

    // --- Routing rules (custom) ---
    fun addRule(rule: RoutingRule) = edit {
        it.copy(routing = it.routing.copy(rules = it.routing.rules + rule))
    }
    fun updateRule(rule: RoutingRule) = edit {
        it.copy(routing = it.routing.copy(rules = it.routing.rules.map { r -> if (r.id == rule.id) rule else r }))
    }
    fun removeRule(id: String) = edit {
        it.copy(routing = it.routing.copy(rules = it.routing.rules.filterNot { r -> r.id == id }))
    }
    fun toggleRule(id: String, enabled: Boolean) = edit {
        it.copy(routing = it.routing.copy(rules = it.routing.rules.map { r -> if (r.id == id) r.copy(enabled = enabled) else r }))
    }
    fun moveRule(from: Int, to: Int) = edit {
        val list = it.routing.rules.toMutableList()
        if (from in list.indices && to in list.indices) {
            list.add(to, list.removeAt(from))
        }
        it.copy(routing = it.routing.copy(rules = list))
    }

    // --- Rule providers (remote rulesets from GitHub/URL) ---
    private val _providerBusy = MutableStateFlow(false)
    val providerBusy: StateFlow<Boolean> = _providerBusy
    private val _providerMessage = MutableStateFlow<String?>(null)
    val providerMessage: StateFlow<String?> = _providerMessage
    fun clearProviderMessage() = _providerMessage.update { null }

    fun addProvider(name: String, url: String, outbound: RuleOutbound, intervalHours: Int = 24) {
        if (url.isBlank() || _providerBusy.value) return
        viewModelScope.launch {
            _providerBusy.update { true }
            try {
                val id = java.util.UUID.randomUUID().toString()
                val parsed = withContext(Dispatchers.IO) { RuleProviderFetcher.fetch(url.trim()) }
                val label = name.ifBlank { url.trim().substringAfterLast('/') }
                val provider = RuleProvider(
                    id = id, name = label, url = url.trim(), outbound = outbound,
                    enabled = true, lastUpdated = System.currentTimeMillis(),
                    entryCount = parsed.domains.size + parsed.ips.size,
                    updateIntervalHours = intervalHours
                )
                val rule = RoutingRule(
                    id = id, enabled = true, remark = label, outbound = outbound,
                    domains = parsed.domains, ips = parsed.ips
                )
                repo.update {
                    it.copy(routing = it.routing.copy(
                        providers = it.routing.providers + provider,
                        rules = it.routing.rules + rule
                    ))
                }
                _providerMessage.update { "Imported ${provider.entryCount} entries" }
            } catch (t: Throwable) {
                _providerMessage.update { t.message ?: "Import failed" }
            } finally {
                _providerBusy.update { false }
            }
        }
    }

    fun refreshProvider(id: String) {
        if (_providerBusy.value) return
        viewModelScope.launch {
            val provider = repo.current().routing.providers.firstOrNull { it.id == id } ?: return@launch
            _providerBusy.update { true }
            try {
                val parsed = withContext(Dispatchers.IO) { RuleProviderFetcher.fetch(provider.url) }
                repo.update { st ->
                    st.copy(routing = st.routing.copy(
                        providers = st.routing.providers.map { p ->
                            if (p.id == id) p.copy(lastUpdated = System.currentTimeMillis(), entryCount = parsed.domains.size + parsed.ips.size) else p
                        },
                        rules = st.routing.rules.map { r ->
                            if (r.id == id) r.copy(domains = parsed.domains, ips = parsed.ips) else r
                        }
                    ))
                }
                _providerMessage.update { "Updated ${parsed.domains.size + parsed.ips.size} entries" }
            } catch (t: Throwable) {
                _providerMessage.update { t.message ?: "Refresh failed" }
            } finally {
                _providerBusy.update { false }
            }
        }
    }

    fun removeProvider(id: String) = edit {
        it.copy(routing = it.routing.copy(
            providers = it.routing.providers.filterNot { p -> p.id == id },
            rules = it.routing.rules.filterNot { r -> r.id == id }
        ))
    }

    /** Refresh enabled providers whose auto-update interval has elapsed (sequential). */
    fun refreshStaleProviders() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val stale = repo.current().routing.providers.filter {
                it.enabled && it.updateIntervalHours > 0 &&
                    now - it.lastUpdated > it.updateIntervalHours * 60L * 60L * 1000L
            }
            for (p in stale) {
                val parsed = runCatching { withContext(Dispatchers.IO) { RuleProviderFetcher.fetch(p.url) } }.getOrNull() ?: continue
                repo.update { st ->
                    st.copy(routing = st.routing.copy(
                        providers = st.routing.providers.map { x ->
                            if (x.id == p.id) x.copy(lastUpdated = System.currentTimeMillis(), entryCount = parsed.domains.size + parsed.ips.size) else x
                        },
                        rules = st.routing.rules.map { r ->
                            if (r.id == p.id) r.copy(domains = parsed.domains, ips = parsed.ips) else r
                        }
                    ))
                }
            }
        }
    }

    fun toggleProvider(id: String, enabled: Boolean) = edit {
        it.copy(routing = it.routing.copy(
            providers = it.routing.providers.map { p -> if (p.id == id) p.copy(enabled = enabled) else p },
            rules = it.routing.rules.map { r -> if (r.id == id) r.copy(enabled = enabled) else r }
        ))
    }
}

// ---------------------------------------------------------------------------
// Onboarding (first-run welcome wizard)
// ---------------------------------------------------------------------------
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: SettingsRepository
) : ViewModel() {
    constructor() : this(MockSettingsRepository())

    val settings: StateFlow<AppSettings> =
        repo.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setLanguage(label: String) = viewModelScope.launch {
        repo.update { it.copy(language = SupportedLanguages.normalizeLabel(label)) }
    }

    fun acceptTerms() = viewModelScope.launch {
        repo.update { it.copy(termsAccepted = true) }
    }

    /** Persist feature picks only — packs / settings apply on [completeOnboarding]. */
    fun setWants(wants: OnboardingWants, analytics: Boolean) = viewModelScope.launch {
        repo.update {
            it.copy(
                onboardingWants = wants.copy(analytics = analytics),
                analyticsConsent = analytics
            )
        }
    }

    fun completeOnboarding() = viewModelScope.launch {
        val current = repo.current()
        val wants = current.onboardingWants.copy(analytics = current.analyticsConsent)
        val applied = com.v2rayez.app.data.core.OnboardingFeatureMapping.apply(current, wants)
        repo.update {
            applied.copy(
                termsAccepted = true,
                onboardingComplete = true,
                crashlyticsConsent = true
            )
        }
        // PackInstallCoordinator.start() / Application observes pendingAddonInstall and drains.
    }
}

// ---------------------------------------------------------------------------
// Geo pack status (drives the Iran geo-pack CTA on Home)
// ---------------------------------------------------------------------------
@HiltViewModel
class GeoStatusViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val geoAssets: com.v2rayez.app.data.core.GeoAssetManager
) : ViewModel() {

    private val _showIranGeoCta = MutableStateFlow(false)
    /** True when the device is in Iran but the full geo pack (geosite:ir / geoip:ir) is missing. */
    val showIranGeoCta: StateFlow<Boolean> = _showIranGeoCta

    init { refresh() }

    /** Re-evaluate (e.g. after returning from Core manager where the pack may have installed). */
    fun refresh() = viewModelScope.launch {
        val show = withContext(Dispatchers.IO) {
            com.v2rayez.app.data.core.IranRouting.shouldShowGeoPackCta(
                countryCode = com.v2rayez.app.data.core.DeviceCountry.detect(context),
                geositeAvailable = geoAssets.geositeAvailable()
            )
        }
        _showIranGeoCta.value = show
    }
}

/** Surfaces the background pack-install banner driven by [PackInstallCoordinator]. */
@HiltViewModel
class PackDownloadBannerViewModel @Inject constructor(
    private val packInstalls: com.v2rayez.app.data.core.PackInstallCoordinator
) : ViewModel() {
    val show: StateFlow<Boolean> = packInstalls.homeInstallBanner
    fun dismiss() = packInstalls.dismissHomeBanner()
}
