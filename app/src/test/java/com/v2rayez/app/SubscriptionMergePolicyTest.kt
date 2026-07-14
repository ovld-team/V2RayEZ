package com.v2rayez.app

import com.v2rayez.app.data.repository.planSubscriptionMerge
import com.v2rayez.app.data.repository.parseSubscriptionPayload
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMergePolicyTest {
    private fun server(
        id: String,
        host: String = "example.com",
        port: Int = 443,
        name: String = id,
        favorite: Boolean = false,
        ping: Int = -1,
        userModified: Boolean = false
    ) = Server(
        id = id,
        name = name,
        country = "Unknown",
        countryCode = "UN",
        protocol = Protocol.VLESS,
        transport = "TCP",
        security = "TLS",
        sni = host,
        address = "$host:$port",
        pingMs = ping,
        signal = 3,
        group = ServerGroup.SUBSCRIPTION,
        isFavorite = favorite,
        host = host,
        port = port,
        subscriptionId = "sub",
        userModified = userModified
    )

    @Test
    fun refreshPreservesStableIdentityAndUserFacingState() {
        val old = server("stable", favorite = true, ping = 42).copy(customGroup = "Fast")
        val fresh = server("stable", name = "Renamed upstream", favorite = false, ping = -1)

        val plan = planSubscriptionMerge(listOf(old), listOf(fresh), "sub")

        assertEquals("stable", plan.servers.single().id)
        assertEquals("Renamed upstream", plan.servers.single().name)
        assertTrue(plan.servers.single().isFavorite)
        assertEquals(42, plan.servers.single().pingMs)
        assertEquals("Fast", plan.servers.single().customGroup)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun addressFallbackHandlesChangedDeterministicId() {
        val old = server("old-id", favorite = true)
        val fresh = server("new-id", name = "Updated")

        val merged = planSubscriptionMerge(listOf(old), listOf(fresh), "sub").servers.single()

        assertEquals("old-id", merged.id)
        assertEquals("Updated", merged.name)
        assertTrue(merged.isFavorite)
    }

    @Test
    fun userModifiedRowsSurviveRefreshAndFeedRemoval() {
        val edited = server("edited", name = "My name", userModified = true)
        val upstream = server("edited", name = "Upstream name")

        val matched = planSubscriptionMerge(listOf(edited), listOf(upstream), "sub")
        assertEquals("My name", matched.servers.single().name)
        assertTrue(matched.servers.single().userModified)

        val removed = planSubscriptionMerge(listOf(edited), emptyList(), "sub")
        assertFalse(removed.deleteIds.contains("edited"))
    }

    @Test
    fun vanishedUnmodifiedRowsAreDeletedButNewRowsAreInserted() {
        val vanished = server("gone", host = "gone.example")
        val added = server("new", host = "new.example")

        val plan = planSubscriptionMerge(listOf(vanished), listOf(added), "sub")

        assertEquals(listOf("gone"), plan.deleteIds)
        assertEquals(listOf("new"), plan.servers.map { it.id })
    }

    @Test
    fun subscriptionPayloadImportsPlainLinksWithOwnership() {
        val body =
            "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?security=tls&type=ws#Node"

        val result = parseSubscriptionPayload(body, ServerGroup.SUBSCRIPTION, "sub-1")

        assertEquals(1, result.servers.size)
        assertEquals("sub-1", result.servers.single().subscriptionId)
        assertEquals(ServerGroup.SUBSCRIPTION, result.servers.single().group)
    }

    @Test
    fun subscriptionPayloadRoutesClashYamlThroughYamlParser() {
        val body = """
            proxies:
              - name: Clash node
                type: vless
                server: example.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                tls: true
        """.trimIndent()

        val result = parseSubscriptionPayload(body, ServerGroup.SUBSCRIPTION, "sub-yaml")

        assertEquals(1, result.servers.size)
        assertEquals("Clash node", result.servers.single().name)
        assertEquals("sub-yaml", result.servers.single().subscriptionId)
    }
}
