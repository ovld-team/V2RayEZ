package com.v2rayez.app.data.fronting

import com.v2rayez.app.domain.model.DomainFrontConfig
import com.v2rayez.app.domain.model.Server
import java.net.Inet4Address
import java.net.InetAddress

/**
 * For real-SNI-preserved fronting, the TCP target must accept TLS for the server's SNI.
 * Generic Cloudflare IPs (e.g. 104.19.229.21) reject unrelated hostnames with a TLS alert.
 * Resolve the server host/SNI so the dialer reaches an IP that can complete the handshake,
 * while fake-SNI probes + ClientHello fragmentation still run on that socket path.
 */
object FrontAddressResolver {

    data class ResolvedFronts(
        val primary: String,
        val fallback: String,
        val sourceHost: String
    )

    /**
     * Prefer IPv4 A records for [server.sni] then [server.host].
     * Returns null when DNS yields nothing usable (caller keeps configured fronts).
     */
    fun resolveForServer(server: Server): ResolvedFronts? {
        val host = server.sni.trim().ifBlank { server.host.trim() }
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

    /** Overlay DNS-resolved fronts onto [config] for this connect attempt (not persisted). */
    fun withResolvedFronts(config: DomainFrontConfig, server: Server): Pair<DomainFrontConfig, String?> {
        val resolved = resolveForServer(server) ?: return config to null
        val note = "front IP from $resolved.sourceHost → ${resolved.primary}" +
            if (resolved.fallback.isNotEmpty()) " (fallback ${resolved.fallback})" else ""
        val next = config.copy(
            frontAddress = resolved.primary,
            fallbackAddress = resolved.fallback.ifBlank {
                // Keep a secondary only if it differs from primary; otherwise leave empty
                // so we don't bounce to an unrelated default CF IP.
                ""
            }
        )
        return next to note
    }

    private fun looksLikeIp(value: String): Boolean {
        if (value.count { it == '.' } == 3) {
            return value.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return value.contains(':') // rough IPv6
    }
}
