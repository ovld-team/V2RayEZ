package com.v2rayez.app.data.repository

import com.v2rayez.app.data.local.ServerDao
import com.v2rayez.app.data.local.SubscriptionDao
import com.v2rayez.app.data.local.toEntity
import com.v2rayez.app.data.local.toModel
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.ImportResult
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val http: OkHttpClient
) : ServerRepository {

    override fun servers(): Flow<List<Server>> =
        serverDao.observeAll().map { list -> list.map { it.toModel() } }

    override fun subscriptions(): Flow<List<Subscription>> =
        subscriptionDao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun getServer(id: String): Server? = serverDao.getById(id)?.toModel()

    override suspend fun toggleFavorite(id: String) {
        val current = serverDao.getById(id) ?: return
        serverDao.setFavorite(id, !current.isFavorite)
    }

    override suspend fun upsert(server: Server) = serverDao.upsert(server.toEntity())

    override suspend fun delete(id: String) = serverDao.delete(id)

    override suspend fun duplicate(id: String): Server? {
        val original = serverDao.getById(id)?.toModel() ?: return null
        // Detach the copy from its subscription so it isn't wiped on the next subscription refresh.
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (copy)",
            subscriptionId = null,
            group = ServerGroup.MANUAL
        )
        serverDao.upsert(copy.toEntity())
        return copy
    }

    override suspend fun importFromText(text: String): ImportResult {
        // A bare http(s):// line is a subscription URL, not a share URI — fetch it.
        val trimmed = text.trim()
        if (isSubscriptionUrl(trimmed)) {
            return addSubscription(trimmed.substringAfterLast('/').ifBlank { trimmed }, trimmed)
        }
        val detail = ProxyParser.parseManyDetailed(text, group = ServerGroup.MANUAL)
        if (detail.servers.isEmpty()) {
            return ImportResult(false, 0, detail.summaryMessage())
        }
        serverDao.upsertAll(detail.servers.map { it.toEntity() })
        return ImportResult(true, detail.servers.size, detail.summaryMessage())
    }

    /** True when [text] is a single remote subscription URL (not a proxy share URI). */
    private fun isSubscriptionUrl(text: String): Boolean {
        if (text.contains('\n') || text.contains(' ')) return false
        val lower = text.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://"))
    }

    override suspend fun previewFromUrl(url: String): List<Server> {
        val body = fetch(url) ?: return emptyList()
        return ProxyParser.parseMany(body, group = ServerGroup.MANUAL)
    }

    override suspend fun addSubscription(name: String, url: String): ImportResult {
        val id = UUID.randomUUID().toString()
        val body = fetch(url) ?: return ImportResult(false, 0, "Failed to fetch subscription")
        val detail = ProxyParser.parseManyDetailed(body, group = ServerGroup.SUBSCRIPTION, subscriptionId = id)
        if (detail.servers.isEmpty()) {
            return ImportResult(false, 0, detail.summaryMessage())
        }
        subscriptionDao.upsert(
            Subscription(
                id,
                name.ifBlank { url },
                url,
                true,
                System.currentTimeMillis(),
                detail.servers.size
            ).toEntity()
        )
        serverDao.upsertAll(detail.servers.map { it.toEntity() })
        return ImportResult(true, detail.servers.size, detail.summaryMessage())
    }

    override suspend fun refreshSubscription(id: String): ImportResult {
        val sub = subscriptionDao.getById(id)?.toModel() ?: return ImportResult(false, 0, "Subscription not found")
        val body = fetch(sub.url) ?: return ImportResult(false, 0, "Failed to fetch subscription")
        val detail = ProxyParser.parseManyDetailed(body, group = ServerGroup.SUBSCRIPTION, subscriptionId = id)
        val parsed = detail.servers
        // Never wipe the existing servers on a transient/empty/unsupported response.
        if (parsed.isEmpty()) return ImportResult(false, 0, detail.summaryMessage())

        // MERGE rather than delete+reinsert so pings, favorites, and in-place user edits survive.
        val existing = serverDao.observeAll().first()
            .map { it.toModel() }
            .filter { it.subscriptionId == id }
        val existingById = existing.associateBy { it.id }
        val existingByAddr = existing.associateBy { addrKey(it) }

        val consumed = mutableSetOf<String>()
        val merged = parsed.map { fresh ->
            // Match on the deterministic id first, then fall back to host:port+protocol.
            val match = existingById[fresh.id] ?: existingByAddr[addrKey(fresh)]
            if (match != null) {
                consumed += match.id
                if (match.userModified) {
                    // Keep the user's edited row untouched, only re-affirming its subscription linkage.
                    match.copy(subscriptionId = id, group = ServerGroup.SUBSCRIPTION)
                } else {
                    // Adopt the fresh config but preserve stable identity + user-facing state.
                    fresh.copy(
                        id = match.id,
                        pingMs = match.pingMs,
                        isFavorite = match.isFavorite,
                        frontProxyId = match.frontProxyId,
                        customGroup = match.customGroup,
                        userModified = false
                    )
                }
            } else {
                fresh
            }
        }

        // Servers that disappeared from the feed are removed, UNLESS the user edited them.
        existing.filter { it.id !in consumed && !it.userModified }
            .forEach { serverDao.delete(it.id) }

        serverDao.upsertAll(merged.map { it.toEntity() })
        subscriptionDao.upsert(sub.copy(lastUpdated = System.currentTimeMillis(), serverCount = parsed.size).toEntity())
        return ImportResult(true, parsed.size, detail.summaryMessage())
    }

    /** Deterministic fallback identity for merge matching when the raw URI changed. */
    private fun addrKey(s: Server): String = "${s.host}:${s.port}|${s.protocol.name}"

    override suspend fun deleteSubscription(id: String) {
        serverDao.deleteBySubscription(id)
        subscriptionDao.delete(id)
    }

    override suspend fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        subscriptionDao.setEnabled(id, enabled)
    }

    override suspend fun renameSubscription(id: String, name: String) {
        if (name.isNotBlank()) subscriptionDao.rename(id, name.trim())
    }

    override suspend fun updateSubscriptionUrl(id: String, url: String) {
        if (url.isNotBlank()) subscriptionDao.updateUrl(id, url.trim())
    }

    override suspend fun deleteAll(ids: List<String>) {
        if (ids.isNotEmpty()) serverDao.deleteAll(ids)
    }

    override suspend fun setFavoriteAll(ids: List<String>, favorite: Boolean) {
        if (ids.isNotEmpty()) serverDao.setFavoriteAll(ids, favorite)
    }

    override suspend fun setCustomGroup(ids: List<String>, group: String?) {
        if (ids.isNotEmpty()) serverDao.setCustomGroupAll(ids, group?.trim()?.takeIf { it.isNotBlank() })
    }

    override fun exportUri(server: Server): String = ProxyParser.toUri(server)

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "v2rayez/2.0").build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
