package com.v2rayez.app

import com.v2rayez.app.data.work.SubscriptionRefresher
import com.v2rayez.app.domain.model.BackupSnapshot
import com.v2rayez.app.domain.model.ImportResult
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Framework-free fake — only [subscriptions] and [refreshSubscription] matter for this test. */
private class FakeServerRepository(
    private val subs: List<Subscription>,
    private val refreshOutcomes: Map<String, ImportResult>
) : ServerRepository {
    val refreshedIds = mutableListOf<String>()

    override fun servers(): Flow<List<Server>> = flowOf(emptyList())
    override fun subscriptions(): Flow<List<Subscription>> = flowOf(subs)
    override suspend fun getServer(id: String): Server? = null
    override suspend fun toggleFavorite(id: String) {}
    override suspend fun upsert(server: Server) {}
    override suspend fun updatePing(id: String, pingMs: Int) {}
    override suspend fun delete(id: String) {}
    override suspend fun duplicate(id: String): Server? = null
    override suspend fun importFromText(text: String): ImportResult = ImportResult(false, 0)
    override suspend fun previewFromUrl(url: String): List<Server> = emptyList()
    override suspend fun addSubscription(name: String, url: String): ImportResult = ImportResult(false, 0)
    override suspend fun refreshSubscription(id: String): ImportResult {
        refreshedIds += id
        return refreshOutcomes[id] ?: ImportResult(false, 0, "no fixture for $id")
    }
    override suspend fun deleteSubscription(id: String) {}
    override suspend fun backupSnapshot(): BackupSnapshot = BackupSnapshot(emptyList(), emptyList())
    override suspend fun restoreBackup(
        subscriptions: List<Subscription>,
        manualUris: List<String>,
        subscriptionServers: Map<String, List<String>>
    ): ImportResult = ImportResult(false, 0)
    override suspend fun setSubscriptionEnabled(id: String, enabled: Boolean) {}
    override suspend fun renameSubscription(id: String, name: String) {}
    override suspend fun updateSubscriptionUrl(id: String, url: String) {}
    override suspend fun deleteAll(ids: List<String>) {}
    override suspend fun setFavoriteAll(ids: List<String>, favorite: Boolean) {}
    override suspend fun setCustomGroup(ids: List<String>, group: String?) {}
    override fun exportUri(server: Server): String = ""
    override suspend fun exportUris(ids: List<String>): String = ""
}

class SubscriptionRefresherTest {

    private fun sub(id: String, enabled: Boolean) =
        Subscription(id = id, name = id, url = "https://example.invalid/$id", enabled = enabled)

    @Test
    fun refreshEnabledSkipsDisabledSubscriptions() = runBlocking {
        val repo = FakeServerRepository(
            subs = listOf(sub("a", enabled = true), sub("b", enabled = false)),
            refreshOutcomes = mapOf("a" to ImportResult(true, 5))
        )
        val summary = SubscriptionRefresher.refreshEnabled(repo)

        assertEquals(listOf("a"), repo.refreshedIds)
        assertEquals(1, summary.attempted)
        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.failed)
        assertFalse(summary.allFailed)
    }

    @Test
    fun refreshEnabledCountsFailuresWithoutThrowing() = runBlocking {
        val repo = FakeServerRepository(
            subs = listOf(sub("a", enabled = true), sub("b", enabled = true)),
            refreshOutcomes = mapOf(
                "a" to ImportResult(false, 0, "network error"),
                "b" to ImportResult(true, 3)
            )
        )
        val summary = SubscriptionRefresher.refreshEnabled(repo)

        assertEquals(2, summary.attempted)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.failed)
        assertFalse(summary.allFailed)
    }

    @Test
    fun refreshEnabledReportsAllFailedWhenEverySubFails() = runBlocking {
        val repo = FakeServerRepository(
            subs = listOf(sub("a", enabled = true)),
            refreshOutcomes = mapOf("a" to ImportResult(false, 0, "boom"))
        )
        val summary = SubscriptionRefresher.refreshEnabled(repo)

        assertTrue(summary.allFailed)
    }

    @Test
    fun refreshEnabledWithNoSubscriptionsIsNotAllFailed() = runBlocking {
        val repo = FakeServerRepository(subs = emptyList(), refreshOutcomes = emptyMap())
        val summary = SubscriptionRefresher.refreshEnabled(repo)

        assertEquals(0, summary.attempted)
        assertFalse(summary.allFailed)
    }
}
