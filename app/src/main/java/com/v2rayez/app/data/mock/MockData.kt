package com.v2rayez.app.data.mock

import com.v2rayez.app.domain.model.ActivityItem
import com.v2rayez.app.domain.model.ActivityType
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.CryptoDonation
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.ToolItem
import com.v2rayez.app.domain.model.TopServer
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.model.UsageSlice

/** Static sample data so every screen renders without a backend. */
object MockData {

    val servers: List<Server> = listOf(
        Server(
            id = "jp-tokyo-01", name = "Japan - Tokyo - 01", country = "Japan", countryCode = "JP",
            protocol = Protocol.VLESS, transport = "WS", security = "TLS", sni = "example.com",
            address = "jp1.example.com:443", pingMs = 42, signal = 4,
            group = ServerGroup.FAVORITES, isFavorite = true
        ),
        Server(
            id = "de-frankfurt-02", name = "Germany - Frankfurt - 02", country = "Germany", countryCode = "DE",
            protocol = Protocol.VMESS, transport = "gRPC", security = "TLS", sni = "de.example.com",
            address = "de2.example.com:443", pingMs = 64, signal = 4,
            group = ServerGroup.SUBSCRIPTION
        ),
        Server(
            id = "us-newyork-03", name = "United States - New York - 03", country = "United States", countryCode = "US",
            protocol = Protocol.TROJAN, transport = "TCP", security = "TLS", sni = "us.example.com",
            address = "us3.example.com:443", pingMs = 78, signal = 3,
            group = ServerGroup.SUBSCRIPTION
        ),
        Server(
            id = "sg-singapore-01", name = "Singapore - Singapore - 01", country = "Singapore", countryCode = "SG",
            protocol = Protocol.VLESS, transport = "WS", security = "TLS", sni = "sg.example.com",
            address = "sg1.example.com:443", pingMs = 55, signal = 4,
            group = ServerGroup.MANUAL
        ),
        Server(
            id = "fr-paris-01", name = "France - Paris - 01", country = "France", countryCode = "FR",
            protocol = Protocol.SHADOWSOCKS, transport = "TCP", security = "None", sni = "fr.example.com",
            address = "fr1.example.com:443", pingMs = 61, signal = 3,
            group = ServerGroup.MANUAL
        ),
        Server(
            id = "uk-london-01", name = "United Kingdom - London - 01", country = "United Kingdom", countryCode = "GB",
            protocol = Protocol.VMESS, transport = "WS", security = "TLS", sni = "uk.example.com",
            address = "uk1.example.com:443", pingMs = 71, signal = 3,
            group = ServerGroup.SUBSCRIPTION
        ),
        Server(
            id = "ca-toronto-01", name = "Canada - Toronto - 01", country = "Canada", countryCode = "CA",
            protocol = Protocol.VLESS, transport = "gRPC", security = "TLS", sni = "ca.example.com",
            address = "ca1.example.com:443", pingMs = 88, signal = 2,
            group = ServerGroup.MANUAL
        )
    )

    val connectionState = ConnectionState(
        status = ConnectionStatus.CONNECTED,
        server = servers.first(),
        uptimeSeconds = 942, // 00:15:42
        downloadLabel = "125.6 MB",
        uploadLabel = "32.4 MB",
        pingMs = 42,
        speedLabel = "86.7 Mbps"
    )

    val weeklyTraffic: List<TrafficPoint> = listOf(
        TrafficPoint("Mon", 0.42f, 0.18f),
        TrafficPoint("Tue", 0.60f, 0.24f),
        TrafficPoint("Wed", 0.35f, 0.14f),
        TrafficPoint("Thu", 0.78f, 0.30f),
        TrafficPoint("Fri", 0.52f, 0.22f),
        TrafficPoint("Sat", 0.90f, 0.38f),
        TrafficPoint("Sun", 0.66f, 0.28f)
    )

    val liveThroughput: List<ThroughputSample> = (0 until 40).map { i ->
        val phase = i / 6.0
        ThroughputSample(
            downBps = (2_500_000 + 2_000_000 * kotlin.math.sin(phase)).toLong().coerceAtLeast(0),
            upBps = (600_000 + 500_000 * kotlin.math.sin(phase + 1.2)).toLong().coerceAtLeast(0)
        )
    }

    val recentActivity: List<ActivityItem> = listOf(
        ActivityItem("1", "Connected to Japan - Tokyo - 01", "9:41 AM", ActivityType.CONNECTED),
        ActivityItem("2", "Connection duration", "9:41 AM", ActivityType.DURATION),
        ActivityItem("3", "Downloaded", "9:41 AM", ActivityType.DOWNLOAD),
        ActivityItem("4", "Uploaded", "9:41 AM", ActivityType.UPLOAD)
    )

    val topServers: List<TopServer> = listOf(
        TopServer("Japan - Tokyo - 01", "JP", "1.25 GB", 1.0f),
        TopServer("United States - New York - 03", "US", "512 MB", 0.62f),
        TopServer("Germany - Frankfurt - 02", "DE", "256 MB", 0.34f)
    )

    val dataUsage: List<UsageSlice> = listOf(
        UsageSlice("Download", 1.25f, "1.25 GB"),
        UsageSlice("Upload", 0.50f, "512 MB"),
        UsageSlice("Others", 0.25f, "256 MB")
    )

    val logs: List<LogEntry> = listOf(
        LogEntry("1", "09:41:12", LogLevel.INFO, "Connection established", "Japan - Tokyo - 01"),
        LogEntry("2", "09:41:10", LogLevel.INFO, "TLS Handshake success"),
        LogEntry("3", "09:41:09", LogLevel.WARNING, "High latency detected", "Ping: 142 ms"),
        LogEntry("4", "09:41:08", LogLevel.ERROR, "Failed to resolve host"),
        LogEntry("5", "09:41:05", LogLevel.INFO, "Reconnecting..."),
        LogEntry("6", "09:41:03", LogLevel.INFO, "Connection closed"),
        LogEntry("7", "09:41:01", LogLevel.DEBUG, "Sent: 125.6 MB"),
        LogEntry("8", "09:41:00", LogLevel.DEBUG, "Received: 32.4 MB")
    )

    val networkTools: List<ToolItem> = listOf(
        ToolItem("sni", "SNI Tunnel", "Bypass DPI"),
        ToolItem("fronting", "Domain Fronting", "MITM fronting"),
        ToolItem("tor", "Tor", "Anonymity Network"),
        ToolItem("bp8", "Subscriptions", "Manage subscriptions"),
        ToolItem("cert", "Certificates", "SSL / TLS"),
        ToolItem("dns", "DNS", "DNS over HTTPS"),
        ToolItem("diag", "Diagnostics", "Network Info")
    )

    val advancedTools: List<ToolItem> = listOf(
        ToolItem("snifront", "SNI Front Dialer", "Fake SNI dialer"),
        ToolItem("routing", "Routing", "Manage routing rules"),
        ToolItem("dnsmgr", "DNS Manager", "Custom DNS configuration"),
        ToolItem("hosts", "Hosts", "Edit hosts file"),
        ToolItem("appproxy", "App Proxy", "Per-app proxy settings")
    )

    val donations: List<CryptoDonation> = listOf(
        CryptoDonation("USDT", "TRC20", "TJ12x...qJs9", "TJ12x...qJs9"),
        CryptoDonation("BTC", "Bitcoin (BTC)", "bc1q5...s0k3", "bc1q5...s0k3"),
        CryptoDonation("ETH", "Ethereum (ETH)", "0x8a3...f06d", "0x8a3...f06d"),
        CryptoDonation("TON", "Ton (TON)", "UQDr...3Gjx", "UQDr...3Gjx")
    )
}
