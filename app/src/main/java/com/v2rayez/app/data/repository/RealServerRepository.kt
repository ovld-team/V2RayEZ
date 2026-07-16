package com.v2rayez.app.data.repository

import androidx.room.withTransaction
import com.v2rayez.app.data.local.ServerDao
import com.v2rayez.app.data.local.SubscriptionDao
import com.v2rayez.app.data.local.V2RayDatabase
import com.v2rayez.app.data.local.toEntity
import com.v2rayez.app.data.local.toModel
import com.v2rayez.app.data.parser.ClashYamlParser
import com.v2rayez.app.data.net.UrlSafety
import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.BackupSnapshot
import com.v2rayez.app.domain.model.ImportResult
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal data class SubscriptionMergePlan(
    val servers: List<Server>,
    val deleteIds: List<String>
)

/** Pure subscription merge policy, shared by Room-backed refreshes and JVM contract tests. */
internal fun planSubscriptionMerge(
    existing: List<Server>,
    fresh: List<Server>,
    subscriptionId: String
): SubscriptionMergePlan {
    val existingById = existing.associateBy { it.id }
    val existingByAddress = existing.associateBy(::subscriptionAddressKey)
    val consumed = mutableSetOf<String>()
    val merged = fresh.map { incoming ->
        val match = existingById[incoming.id] ?: existingByAddress[subscriptionAddressKey(incoming)]
        if (match == null) {
            incoming
        } else {
            consumed += match.id
            if (match.userModified) {
                match.copy(subscriptionId = subscriptionId, group = ServerGroup.SUBSCRIPTION)
            } else {
                incoming.copy(
                    id = match.id,
                    pingMs = match.pingMs,
                    isFavorite = match.isFavorite,
                    frontProxyId = match.frontProxyId,
                    customGroup = match.customGroup,
                    userModified = false
                )
            }
        }
    }
    return SubscriptionMergePlan(
        servers = merged,
        deleteIds = existing.filter { it.id !in consumed && !it.userModified }.map { it.id }
    )
}

private fun subscriptionAddressKey(server: Server): String =
    "${server.host}:${server.port}|${server.protocol.name}"

internal fun parseSubscriptionPayload(
    body: String,
    group: ServerGroup,
    subscriptionId: String? = null
): ProxyParser.ParseManyResult {
    if (!ClashYamlParser.looksLikeYaml(body)) {
        return ProxyParser.parseManyDetailed(body, group, subscriptionId)
    }
    val servers = ClashYamlParser.parse(body, group, subscriptionId)
    return ProxyParser.ParseManyResult(
        servers = servers,
        totalLinks = servers.size,
        skippedUnsupported = 0,
        skippedMalformed = 0,
        unsupportedSchemes = emptyMap()
    )
}

@Singleton
class RealServerRepository @Inject constructor(
    private val database: V2RayDatabase,
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

    override suspend fun updatePing(id: String, pingMs: Int) = serverDao.setPing(id, pingMs)

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
        val detail = parseBody(text, group = ServerGroup.MANUAL)
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
        return parseBody(body, group = ServerGroup.MANUAL).servers
    }

    override suspend fun addSubscription(name: String, url: String): ImportResult {
        val trimmedUrl = url.trim()
        // Dedupe: re-adding the same URL used to create a second subscription row with a fresh
        // random id (and therefore a second, independent copy of every server). Refresh the
        // existing row instead so "Add" is idempotent for the common re-paste/re-scan case.
        subscriptionDao.getAll().map { it.toModel() }.firstOrNull { it.url.trim() == trimmedUrl }?.let { existing ->
            val result = refreshSubscription(existing.id)
            return result.copy(
                message = "Already subscribed (\"${existing.name}\") — refreshed. ${result.message}".trim()
            )
        }
        val id = UUID.randomUUID().toString()
        val body = fetch(url) ?: return ImportResult(false, 0, "Failed to fetch subscription")
        val detail = parseBody(body, group = ServerGroup.SUBSCRIPTION, subscriptionId = id)
        if (detail.servers.isEmpty()) {
            return ImportResult(false, 0, detail.summaryMessage())
        }
        database.withTransaction {
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
        }
        return ImportResult(true, detail.servers.size, detail.summaryMessage())
    }

    override suspend fun refreshSubscription(id: String): ImportResult {
        val sub = subscriptionDao.getById(id)?.toModel() ?: return ImportResult(false, 0, "Subscription not found")
        val body = fetch(sub.url) ?: return ImportResult(false, 0, "Failed to fetch subscription")
        val detail = parseBody(body, group = ServerGroup.SUBSCRIPTION, subscriptionId = id)
        val parsed = detail.servers
        // Never wipe the existing servers on a transient/empty/unsupported response.
        if (parsed.isEmpty()) return ImportResult(false, 0, detail.summaryMessage())

        database.withTransaction {
            // MERGE rather than delete+reinsert so pings, favorites, and in-place user edits survive.
            val existing = serverDao.getBySubscription(id).map { it.toModel() }
            val plan = planSubscriptionMerge(existing, parsed, id)

            // Servers that disappeared from the feed are removed, UNLESS the user edited them.
            plan.deleteIds.forEach { serverDao.delete(it) }

            serverDao.upsertAll(plan.servers.map { it.toEntity() })
            subscriptionDao.upsert(
                sub.copy(lastUpdated = System.currentTimeMillis(), serverCount = parsed.size).toEntity()
            )
        }
        return ImportResult(true, parsed.size, detail.summaryMessage())
    }

    override suspend fun deleteSubscription(id: String) {
        database.withTransaction {
            serverDao.deleteBySubscription(id)
            subscriptionDao.delete(id)
        }
    }

    override suspend fun backupSnapshot(): BackupSnapshot = database.withTransaction {
        BackupSnapshot(
            servers = serverDao.getAllFull().map { it.toModel() },
            subscriptions = subscriptionDao.getAll().map { it.toModel() }
        )
    }

    override suspend fun restoreBackup(
        subscriptions: List<Subscription>,
        manualUris: List<String>,
        subscriptionServers: Map<String, List<String>>
    ): ImportResult {
        val knownSubscriptionIds = subscriptions.mapTo(mutableSetOf()) { it.id }
        if (!knownSubscriptionIds.containsAll(subscriptionServers.keys)) {
            return ImportResult(false, 0, "Backup contains orphaned subscription servers")
        }
        // Parse each entry independently: WireGuard share configs can legitimately contain newlines.
        val manualServers = manualUris.flatMap { uri ->
            ProxyParser.parseMany(uri, group = ServerGroup.MANUAL)
        }
        val ownedServers = subscriptionServers.flatMap { (subscriptionId, uris) ->
            uris.flatMap { uri ->
                ProxyParser.parseMany(
                    uri,
                    group = ServerGroup.SUBSCRIPTION,
                    subscriptionId = subscriptionId
                )
            }
        }
        val expectedCount = manualUris.size + subscriptionServers.values.sumOf { it.size }
        val restored = manualServers + ownedServers
        if (restored.size != expectedCount) {
            return ImportResult(false, 0, "Backup contains invalid server credentials")
        }

        database.withTransaction {
            subscriptions.forEach { subscriptionDao.upsert(it.toEntity()) }
            if (restored.isNotEmpty()) serverDao.upsertAll(restored.map { it.toEntity() })
        }
        return ImportResult(true, restored.size, "Backup database restored")
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

    override suspend fun exportUris(ids: List<String>): String =
        ids.mapNotNull { id -> getServer(id)?.let { exportUri(it) } }
            .joinToString("\n")
            .trim()

    private fun parseBody(
        body: String,
        group: ServerGroup,
        subscriptionId: String? = null
    ): ProxyParser.ParseManyResult = parseSubscriptionPayload(body, group, subscriptionId)

    /**
     * SSRF-guarded, size-capped subscription fetch. Redirects are followed manually (client-level
     * auto-redirect is disabled for this call) so every hop — not just the original URL — is
     * re-validated against [UrlSafety] before it's ever connected to.
     */
    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val redirectFreeClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                UrlSafety.assertSafe(current)
                val request = Request.Builder().url(current).header("User-Agent", "v2rayez/2.0").build()
                redirectFreeClient.newCall(request).execute().use { resp ->
                    if (resp.code in REDIRECT_CODES) {
                        val location = resp.header("Location") ?: return@withContext null
                        current = resp.request.url.resolve(location)?.toString() ?: return@withContext null
                        return@use
                    }
                    if (!resp.isSuccessful) return@withContext null
                    val contentLength = resp.header("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength > MAX_SUBSCRIPTION_BYTES) {
                        return@withContext null
                    }
                    val body = resp.body ?: return@withContext null
                    return@withContext UrlSafety.readBounded(body.byteStream(), MAX_SUBSCRIPTION_BYTES)
                }
            }
            null // exhausted redirect budget
        }.getOrNull()
    }

    companion object {
        /** Subscription bodies are proxy-link text, not media — 5 MiB is generous headroom. */
        private const val MAX_SUBSCRIPTION_BYTES = 5 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
