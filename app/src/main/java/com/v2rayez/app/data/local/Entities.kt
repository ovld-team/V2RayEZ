package com.v2rayez.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ServerGroup

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val protocol: Protocol,
    val transport: String,
    val security: String,
    val sni: String,
    val address: String,
    val pingMs: Int,
    val signal: Int,
    val group: ServerGroup,
    val isFavorite: Boolean,
    val host: String,
    val port: Int,
    val uuid: String,
    val password: String,
    val method: String,
    val ssPlugin: String = "",
    val ssPluginOptions: String = "",
    val alterId: Int,
    val flow: String,
    val network: String,
    val headerType: String,
    val path: String,
    val requestHost: String,
    val streamSecurity: String,
    val alpn: String,
    val fingerprint: String,
    val allowInsecure: Boolean,
    val publicKey: String,
    val shortId: String,
    val spiderX: String,
    val subscriptionId: String?,
    val rawUri: String,
    val frontProxyId: String? = null,
    /** True once the user edited this subscription-owned server in place; refresh must not overwrite it. */
    val userModified: Boolean = false,
    val sortOrder: Int = 0,
    /** User-defined group name; servers with the same value render as their own section. */
    val customGroup: String? = null,
    /** Per-server core override; SYSTEM follows app default. */
    val preferredCore: String = "SYSTEM",
    // --- v0.9.71 P7 protocol params (SSH / WireGuard / DNS-tunnel / Psiphon) ---
    val sshUser: String = "",
    val sshPrivateKey: String = "",
    val sshHostKey: String = "",
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    /** Comma-joined CIDRs, e.g. "10.0.0.2/32,fd00::2/128". */
    val wgLocalAddresses: String = "",
    /** Comma-joined peer routes from AllowedIPs. */
    val wgAllowedIps: String = "0.0.0.0/0,::/0",
    /** Comma-joined reserved bytes, e.g. "12,34,56". */
    val wgReserved: String = "",
    val wgMtu: Int = 0,
    val dnsTunnelDomain: String = "",
    val dnsTunnelPubKey: String = "",
    val dnsTunnelResolver: String = "",
    val dnsTunnelMode: String = "doh",
    val psiphonConfig: String = ""
)

/**
 * List-safe Room projection for [ServerDao.observeAll] — omits large text blobs
 * (rawUri, SSH/WG keys, Psiphon JSON) that blow Android's ~2 MiB CursorWindow
 * when hundreds of subscription servers are observed at once.
 */
data class ServerListEntity(
    val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val protocol: Protocol,
    val transport: String,
    val security: String,
    val sni: String,
    val address: String,
    val pingMs: Int,
    val signal: Int,
    val group: ServerGroup,
    val isFavorite: Boolean,
    val host: String,
    val port: Int,
    val uuid: String,
    val password: String,
    val method: String,
    val ssPlugin: String = "",
    val ssPluginOptions: String = "",
    val alterId: Int,
    val flow: String,
    val network: String,
    val headerType: String,
    val path: String,
    val requestHost: String,
    val streamSecurity: String,
    val alpn: String,
    val fingerprint: String,
    val allowInsecure: Boolean,
    val publicKey: String,
    val shortId: String,
    val spiderX: String,
    val subscriptionId: String?,
    val frontProxyId: String? = null,
    val userModified: Boolean = false,
    val sortOrder: Int = 0,
    val customGroup: String? = null,
    val preferredCore: String = "SYSTEM",
    val sshUser: String = "",
    val wgLocalAddresses: String = "",
    val wgAllowedIps: String = "0.0.0.0/0,::/0",
    val wgReserved: String = "",
    val wgMtu: Int = 0,
    val dnsTunnelDomain: String = "",
    val dnsTunnelPubKey: String = "",
    val dnsTunnelResolver: String = "",
    val dnsTunnelMode: String = "doh"
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val lastUpdated: Long,
    val serverCount: Int
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val serverName: String,
    val countryCode: String,
    val protocol: String,
    val downBytes: Long,
    val upBytes: Long,
    val startedAt: Long,
    val endedAt: Long
)

/**
 * Per-day traffic totals so charts survive app restarts and reflect real history
 * instead of the in-memory current-process week. Keyed by epoch-day (days since
 * 1970-01-01, local time).
 */
@Entity(tableName = "daily_traffic")
data class DailyTrafficEntity(
    @PrimaryKey val dateEpochDay: Long,
    val downBytes: Long,
    val upBytes: Long
)
