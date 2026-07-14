package com.v2rayez.app.domain.model

/** Supported proxy protocols shown in the Servers filter row. */
enum class Protocol(val label: String) {
    VLESS("VLESS"),
    VMESS("VMESS"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),

    // --- v0.9.71 P7 protocols (import + runtime via downloaded core / addon pack) ---
    /** Generic WireGuard peer (non-WARP). Runtime: downloaded sing-box `wireguard` endpoint. */
    WIREGUARD("WireGuard"),
    /** Generic SSH tunnel. Runtime: downloaded sing-box `ssh` outbound. */
    SSH("SSH"),
    /** DNS tunnel (dnstt). Runtime: `dnstunnel` addon pack process → local SOCKS → hev TUN. */
    DNSTUNNEL("DNS Tunnel"),
    /** Psiphon circumvention profile. Runtime: `psiphon` addon pack console client → SOCKS → hev. */
    PSIPHON("Psiphon");

    /**
     * True when this protocol cannot run on the in-process Xray AAR and must use the downloaded
     * sing-box process core (WireGuard endpoint / SSH outbound). [DNSTUNNEL] and [PSIPHON] have
     * their own process engines and are handled separately (see [usesStandaloneEngine]).
     */
    fun requiresSingBox(): Boolean = this == WIREGUARD || this == SSH

    /** True when the protocol is driven by a dedicated addon-pack process engine, not a proxy core. */
    fun usesStandaloneEngine(): Boolean = this == DNSTUNNEL || this == PSIPHON
}

/** Grouping used by the Servers screen chips. */
enum class ServerGroup(val label: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    SUBSCRIPTION("Subscription"),
    MANUAL("Manual")
}

/** A single proxy server / config entry with full connection parameters. */
data class Server(
    val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val protocol: Protocol,
    /** Display transport label (e.g. "WS", "gRPC", "TCP"). */
    val transport: String,
    /** Display security label (e.g. "TLS", "Reality", "None"). */
    val security: String,
    val sni: String,
    /** Display "host:port" summary. */
    val address: String,
    val pingMs: Int,
    /** 0..4 signal strength bars. */
    val signal: Int,
    val group: ServerGroup,
    val isFavorite: Boolean = false,

    // --- Real connection parameters (used by ConfigBuilder) ---
    val host: String = "",
    val port: Int = 443,
    /** VLESS/VMESS id. */
    val uuid: String = "",
    /** Trojan / Shadowsocks password. */
    val password: String = "",
    /** Shadowsocks cipher method. */
    val method: String = "",
    /** SIP003 Shadowsocks plugin name, e.g. `v2ray-plugin` or `obfs-local`. */
    val ssPlugin: String = "",
    /** SIP003 semicolon-delimited plugin options (without the plugin name). */
    val ssPluginOptions: String = "",
    /** VMESS alterId (0 for AEAD). */
    val alterId: Int = 0,
    /** VLESS xtls flow (e.g. "xtls-rprx-vision"). */
    val flow: String = "",
    /** Xray network: tcp/ws/grpc/h2/httpupgrade/quic/kcp. */
    val network: String = "tcp",
    /** tcp headerType (e.g. "none", "http"). */
    val headerType: String = "none",
    /** ws/h2 path or grpc serviceName. */
    val path: String = "",
    /** ws/h2 Host header (comma separated allowed). */
    val requestHost: String = "",
    /** TLS security kind: none/tls/reality. */
    val streamSecurity: String = "",
    val alpn: String = "",
    /** uTLS fingerprint (e.g. "chrome"). */
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    /** Reality public key. */
    val publicKey: String = "",
    /** Reality shortId. */
    val shortId: String = "",
    /** Reality spiderX. */
    val spiderX: String = "",

    // --- SSH (Protocol.SSH — sing-box `ssh` outbound) ---
    /** SSH login user. */
    val sshUser: String = "",
    /** PEM-encoded SSH private key (optional; password auth used when blank). */
    val sshPrivateKey: String = "",
    /** Optional pinned SSH host public key (`ssh-ed25519 AAAA…`). */
    val sshHostKey: String = "",

    // --- WireGuard (Protocol.WIREGUARD — sing-box `wireguard` endpoint) ---
    /** Client WireGuard private key (base64). */
    val wgPrivateKey: String = "",
    /** Peer (server) public key (base64). */
    val wgPeerPublicKey: String = "",
    /** Optional pre-shared key (base64). */
    val wgPreSharedKey: String = "",
    /** Assigned interface addresses, e.g. "10.0.0.2/32". */
    val wgLocalAddresses: List<String> = emptyList(),
    /** Peer routes preserved from WireGuard `AllowedIPs` / share links. */
    val wgAllowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    /** Optional 3-byte Cloudflare-style reserved bytes. */
    val wgReserved: List<Int> = emptyList(),
    /** WireGuard MTU (0 = core default). */
    val wgMtu: Int = 0,

    // --- DNS tunnel (Protocol.DNSTUNNEL — dnstt addon pack) ---
    /** Tunnel domain, e.g. "t.example.com". */
    val dnsTunnelDomain: String = "",
    /** dnstt server public key (hex). */
    val dnsTunnelPubKey: String = "",
    /** Upstream resolver: a DoH URL, a DoT host, or a plain UDP resolver ip:port. */
    val dnsTunnelResolver: String = "",
    /** Resolver transport: "doh" / "dot" / "udp". */
    val dnsTunnelMode: String = "doh",

    // --- Psiphon (Protocol.PSIPHON — psiphon-tunnel-core console client) ---
    /** Psiphon config JSON (Propagation/Sponsor ids + embedded/remote server list). */
    val psiphonConfig: String = "",

    /** Owning subscription id (null for manual). */
    val subscriptionId: String? = null,
    /** True once the user has edited this (subscription-owned) server in place, so a
     *  subscription refresh must not overwrite it. */
    val userModified: Boolean = false,
    /** Original share URI, if imported from a link. */
    val rawUri: String = "",
    /** Optional chain hop: id of another server to dial through (this server -> front -> internet). */
    val frontProxyId: String? = null,
    /** User-defined group name; null means ungrouped. */
    val customGroup: String? = null,
    /**
     * Which proxy core to use for this server.
     * [CorePreference.SYSTEM] follows [AppSettings.defaultCore].
     */
    val preferredCore: CorePreference = CorePreference.SYSTEM
)

/** Connection lifecycle. */
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

/** Live connection snapshot used by Home. */
data class ConnectionState(
    val status: ConnectionStatus,
    val server: Server?,
    val uptimeSeconds: Long,
    val downloadLabel: String,
    val uploadLabel: String,
    val pingMs: Int,
    val speedLabel: String,
    /** Cumulative session totals, pre-formatted (e.g. "12.4 MB"). */
    val sessionDownLabel: String = "0 B",
    val sessionUpLabel: String = "0 B",
    /** Last connection failure reason, shown on Home; cleared on connect. */
    val errorMessage: String? = null,
    /**
     * True when [errorMessage] is caused by a missing on-demand pack/core binary (sing-box,
     * Psiphon, DNS tunnel, ByeDPI, …) rather than a bad server config — Home shows an
     * "Open Core manager" CTA instead of just the raw text. Locale-independent (not inferred
     * from the translated error string).
     */
    val needsCoreManager: Boolean = false,
    /**
     * True when the OS started this VPN session via the system "Always-on VPN" setting
     * (queried via VpnService.isAlwaysOn on API 29+). Only meaningful while connected.
     */
    val alwaysOn: Boolean = false,
    /** True when the OS "Block connections without VPN" (lockdown) is active for this session. */
    val lockdown: Boolean = false
)

/** One bucket in a bar/line traffic chart. */
data class TrafficPoint(
    val label: String,
    val download: Float,
    val upload: Float
)

/** One ~1 Hz sample of instantaneous throughput (bytes/sec) for the live Home graph. */
data class ThroughputSample(
    val downBps: Long,
    val upBps: Long
)

enum class LogLevel(val label: String) {
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
    DEBUG("Debug")
}

data class LogEntry(
    val id: String,
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val detail: String? = null,
    /** Which subsystem emitted this line — see [LogTags]. Null for untagged/legacy entries. */
    val tag: String? = null
)

/** Stable subsystem tags for [LogEntry.tag] — drives the Logs screen tag filter/badge. */
object LogTags {
    const val VPN = "VPN"
    const val TOR = "Tor"
    const val MITM = "MITM"
    const val CORE = "Core"
    const val DOWNLOAD = "Download"
    const val ROUTING = "Routing"
    const val FREE = "Free"

    val ALL = listOf(VPN, TOR, MITM, CORE, DOWNLOAD, ROUTING, FREE)
}

/** Home "Recent Activity" row and generic timeline events. */
data class ActivityItem(
    val id: String,
    val title: String,
    val time: String,
    val type: ActivityType
)

enum class ActivityType { CONNECTED, DURATION, DOWNLOAD, UPLOAD }

/** Statistics: top server usage row. */
data class TopServer(
    val name: String,
    val countryCode: String,
    val usageLabel: String,
    val fraction: Float
)

/** Statistics: data usage donut slice. */
data class UsageSlice(
    val label: String,
    val value: Float,
    val valueLabel: String
)

/** Time window used by the Statistics screen filter. */
enum class StatsRange(val label: String) {
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    ALL("All Time");

    /** Epoch millis lower bound for this range (0 for ALL). */
    fun since(now: Long = System.currentTimeMillis()): Long = when (this) {
        TODAY -> now - 24L * 60 * 60 * 1000
        WEEK -> now - 7L * 24 * 60 * 60 * 1000
        MONTH -> now - 30L * 24 * 60 * 60 * 1000
        ALL -> 0L
    }
}

/** Statistics: pre-formatted summary totals for the tiles. */
data class StatsTotals(
    val totalDownload: String,
    val totalUpload: String,
    val averageSpeed: String,
    val averagePing: String
)

/** Crypto donation option on the Donate screen. */
data class CryptoDonation(
    val symbol: String,
    val network: String,
    val address: String,
    val shortAddress: String,
    /** Remote coin-logo URL (PNG/SVG) rendered in the badge. */
    val iconUrl: String = "",
    /** Emoji glyph fallback shown if [iconUrl] fails to load. */
    val emoji: String = ""
)

/** A Tools screen entry (network tool or advanced item). */
data class ToolItem(
    val id: String,
    val title: String,
    val subtitle: String
)
