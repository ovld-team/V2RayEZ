package com.v2rayez.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query(
        """
        SELECT id, name, country, countryCode, protocol, transport, security, sni, address,
               pingMs, signal, `group`, isFavorite, host, port, uuid, password, method,
               ssPlugin, ssPluginOptions, alterId, flow, network, headerType, path, requestHost,
               streamSecurity, alpn, fingerprint, allowInsecure, publicKey, shortId, spiderX,
               subscriptionId, frontProxyId, userModified, sortOrder, customGroup, preferredCore,
               sshUser, wgLocalAddresses, wgAllowedIps, wgReserved, wgMtu,
               dnsTunnelDomain, dnsTunnelPubKey, dnsTunnelResolver, dnsTunnelMode
        FROM servers ORDER BY sortOrder ASC, name ASC
        """
    )
    fun observeAll(): Flow<List<ServerListEntity>>

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllFull(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId")
    suspend fun getBySubscription(subId: String): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: String): ServerEntity?

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerEntity>)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: String)

    @Query("UPDATE servers SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE servers SET pingMs = :pingMs WHERE id = :id")
    suspend fun setPing(id: String, pingMs: Int)

    @Query("DELETE FROM servers WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("UPDATE servers SET isFavorite = :fav WHERE id IN (:ids)")
    suspend fun setFavoriteAll(ids: List<String>, fav: Boolean)

    @Query("UPDATE servers SET customGroup = :group WHERE id IN (:ids)")
    suspend fun setCustomGroupAll(ids: List<String>, group: String?)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE subscriptions SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE subscriptions SET url = :url WHERE id = :id")
    suspend fun updateUrl(id: String, url: String)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Query("SELECT COALESCE(SUM(downBytes),0) FROM sessions")
    fun observeTotalDown(): Flow<Long>

    @Query("SELECT COALESCE(SUM(upBytes),0) FROM sessions")
    fun observeTotalUp(): Flow<Long>

    @Query("SELECT COALESCE(SUM(downBytes),0) FROM sessions WHERE endedAt >= :since")
    fun observeTotalDownSince(since: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(upBytes),0) FROM sessions WHERE endedAt >= :since")
    fun observeTotalUpSince(since: Long): Flow<Long>

    @Query("SELECT * FROM sessions ORDER BY endedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE endedAt >= :since ORDER BY endedAt DESC LIMIT 200")
    fun observeRecentSince(since: Long): Flow<List<SessionEntity>>

    @Query("SELECT COALESCE(AVG(endedAt - startedAt),0) FROM sessions")
    fun observeAvgDurationMs(): Flow<Long>

    /** Persisted lifetime usage (down + up) per server, for the Servers-list traffic labels. */
    @Query("SELECT serverId AS serverId, COALESCE(SUM(downBytes + upBytes),0) AS bytes FROM sessions GROUP BY serverId")
    fun observeUsageByServer(): Flow<List<ServerUsageRow>>
}

/** Aggregate row for [SessionDao.observeUsageByServer]. */
data class ServerUsageRow(val serverId: String, val bytes: Long)

@Dao
interface DailyTrafficDao {
    @Query("SELECT * FROM daily_traffic ORDER BY dateEpochDay ASC")
    fun observeAll(): Flow<List<DailyTrafficEntity>>

    @Query("SELECT * FROM daily_traffic WHERE dateEpochDay >= :sinceDay ORDER BY dateEpochDay ASC")
    fun observeSince(sinceDay: Long): Flow<List<DailyTrafficEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: DailyTrafficEntity)

    @Query("UPDATE daily_traffic SET downBytes = downBytes + :down, upBytes = upBytes + :up WHERE dateEpochDay = :day")
    suspend fun increment(day: Long, down: Long, up: Long)

    /** Atomically add byte deltas to a day's bucket, creating the row if needed. */
    @Transaction
    suspend fun addTraffic(day: Long, down: Long, up: Long) {
        insertIfAbsent(DailyTrafficEntity(day, 0L, 0L))
        increment(day, down, up)
    }
}
