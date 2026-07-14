package com.v2rayez.app.data.repository

import com.v2rayez.app.data.local.DailyTrafficDao
import com.v2rayez.app.data.local.DailyTrafficEntity
import com.v2rayez.app.data.local.SessionDao
import com.v2rayez.app.data.local.SessionEntity
import com.v2rayez.app.data.service.VpnStateHolder
import com.v2rayez.app.domain.model.StatsTotals
import com.v2rayez.app.domain.model.TopServer
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.model.UsageSlice
import com.v2rayez.app.domain.repository.StatsRepository
import com.v2rayez.app.util.Formatters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealStatsRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val dailyTrafficDao: DailyTrafficDao,
    private val stateHolder: VpnStateHolder
) : StatsRepository {

    override fun totals(since: Long): Flow<StatsTotals> =
        combine(
            if (since <= 0L) sessionDao.observeTotalDown() else sessionDao.observeTotalDownSince(since),
            if (since <= 0L) sessionDao.observeTotalUp() else sessionDao.observeTotalUpSince(since),
            recentSince(since),
            stateHolder.sessionDown,
            stateHolder.sessionUp
        ) { lifeDown, lifeUp, sessions, sessDown, sessUp ->
            val livePing = stateHolder.connectionState.value.pingMs
            StatsTotals(
                totalDownload = Formatters.bytes(lifeDown + sessDown),
                totalUpload = Formatters.bytes(lifeUp + sessUp),
                averageSpeed = averageSessionSpeed(sessions),
                averagePing = if (livePing >= 0) "$livePing ms" else "-"
            )
        }

    /** Session-weighted average throughput over the window: total bytes / total connected seconds. */
    private fun averageSessionSpeed(sessions: List<SessionEntity>): String {
        val totalBytes = sessions.sumOf { it.downBytes + it.upBytes }
        val totalSeconds = sessions.sumOf { ((it.endedAt - it.startedAt) / 1000L).coerceAtLeast(0L) }
        if (totalSeconds <= 0L || totalBytes <= 0L) return "-"
        return Formatters.mbps(totalBytes / totalSeconds)
    }

    /**
     * Real per-day history from the [DailyTrafficDao] plus unflushed live session bytes, so the
     * chart survives restarts without double-counting already-persisted daily_traffic.
     * Bucket count adapts to the selected range: 7 days for Today/Week, up to 30 for Month/All.
     * Values are in MiB (1024²) to match [com.v2rayez.app.util.Formatters].
     */
    override fun weeklyTraffic(since: Long): Flow<List<TrafficPoint>> {
        val bucketDays = bucketDaysFor(since)
        val today = LocalDate.now().toEpochDay()
        val startDay = today - (bucketDays - 1)
        return combine(
            dailyTrafficDao.observeSince(startDay),
            stateHolder.sessionDown,
            stateHolder.sessionUp,
            stateHolder.flushedSessionDown,
            stateHolder.flushedSessionUp
        ) { rows, liveDown, liveUp, flushedDown, flushedUp ->
            val unflushedDown = (liveDown - flushedDown).coerceAtLeast(0L)
            val unflushedUp = (liveUp - flushedUp).coerceAtLeast(0L)
            buildDailySeries(rows, startDay, today, bucketDays, unflushedDown, unflushedUp)
        }
    }

    private fun bucketDaysFor(since: Long): Int {
        if (since <= 0L) return 30
        val windowDays = ((System.currentTimeMillis() - since) / DAY_MS).toInt()
        return if (windowDays <= 7) 7 else windowDays.coerceAtMost(30)
    }

    private fun buildDailySeries(
        rows: List<DailyTrafficEntity>,
        startDay: Long,
        today: Long,
        bucketDays: Int,
        unflushedDown: Long,
        unflushedUp: Long
    ): List<TrafficPoint> {
        val byDay = rows.associateBy { it.dateEpochDay }
        val useWeekday = bucketDays <= 8
        return (0 until bucketDays).map { offset ->
            val day = startDay + offset
            val row = byDay[day]
            var down = row?.downBytes ?: 0L
            var up = row?.upBytes ?: 0L
            // Fold only not-yet-flushed live bytes into today's bucket.
            if (day == today) {
                down += unflushedDown
                up += unflushedUp
            }
            val date = LocalDate.ofEpochDay(day)
            val label = if (useWeekday) {
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            } else {
                date.dayOfMonth.toString()
            }
            TrafficPoint(
                label = label,
                download = down / MIB,
                upload = up / MIB
            )
        }
    }

    override fun topServers(since: Long): Flow<List<TopServer>> =
        recentSince(since).map { sessions ->
            val grouped = sessions.groupBy { it.serverId }
                .map { (_, list) ->
                    val total = list.sumOf { it.downBytes + it.upBytes }
                    Triple(list.first(), total, list.first().countryCode)
                }
                .sortedByDescending { it.second }
                .take(4)
            val max = grouped.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
            grouped.map { (session, total, cc) ->
                TopServer(
                    name = session.serverName,
                    countryCode = cc,
                    usageLabel = Formatters.bytes(total),
                    fraction = (total.toFloat() / max.toFloat())
                )
            }
        }

    override fun dataUsage(since: Long): Flow<List<UsageSlice>> =
        recentSince(since).map { sessions ->
            byProtocol(sessions)
        }

    override fun serverUsage(): Flow<Map<String, Long>> =
        combine(
            sessionDao.observeUsageByServer(),
            stateHolder.connectionState,
            stateHolder.sessionDown,
            stateHolder.sessionUp
        ) { rows, conn, liveDown, liveUp ->
            val base = rows.associate { it.serverId to it.bytes }.toMutableMap()
            // Fold the live (not-yet-persisted) session into the connected server's total.
            val liveId = conn.server?.id
            if (liveId != null && (liveDown > 0L || liveUp > 0L)) {
                base[liveId] = (base[liveId] ?: 0L) + liveDown + liveUp
            }
            base
        }

    private fun recentSince(since: Long): Flow<List<SessionEntity>> =
        if (since <= 0L) sessionDao.observeRecent() else sessionDao.observeRecentSince(since)

    private fun byProtocol(sessions: List<SessionEntity>): List<UsageSlice> {
        if (sessions.isEmpty()) return emptyList()
        val byProto = sessions.groupBy { it.protocol }
            .mapValues { (_, list) -> list.sumOf { it.downBytes + it.upBytes } }
        val total = byProto.values.sum().takeIf { it > 0 } ?: return emptyList()
        return byProto.entries.sortedByDescending { it.value }.map { (proto, bytes) ->
            UsageSlice(
                label = proto,
                value = bytes.toFloat() / total.toFloat(),
                valueLabel = Formatters.bytes(bytes)
            )
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** MiB — consistent with Formatters (1024-based), not decimal MB. */
        const val MIB = 1024f * 1024f
    }
}
