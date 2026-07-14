package com.v2rayez.app.data.fronting

import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.Server
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Chooses the TCP dial target for [DomainFrontDialer].
 *
 * EasySNI / CDN fronting dials a **front edge IP** (Cloudflare/Google defaults) while TLS
 * ClientHello fragmentation still carries Xray's real SNI. Overwriting those fronts with the
 * origin host breaks CDN fronting.
 *
 * REALITY (and other origin-direct TLS) must dial an IP that accepts the server's TLS identity —
 * usually [Server.host], never a decoy REALITY SNI — or every strategy TLS-alerts.
 */
object FrontAddressResolver {

    data class ResolvedFronts(
        val primary: String,
        val fallback: String,
        val sourceHost: String
    )

    /**
     * Resolve the actual server endpoint, never the TLS SNI/REALITY decoy.
     */
    fun resolveForServer(server: Server): ResolvedFronts? {
        val host = server.host.trim().ifBlank { server.sni.trim() }
        if (host.isEmpty() || looksLikeIp(host)) {
            return if (looksLikeIp(host)) {
                ResolvedFronts(primary = host, fallback = "", sourceHost = host)
            } else {
                null
            }
        }
        val ips = runCatching {
            InetAddress.getAllByName(host)
                .mapNotNull { addr ->
                    when (addr) {
                        is Inet4Address -> addr.hostAddress
                        else -> null // Prefer IPv4 for the Java dialer path
                    }
                }
                .distinct()
                .filter { !it.isNullOrBlank() }
                .map { it!! }
        }.getOrDefault(emptyList())
        if (ips.isEmpty()) return null
        return ResolvedFronts(
            primary = ips[0],
            fallback = ips.getOrNull(1).orEmpty(),
            sourceHost = host
        )
    }

    /** Overlay dial targets onto [config] for this connect attempt (not persisted). */
    fun withResolvedFronts(config: DomainFrontConfig, server: Server): Pair<DomainFrontConfig, String?> {
        // REALITY / origin-direct: must reach the real endpoint (EasySNI passthrough to origin).
        if (needsOriginDial(server)) {
            val resolved = resolveForServer(server) ?: return config to null
            val note = "origin dial ${resolved.sourceHost} → ${resolved.primary}" +
                if (resolved.fallback.isNotEmpty()) " (fallback ${resolved.fallback})" else ""
            val next = config.copy(
                frontAddress = resolved.primary,
                fallbackAddress = resolved.fallback.ifBlank { "" }
            )
            return next to note
        }
        // CDN / EasySNI-style: keep configured Cloudflare (or user) edge IPs.
        val front = config.frontAddress.trim().ifBlank { DomainFrontDialer.DEFAULT_FRONT_ADDRESS }
        val fallback = config.fallbackAddress.trim().ifBlank {
            if (front == DomainFrontDialer.DEFAULT_FRONT_ADDRESS) {
                DomainFrontDialer.DEFAULT_FALLBACK_ADDRESS
            } else {
                ""
            }
        }
        val note = "CDN front $front" +
            if (fallback.isNotEmpty()) " (fallback $fallback)" else "" +
            " / fake-SNI ${config.effectiveFakeSni}"
        return config.copy(frontAddress = front, fallbackAddress = fallback) to note
    }

    /** REALITY must dial the origin; CDN/EasySNI fronts stay on configured edge IPs. */
    internal fun needsOriginDial(server: Server): Boolean {
        val hay = "${server.security} ${server.streamSecurity}".lowercase()
        return hay.contains("reality") ||
            (server.publicKey.isNotBlank() && hay.contains("reality"))
    }

    private fun looksLikeIp(value: String): Boolean {
        if (value.count { it == '.' } == 3) {
            return value.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return value.contains(':') // rough IPv6
    }
}
