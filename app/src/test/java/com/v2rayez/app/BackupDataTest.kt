package com.v2rayez.app

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.BackupData
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.model.WarpConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `backup round trip preserves subscriptions`() {
        val backup = BackupData(
            settings = AppSettings(),
            servers = listOf("vless://manual@example.test:443"),
            subscriptions = listOf(
                Subscription(
                    id = "sub-1",
                    name = "Private feed",
                    url = "https://example.test/private-token"
                )
            ),
            subscriptionServers = mapOf(
                "sub-1" to listOf("vless://subscription@example.test:443")
            )
        )

        val restored = json.decodeFromString(
            BackupData.serializer(),
            json.encodeToString(BackupData.serializer(), backup)
        )

        assertEquals(2, restored.version)
        assertEquals(backup.subscriptions, restored.subscriptions)
        assertEquals(backup.servers, restored.servers)
        assertEquals(backup.subscriptionServers, restored.subscriptionServers)
    }

    @Test
    fun `version one backup remains readable without subscriptions`() {
        val restored = json.decodeFromString(
            BackupData.serializer(),
            """{"version":1,"settings":{},"servers":[]}"""
        )

        assertEquals(1, restored.version)
        assertTrue(restored.subscriptions.isEmpty())
    }

    @Test
    fun `credential detection covers portable secrets`() {
        assertFalse(BackupData(settings = AppSettings()).containsSensitiveCredentials())
        assertTrue(
            BackupData(
                settings = AppSettings(warp = WarpConfig(privateKey = "private"))
            ).containsSensitiveCredentials()
        )
        assertTrue(
            BackupData(
                settings = AppSettings(),
                subscriptions = listOf(Subscription("id", "name", "https://feed.test/token"))
            ).containsSensitiveCredentials()
        )
    }
}
