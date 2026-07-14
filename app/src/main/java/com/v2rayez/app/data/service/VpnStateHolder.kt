package com.v2rayez.app.data.service

import android.content.Context
import com.v2rayez.app.R
import com.v2rayez.app.data.local.DailyTrafficDao
import com.v2rayez.app.data.widget.VpnWidgetUpdater
import com.v2rayez.app.domain.model.ActivityItem
import com.v2rayez.app.domain.model.ActivityType
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

internal data class PendingTrafficBatch(val down: Long, val up: Long)

/**
 * Atomically accumulates and drains traffic so stats ticks and lifecycle flushes cannot
 * snapshot or clear the same bytes concurrently.
 */
internal class PendingTrafficAccumulator {
    private val lock = Any()
    private var down = 0L
    private var up = 0L
    private var ticks = 0

    fun add(downDelta: Long, upDelta: Long): PendingTrafficBatch? = synchronized(lock) {
        down += downDelta
        up += upDelta
        ticks++
        if (ticks >= 10 || down + up > 5_000_000L) drainLocked() else null
    }

    fun drain(): PendingTrafficBatch? = synchronized(lock) { drainLocked() }

    private fun drainLocked(): PendingTrafficBatch? {
        if (down <= 0L && up <= 0L) return null
        return PendingTrafficBatch(down, up).also {
            down = 0L
            up = 0L
            ticks = 0
        }
    }
}

/**
 * Process-wide, observable VPN state shared between [V2RayVpnService] and the
 * repository/controller layer. The service is the sole writer.
 */
@Singleton
class VpnStateHolder @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dailyTrafficDao: DailyTrafficDao
) {

    companion object {
        val DISCONNECTED = ConnectionState(
            status = ConnectionStatus.DISCONNECTED,
            server = null,
            uptimeSeconds = 0,
            downloadLabel = "0 B/s",
            uploadLabel = "0 B/s",
            pingMs = -1,
            speedLabel = "0 Mbps"
        )
        private val WEEK_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        /** Number of ~1 Hz samples retained for the live Home throughput graph. */
        private const val LIVE_WINDOW = 60
        /** MiB divisor — keep chart units aligned with [Formatters] (1024-based). */
        private const val MIB = 1024f * 1024f
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _weeklyTraffic = MutableStateFlow(WEEK_LABELS.map { TrafficPoint(it, 0f, 0f) })
    val weeklyTraffic: StateFlow<List<TrafficPoint>> = _weeklyTraffic.asStateFlow()

    /** Rolling window of the last [LIVE_WINDOW] per-second throughput samples. */
    private val _liveThroughput = MutableStateFlow<List<ThroughputSample>>(emptyList())
    val liveThroughput: StateFlow<List<ThroughputSample>> = _liveThroughput.asStateFlow()

    private val _recentActivity = MutableStateFlow<List<ActivityItem>>(emptyList())
    val recentActivity: StateFlow<List<ActivityItem>> = _recentActivity.asStateFlow()

    /** Cumulative session totals (bytes) — reset on each new connection. */
    private val _sessionDown = MutableStateFlow(0L)
    val sessionDown: StateFlow<Long> = _sessionDown.asStateFlow()
    private val _sessionUp = MutableStateFlow(0L)
    val sessionUp: StateFlow<Long> = _sessionUp.asStateFlow()

    /**
     * Bytes from the current session already written to [DailyTrafficDao].
     * Charts must add only (session − flushed) so flushed daily rows are not double-counted.
     */
    private val _flushedSessionDown = MutableStateFlow(0L)
    val flushedSessionDown: StateFlow<Long> = _flushedSessionDown.asStateFlow()
    private val _flushedSessionUp = MutableStateFlow(0L)
    val flushedSessionUp: StateFlow<Long> = _flushedSessionUp.asStateFlow()

    @Volatile private var connectStartMs: Long = 0L
    @Volatile private var trafficWeekOfYear: Int = currentWeekOfYear()
    private val sessionGeneration = AtomicLong()

    // Batched daily-traffic persistence: accumulate deltas and flush every few ticks
    // to keep Room writes off the per-second hot path.
    private val pendingTraffic = PendingTrafficAccumulator()

    fun setConnecting(server: Server) {
        _connectionState.update { DISCONNECTED.copy(status = ConnectionStatus.CONNECTING, server = server) }
        notifyWidgetsImmediate()
    }

    fun setConnected(server: Server) {
        connectStartMs = System.currentTimeMillis()
        resetSessionCounters()
        _liveThroughput.value = emptyList()
        _connectionState.update {
            it.copy(
                status = ConnectionStatus.CONNECTED,
                server = server,
                uptimeSeconds = 0,
                // Seed from the last offline test; the service refreshes it live.
                pingMs = if (server.pingMs > 0) server.pingMs else -1,
                errorMessage = null
            )
        }
        addActivity(appContext.getString(R.string.activity_connected_to, server.name), ActivityType.CONNECTED)
        notifyWidgetsImmediate()
    }

    /**
     * Reflect the OS-level Always-on / lockdown state for the running session so the UI can
     * show the truth instead of a fake app toggle. Only applied while connected.
     */
    fun setAlwaysOnState(alwaysOn: Boolean, lockdown: Boolean) {
        if (_connectionState.value.status != ConnectionStatus.CONNECTED &&
            _connectionState.value.status != ConnectionStatus.CONNECTING
        ) return
        _connectionState.update { it.copy(alwaysOn = alwaysOn, lockdown = lockdown) }
    }

    /** Live ping (ms) measured through the running tunnel. */
    fun setPing(ms: Int) {
        if (_connectionState.value.status != ConnectionStatus.CONNECTED) return
        _connectionState.update { it.copy(pingMs = ms) }
        notifyWidgetsThrottled()
    }

    /**
     * Report a connection failure so the UI can show why (instead of crashing/failing silently).
     * [needsCoreManager] flags failures caused by a missing on-demand pack/core binary so Home
     * can surface an "Open Core manager" CTA regardless of the (possibly localized) message text.
     */
    fun setError(message: String, needsCoreManager: Boolean = false) {
        flushPendingTraffic()
        connectStartMs = 0L
        resetSessionCounters()
        _liveThroughput.value = emptyList()
        _connectionState.value = DISCONNECTED.copy(errorMessage = message, needsCoreManager = needsCoreManager)
        addActivity(message, ActivityType.DURATION)
        notifyWidgetsImmediate()
    }

    fun setDisconnected() {
        val prev = _connectionState.value.server
        if (prev != null && _connectionState.value.status == ConnectionStatus.CONNECTED) {
            addActivity(appContext.getString(R.string.activity_disconnected_from, prev.name), ActivityType.DURATION)
        }
        flushPendingTraffic()
        connectStartMs = 0L
        resetSessionCounters()
        _liveThroughput.value = emptyList()
        _connectionState.value = DISCONNECTED
        notifyWidgetsImmediate()
    }

    private fun resetSessionCounters() {
        sessionGeneration.incrementAndGet()
        _sessionDown.value = 0L
        _sessionUp.value = 0L
        _flushedSessionDown.value = 0L
        _flushedSessionUp.value = 0L
    }

    /** Called by the service stats loop (~1 Hz) with per-interval byte deltas. */
    fun onStatsTick(downDelta: Long, upDelta: Long, intervalSeconds: Double) {
        if (_connectionState.value.status != ConnectionStatus.CONNECTED) return
        val safeDownDelta = downDelta.coerceAtLeast(0L)
        val safeUpDelta = upDelta.coerceAtLeast(0L)
        _sessionDown.update { it + safeDownDelta }
        _sessionUp.update { it + safeUpDelta }
        val downRate = if (intervalSeconds > 0) (safeDownDelta / intervalSeconds).toLong() else 0L
        val upRate = if (intervalSeconds > 0) (safeUpDelta / intervalSeconds).toLong() else 0L
        val uptime = if (connectStartMs > 0) (System.currentTimeMillis() - connectStartMs) / 1000 else 0L
        _connectionState.update {
            it.copy(
                uptimeSeconds = uptime,
                downloadLabel = Formatters.speed(downRate),
                uploadLabel = Formatters.speed(upRate),
                speedLabel = Formatters.mbps(downRate),
                sessionDownLabel = Formatters.bytes(_sessionDown.value),
                sessionUpLabel = Formatters.bytes(_sessionUp.value)
            )
        }
        _liveThroughput.update { samples ->
            (samples + ThroughputSample(downRate, upRate)).takeLast(LIVE_WINDOW)
        }
        addToToday(safeDownDelta, safeUpDelta)
        persistDaily(safeDownDelta, safeUpDelta)
        notifyWidgetsThrottled()
    }

    private fun notifyWidgetsImmediate() {
        runCatching { VpnWidgetUpdater.refreshAll(appContext) }
    }

    private fun notifyWidgetsThrottled() {
        runCatching { VpnWidgetUpdater.refreshAllThrottled(appContext) }
    }

    /** Accumulate byte deltas and flush to the daily-traffic table every few ticks. */
    private fun persistDaily(downDelta: Long, upDelta: Long) {
        pendingTraffic.add(downDelta, upDelta)?.let {
            persistTrafficBatch(it, sessionGeneration.get())
        }
    }

    private fun flushPendingTraffic() {
        pendingTraffic.drain()?.let {
            persistTrafficBatch(it, sessionGeneration.get())
        }
    }

    private fun persistTrafficBatch(batch: PendingTrafficBatch, generation: Long) {
        val day = currentEpochDay()
        ioScope.launch {
            runCatching { dailyTrafficDao.addTraffic(day, batch.down, batch.up) }
                .onSuccess {
                    // Advance chart accounting only after Room has acknowledged the batch.
                    if (sessionGeneration.get() == generation) {
                        _flushedSessionDown.update { it + batch.down }
                        _flushedSessionUp.update { it + batch.up }
                    }
                }
        }
    }

    private fun currentEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

    private fun addToToday(downDelta: Long, upDelta: Long) {
        val cal = Calendar.getInstance()
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        if (week != trafficWeekOfYear) {
            trafficWeekOfYear = week
            _weeklyTraffic.value = WEEK_LABELS.map { TrafficPoint(it, 0f, 0f) }
        }
        // Calendar: SUNDAY=1..SATURDAY=7 -> our Mon..Sun index.
        val idx = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; Calendar.SATURDAY -> 5
            else -> 6
        }
        _weeklyTraffic.update { list ->
            list.mapIndexed { i, p ->
                if (i == idx) p.copy(
                    download = p.download + downDelta / MIB,
                    upload = p.upload + upDelta / MIB
                ) else p
            }
        }
    }

    fun addActivity(title: String, type: ActivityType) {
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val item = ActivityItem(UUID.randomUUID().toString(), title, time, type)
        _recentActivity.update { (listOf(item) + it).take(10) }
    }

    private fun currentWeekOfYear(): Int = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
}
