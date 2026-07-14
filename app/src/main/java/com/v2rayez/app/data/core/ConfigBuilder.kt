package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.DesyncMode
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.RoutingMode
import com.v2rayez.app.domain.model.RuleOutbound
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.WarpConfig
import com.v2rayez.app.domain.model.WarpMode
import com.v2rayez.app.domain.model.tunDnsEffectiveSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generates an Xray-core JSON configuration string from a [Server] and global [AppSettings].
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM.
 */
object ConfigBuilder {

    const val TAG_PROXY = "proxy"
    const val TAG_PROXY2 = "proxy2"
    const val TAG_WARP = "warp"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_TOR = "tor"
    const val TAG_FRAGMENT = "fragment"
    const val TAG_BYEDPI = "byedpi"
    const val TAG_DNS = "dns-out"

    private val json = Json { prettyPrint = false }

    /**
     * Validate the connection-critical fields of [server] before handing the config
     * to the core. Returns a user-actionable error message, or null when valid.
     * (The core's own errors are generic — e.g. "failed to parse json config".)
     */
    fun validate(server: Server): String? = when {
        // Psiphon is not an ip:port server — its reachability lives entirely in the config blob.
        server.protocol == Protocol.PSIPHON ->
            if (server.psiphonConfig.isBlank())
                "Psiphon config is missing — import a psiphon:// profile or paste the Psiphon JSON."
            else null
        server.host.isBlank() -> "Server address is missing — edit the server."
        server.protocol == Protocol.DNSTUNNEL -> when {
            server.dnsTunnelDomain.isBlank() -> "DNS tunnel domain is missing — edit the server."
            server.dnsTunnelPubKey.isBlank() -> "DNS tunnel public key is missing — edit the server."
            else -> null
        }
        server.port !in 1..65535 -> "Server port ${server.port} is invalid — edit the server."
        server.protocol == Protocol.TROJAN && server.password.isBlank() ->
            "Trojan password is missing — edit the server and set a password."
        server.protocol == Protocol.SHADOWSOCKS && server.password.isBlank() ->
            "Shadowsocks password is missing — edit the server and set a password."
        server.protocol == Protocol.SHADOWSOCKS && server.method.isBlank() ->
            "Shadowsocks cipher method is missing — edit the server."
        (server.protocol == Protocol.VLESS || server.protocol == Protocol.VMESS) && server.uuid.isBlank() ->
            "${server.protocol.label} UUID is missing — edit the server and set the ID."
        server.protocol == Protocol.WIREGUARD && server.wgPrivateKey.isBlank() ->
            "WireGuard private key is missing — edit the server."
        server.protocol == Protocol.WIREGUARD && server.wgPeerPublicKey.isBlank() ->
            "WireGuard peer public key is missing — edit the server."
        server.protocol == Protocol.SSH && server.password.isBlank() && server.sshPrivateKey.isBlank() ->
            "SSH needs a password or a private key — edit the server."
        resolvedStreamSecurity(server) == "reality" && server.publicKey.isBlank() ->
            "Reality public key (pbk) is missing — edit or re-import the server."
        resolvedStreamSecurity(server) == "reality" && !isValidRealityPublicKey(server.publicKey) ->
            "Reality public key (pbk) is invalid — expected a 32-byte X25519 base64 key."
        server.network.lowercase() in setOf("quic", "kcp", "mkcp") ->
            "${server.network} transport is not supported by this build; choose TCP, WS, gRPC, HTTP/2, XHTTP, or HTTPUpgrade."
        else -> null
    }

    /** Reject user rules Xray cannot parse instead of failing later with a generic core error. */
    fun validateRouting(settings: AppSettings): String? {
        settings.routing.rules.filter { it.enabled }.forEachIndexed { index, rule ->
            val label = rule.remark.ifBlank { "Routing rule ${index + 1}" }
            if (rule.domains.isEmpty() && rule.ips.isEmpty() &&
                rule.port.isBlank() && rule.protocol.isEmpty()
            ) {
                return "$label has no matcher — add a domain, IP, port, or protocol."
            }
            if (rule.port.isNotBlank() && !isValidPortList(rule.port)) {
                return "$label has an invalid port expression: ${rule.port}"
            }
        }
        return null
    }

    private fun isValidPortList(value: String): Boolean =
        value.split(',').all { token ->
            val parts = token.trim().split('-')
            when (parts.size) {
                1 -> parts[0].toIntOrNull() in 1..65535
                2 -> {
                    val first = parts[0].toIntOrNull()
                    val last = parts[1].toIntOrNull()
                    first != null && last != null && first in 1..65535 &&
                        last in 1..65535 && first <= last
                }
                else -> false
            }
        }

    private fun isValidRealityPublicKey(value: String): Boolean {
        val cleaned = value.trim().replace('-', '+').replace('_', '/')
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        return runCatching { java.util.Base64.getDecoder().decode(padded).size == 32 }.getOrDefault(false)
    }

    /**
     * Build an Xray config. When [frontServer] is non-null the traffic is chained:
     * [server] dials through [frontServer] (server -> front -> internet), like v2rayN's
     * forwarding proxy.
     *
     * @param desyncRunning true only when the byedpi SOCKS engine is actually listening;
     *   never emit `dialerProxy: byedpi` for a dead port.
     * @param domainFrontRunning true when the UAC-style local fronting dialer is listening;
     *   Xray then dials `domainFront.listenHost:listenPort` while keeping the real TLS SNI.
     * @param geositeAvailable false when geosite.dat is not installed (the v0.9.50 APK ships
     *   only the mini cn+private geoip; the full dats are on-demand downloads). Every
     *   `geosite:*` matcher is then dropped so Xray never fails to start on a missing file.
     *   `geoip:private` / `geoip:cn` stay unconditional — the mini geoip always backs them.
     *   Defaults to false (fail-safe): a call site that forgets to pass GeoAssetManager's state
     *   degrades to gated-off geo rules instead of a core that refuses to start.
     */
    fun build(
        server: Server,
        settings: AppSettings,
        frontServer: Server? = null,
        desyncRunning: Boolean = false,
        domainFrontRunning: Boolean = false,
        includeTun: Boolean = true,
        geositeAvailable: Boolean = false
    ): String {
        val effectiveSettings = settings.tunDnsEffectiveSettings()
        val obj = buildJsonObject {
            put("log", logBlock(effectiveSettings))
            // Enable outbound traffic accounting so queryStats("proxy","uplink"/"downlink")
            // returns real byte counters instead of always 0.
            putJsonObject("stats") {}
            putJsonObject("policy") {
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }
            put("dns", dnsBlock(effectiveSettings, geositeAvailable))
            if (effectiveSettings.dns.enableFakeDns) put("fakedns", fakednsBlock())
            put("inbounds", inbounds(effectiveSettings, includeTun = includeTun))
            put("outbounds", outbounds(server, effectiveSettings, frontServer, desyncRunning, domainFrontRunning))
            put("routing", routing(effectiveSettings, domainFrontRunning, geositeAvailable))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Build a clean config for latency measurement. Tor routing, WARP, fragment, byedpi
     * desync, domain fronting, mux, and global SNI spoof/omit / cert pins are
     * force-disabled so the measured delay reflects the target [server] itself.
     * Global SNI spoof breaks TLS/REALITY probes (VLESS/VMESS/Trojan) while SS
     * (`security=none`) still succeeds — that was the "only Shadowsocks pings" bug.
     */
    fun buildForTest(
        server: Server,
        settings: AppSettings,
        frontServer: Server? = null,
        geositeAvailable: Boolean = false
    ): String {
        val clean = settings.copy(
            enableMux = false,
            tor = settings.tor.copy(enabled = false),
            warp = settings.warp.copy(enabled = false),
            fragment = settings.fragment.copy(enabled = false),
            desync = settings.desync.copy(enabled = false),
            domainFront = settings.domainFront.copy(enabled = false),
            sni = settings.sni.copy(spoofEnabled = false, omitEnabled = false),
            tls = settings.tls.copy(pinnedSha256 = emptyList()),
            // Drop any custom rule that would divert the probe through Tor.
            routing = settings.routing.copy(
                rules = settings.routing.rules.filter { it.outbound != RuleOutbound.TOR }
            )
        )
        // Socks/HTTP only — no tun-in. measureOutboundDelay must not bind a TUN fd.
        return build(
            server = server,
            settings = clean,
            frontServer = frontServer,
            desyncRunning = false,
            domainFrontRunning = false,
            includeTun = false,
            geositeAvailable = geositeAvailable
        )
    }

    /**
     * True when IP matcher [entry] can resolve against the packaged mini geoip (cn + private)
     * alone — i.e. a literal CIDR/IP or `geoip:cn` / `geoip:private`. Any other `geoip:*` tag
     * needs the full geoip.dat, so it is dropped until the geo pack is installed.
     */
    private fun keepGeoipOffline(entry: String): Boolean {
        val e = entry.trim()
        if (!e.startsWith("geoip:", ignoreCase = true)) return true // literal ip / cidr
        val tag = e.substringAfter(':').removePrefix("!").lowercase()
        return tag == "cn" || tag == "private"
    }

    /** Xray FakeDNS pool: hands out reserved 198.18/15 IPs that sniffing maps back to SNI. */
    private fun fakednsBlock(): JsonArray = buildJsonArray {
        addJsonObject {
            put("ipPool", "198.18.0.0/15")
            put("poolSize", 65535)
        }
    }

    private fun logBlock(settings: AppSettings): JsonObject = buildJsonObject {
        put("loglevel", if (settings.reduceData) "error" else "warning")
    }

    private fun dnsBlock(settings: AppSettings, geositeAvailable: Boolean): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            if (settings.tor.enabled) {
                // Xray's string DNS form is an address, not a host:port endpoint on all bundled
                // core versions. Emit the explicit object form so queries really reach Tor's
                // loopback DNSPort instead of treating "127.0.0.1:9053" as a resolver name.
                addJsonObject {
                    put("address", "127.0.0.1")
                    put("port", settings.tor.dnsPort.coerceIn(1, 65535))
                }
            } else {
                // FakeDNS first so sniffing-enabled connections resolve to the fake pool and
                // routing/SNI can be applied by domain.
                if (settings.dns.enableFakeDns) add("fakedns")
                // Prefer plain IP/UDP resolvers so DNS does not depend on a live proxy+DoH path.
                add(normalizeDnsServer(settings.dns.remoteDns.ifBlank { "1.1.1.1" }))
            }
            // The domestic split needs geosite:cn to scope which domains it answers —
            // without geosite.dat the whole entry is skipped (remote DNS handles everything).
            if (settings.dns.domesticDns.isNotBlank() && geositeAvailable) {
                addJsonObject {
                    put("address", normalizeDnsServer(settings.dns.domesticDns))
                    putJsonArray("domains") { add("geosite:cn") }
                    putJsonArray("expectIPs") { add("geoip:cn") }
                }
            }
        }
        if (settings.dns.hosts.isNotEmpty()) {
            putJsonObject("hosts") {
                settings.dns.hosts.forEach { put(it.domain, it.value) }
            }
        }
        // Avoid AAAA lookups when IPv6 is off so DoH results stay routable.
        put("queryStrategy", if (settings.enableIpv6) "UseIP" else "UseIPv4")
    }

    /** If the user stored a DoH URL, fall back to the host IP/hostname for UDP DNS safety. */
    private fun normalizeDnsServer(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return trimmed
        }
        val host = trimmed.substringAfter("://").substringBefore("/").substringBefore(":")
        return host.ifBlank { "1.1.1.1" }
    }

    /** True when the DNS traffic must be intercepted and resolved via the Xray DNS handler. */
    private fun dnsHandled(settings: AppSettings): Boolean =
        settings.enableLocalDns || settings.dns.enableFakeDns

    private fun inbounds(settings: AppSettings, includeTun: Boolean = true): JsonArray = buildJsonArray {
        // Required for VpnService: libv2ray sets xray.tun.fd, but Xray only consumes it when
        // a tun inbound exists. Without this, the UI shows Connected while Chrome has no path.
        if (includeTun) {
            addJsonObject {
                put("tag", "tun-in")
                put("protocol", "tun")
                put("port", 0)
                putJsonObject("settings") {
                    put("name", "xray0")
                    put("mtu", settings.mtu.coerceIn(1280, 1400))
                    // Explicit gVisor stack — preferred for Android VPN fds across API levels.
                    put("stack", "gvisor")
                }
                putSniffing(settings)
            }
        }
        // When LAN sharing (or allowLan) is on, bind to 0.0.0.0 so hotspot/LAN clients
        // can use this device as a SOCKS/HTTP proxy.
        val listen = if (settings.allowLan || settings.enableLanSharing) "0.0.0.0" else "127.0.0.1"
        addJsonObject {
            put("tag", "socks-in")
            put("protocol", "socks")
            put("listen", listen)
            put("port", settings.socksPort)
            putJsonObject("settings") {
                put("udp", true)
                put("auth", "noauth")
            }
            putSniffing(settings)
        }
        // HTTP inbound so clients that only support HTTP proxies can share the tunnel.
        addJsonObject {
            put("tag", "http-in")
            put("protocol", "http")
            put("listen", listen)
            put("port", settings.httpPort)
            putSniffing(settings)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSniffing(settings: AppSettings) {
        if (!settings.enableSniffing) return
        putJsonObject("sniffing") {
            put("enabled", true)
            putJsonArray("destOverride") {
                add("http"); add("tls"); add("quic")
                if (settings.dns.enableFakeDns) add("fakedns")
            }
        }
    }

    private fun outbounds(
        server: Server,
        settings: AppSettings,
        frontServer: Server?,
        desyncRunning: Boolean,
        domainFrontRunning: Boolean
    ): JsonArray = buildJsonArray {
        val fronting = domainFrontRunning && settings.domainFront.enabled
        // Full-device Tor owns catch-all; a selected server is chained over Tor. Fronting is
        // rejected by V2RayVpnService because its protected local dialer bypasses Tor.
        val warp = settings.warp.takeIf {
            it.enabled && it.configured && !settings.tor.enabled && !fronting
        }
        // Domain fronting owns ClientHello fragmentation on the wire — do not stack
        // byedpi / Xray fragment dialers on the same hop (matches UAC ProxyService).
        val desyncOn = !fronting && !settings.tor.enabled && desyncRunning &&
            settings.desync.enabled && settings.desync.mode != DesyncMode.NONE
        val fragmentOn = !fronting && !settings.tor.enabled && !desyncOn && settings.fragment.enabled
        val rawDialer = when {
            desyncOn -> TAG_BYEDPI
            fragmentOn -> TAG_FRAGMENT
            else -> null
        }
        val dialHost = if (fronting) {
            settings.domainFront.listenHost.ifBlank { "127.0.0.1" }
        } else null
        val dialPort = if (fronting) settings.domainFront.listenPort else null

        if (warp != null && warp.mode == WarpMode.OUTBOUND) {
            // All proxy traffic exits directly through WARP (WireGuard is the primary proxy).
            add(wireguardOutbound(warp, TAG_PROXY, rawDialer))
        } else {
            // Main outbound. It may dial through WARP (front), another server (chain),
            // the local domain-front dialer, or the raw desync/fragment dialer.
            val mainDialer = when {
                fronting -> null
                settings.tor.enabled && !settings.tor.routeAllDevice -> TAG_TOR
                warp != null && warp.mode == WarpMode.FRONT -> TAG_WARP
                frontServer != null -> TAG_PROXY2
                else -> rawDialer
            }
            add(proxyOutbound(server, settings, TAG_PROXY, mainDialer, dialHost, dialPort))
            if (!fronting && warp != null && warp.mode == WarpMode.FRONT) {
                add(wireguardOutbound(warp, TAG_WARP, rawDialer))
            } else if (!fronting && frontServer != null) {
                add(proxyOutbound(frontServer, settings, TAG_PROXY2, rawDialer, null, null))
            }
        }
        addJsonObject {
            put("tag", TAG_DIRECT)
            put("protocol", "freedom")
            putJsonObject("settings") { put("domainStrategy", "UseIP") }
        }
        addJsonObject {
            put("tag", TAG_BLOCK)
            put("protocol", "blackhole")
            putJsonObject("settings") {
                putJsonObject("response") { put("type", "http") }
            }
        }
        // Emit the Tor SOCKS outbound when Tor is the global route OR any custom routing
        // rule sends its matched traffic through Tor.
        val torRuleUsed = settings.routing.rules.any { it.enabled && it.outbound == com.v2rayez.app.domain.model.RuleOutbound.TOR }
        if (settings.tor.enabled || torRuleUsed) {
            addJsonObject {
                put("tag", TAG_TOR)
                put("protocol", "socks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", settings.tor.socksHost)
                            put("port", settings.tor.socksPort)
                        }
                    }
                }
            }
        }
        if (fragmentOn) {
            addJsonObject {
                put("tag", TAG_FRAGMENT)
                put("protocol", "freedom")
                putJsonObject("settings") {
                    putJsonObject("fragment") {
                        put("packets", settings.fragment.packets)
                        put("length", settings.fragment.length)
                        put("interval", settings.fragment.interval)
                    }
                }
            }
        }
        // byedpi only when the engine is confirmed running (never dial a dead SOCKS port).
        if (desyncOn) {
            addJsonObject {
                put("tag", TAG_BYEDPI)
                put("protocol", "socks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", settings.desync.socksHost)
                            put("port", settings.desync.socksPort)
                        }
                    }
                }
            }
        }
        // DNS handler outbound: port-53 traffic is routed here (see routing()) so DoH /
        // FakeDNS defined in the dns block actually resolve app queries.
        if (dnsHandled(settings)) {
            addJsonObject {
                put("tag", TAG_DNS)
                put("protocol", "dns")
            }
        }
    }

    private fun proxyOutbound(
        server: Server,
        settings: AppSettings,
        tag: String,
        dialerTag: String?,
        dialHost: String?,
        dialPort: Int?
    ): JsonObject = buildJsonObject {
        put("tag", tag)
        val address = dialHost?.takeIf { it.isNotBlank() } ?: server.host
        val port = dialPort?.takeIf { it in 1..65535 } ?: server.port
        when (server.protocol) {
            Protocol.VMESS -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", address)
                            put("port", port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", server.uuid)
                                    put("alterId", server.alterId)
                                    put("security", server.method.ifBlank { "auto" })
                                }
                            }
                        }
                    }
                }
            }
            Protocol.VLESS -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", address)
                            put("port", port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", server.uuid)
                                    put("encryption", server.method.ifBlank { "none" })
                                    if (server.flow.isNotBlank()) put("flow", server.flow)
                                }
                            }
                        }
                    }
                }
            }
            Protocol.TROJAN -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", address)
                            put("port", port)
                            put("password", server.password)
                            if (server.flow.isNotBlank()) put("flow", server.flow)
                        }
                    }
                }
            }
            Protocol.SHADOWSOCKS -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", address)
                            put("port", port)
                            put("method", server.method)
                            put("password", server.password)
                        }
                    }
                }
            }
            Protocol.WIREGUARD -> {
                // Xray also speaks WireGuard; emit a generic peer outbound so a WG server still
                // works if the user pins Xray. (Default path forces sing-box — see requiresSingBox.)
                put("protocol", "wireguard")
                putJsonObject("settings") {
                    put("secretKey", server.wgPrivateKey)
                    putJsonArray("address") { server.wgLocalAddresses.forEach { add(it) } }
                    if (server.wgMtu > 0) put("mtu", server.wgMtu)
                    if (server.wgReserved.isNotEmpty()) putJsonArray("reserved") { server.wgReserved.forEach { add(it) } }
                    putJsonArray("peers") {
                        addJsonObject {
                            put("publicKey", server.wgPeerPublicKey)
                            if (server.wgPreSharedKey.isNotBlank()) put("preSharedKey", server.wgPreSharedKey)
                            put("endpoint", "$address:$port")
                            putJsonArray("allowedIPs") {
                                server.wgAllowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") }
                                    .forEach { add(it) }
                            }
                        }
                    }
                }
            }
            // SSH / DNS-tunnel / Psiphon have no Xray outbound — they always run on sing-box or a
            // dedicated engine (V2RayVpnService forces the core). This blackhole is a safety net so
            // a mis-routed config is inert rather than leaking traffic in the clear.
            Protocol.SSH, Protocol.DNSTUNNEL, Protocol.PSIPHON -> {
                put("protocol", "blackhole")
                putJsonObject("settings") {}
            }
        }
        // WireGuard/SSH/DNS/Psiphon have no Xray stream layer — a tcp/udp streamSettings block
        // would be rejected by the wireguard outbound and is meaningless for the blackhole net.
        val hasStreamLayer = when (server.protocol) {
            Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS -> true
            else -> false
        }
        if (hasStreamLayer) put("streamSettings", streamSettings(server, settings, dialerTag))
        // Mux + local domain-front TCP hop is unreliable; mux + XTLS Vision is invalid.
        val frontingHop = dialHost != null && dialPort != null
        val visionFlow = server.flow.contains("vision", ignoreCase = true)
        if (hasStreamLayer && settings.enableMux && tag == TAG_PROXY && !frontingHop && !visionFlow) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", settings.muxConcurrency.coerceIn(1, 1024))
            }
        }
    }

    /** Cloudflare WARP / WireGuard outbound (Xray-core `wireguard` protocol). */
    private fun wireguardOutbound(warp: WarpConfig, tag: String, dialerTag: String?): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", "wireguard")
        putJsonObject("settings") {
            put("secretKey", warp.privateKey)
            putJsonArray("address") { warp.addresses.forEach { add(it) } }
            put("mtu", warp.mtu)
            if (warp.reserved.isNotEmpty()) putJsonArray("reserved") { warp.reserved.forEach { add(it) } }
            putJsonArray("peers") {
                addJsonObject {
                    put("publicKey", warp.peerPublicKey)
                    put("endpoint", warp.endpoint)
                    putJsonArray("allowedIPs") { add("0.0.0.0/0"); add("::/0") }
                }
            }
        }
        if (dialerTag != null) {
            putJsonObject("streamSettings") {
                putJsonObject("sockopt") { put("dialerProxy", dialerTag) }
            }
        }
    }

    /**
     * Resolve stream security when the URI omitted `security=`. Shadowsocks is plaintext;
     * Trojan defaults to TLS; VLESS/VMESS use REALITY when a public key is present, else
     * TLS when an SNI/host hint exists.
     */
    private fun resolvedStreamSecurity(server: Server): String {
        val raw = server.streamSecurity.trim().lowercase()
        if (raw.isNotEmpty()) return raw
        return when (server.protocol) {
            Protocol.SHADOWSOCKS -> "none"
            Protocol.TROJAN -> "tls"
            Protocol.VLESS, Protocol.VMESS -> when {
                server.publicKey.isNotBlank() -> "reality"
                server.sni.isNotBlank() || server.fingerprint.isNotBlank() -> "tls"
                else -> "none"
            }
            // WireGuard/SSH/DNS-tunnel/Psiphon do not use Xray stream TLS (they run on sing-box
            // or a dedicated engine); never emit a TLS/REALITY block for them.
            Protocol.WIREGUARD, Protocol.SSH, Protocol.DNSTUNNEL, Protocol.PSIPHON -> "none"
        }
    }

    /**
     * xReality hardening: when a TLS/REALITY server omits `fp`, present a modern uTLS
     * ClientHello (`chrome`) instead of Go's default fingerprint, which is easily flagged by
     * DPI. Applied uniformly by [streamSettings] and [SingBoxConfigBuilder].
     */
    fun effectiveFingerprint(server: Server, security: String): String {
        if (server.fingerprint.isNotBlank()) return server.fingerprint
        return if (security == "tls" || security == "xtls" || security == "reality") "chrome" else ""
    }

    private fun streamSettings(server: Server, settings: AppSettings, dialerTag: String?): JsonObject = buildJsonObject {
        val net = server.network.ifBlank { "tcp" }
        put("network", net)
        val security = resolvedStreamSecurity(server)
        put("security", if (security == "none") "" else security)

        // Real TLS SNI from the server URI. Domain fronting must NOT swap this — the local
        // dialer uses a separate fake SNI only for probe ClientHellos (UAC model).
        val sni = settings.sni
        val spoofSni = sni.spoofEnabled && sni.spoofDomain.isNotBlank()
        val tlsServerName = when {
            spoofSni -> sni.spoofDomain
            sni.omitEnabled -> ""
            else -> server.sni.ifBlank { server.host }
        }
        val allowInsecure = server.allowInsecure || settings.tls.allowInsecure || spoofSni
        when (security) {
            "tls", "xtls" -> putJsonObject("tlsSettings") {
                put("serverName", tlsServerName)
                put("allowInsecure", allowInsecure)
                effectiveFingerprint(server, security).takeIf { it.isNotBlank() }
                    ?.let { put("fingerprint", it) }
                if (server.alpn.isNotBlank()) putJsonArray("alpn") {
                    server.alpn.split(",").forEach { add(it.trim()) }
                }
                val pins = settings.tls.pinnedSha256.map { it.trim() }.filter { it.isNotEmpty() }
                if (pins.isNotEmpty()) {
                    put("pinnedPeerCertSha256", pins.joinToString(","))
                }
            }
            "reality" -> putJsonObject("realitySettings") {
                put("serverName", server.sni.ifBlank { server.host })
                put("publicKey", server.publicKey)
                put("shortId", server.shortId)
                put("spiderX", server.spiderX)
                // REALITY requires a uTLS fingerprint; default to chrome when the URI omitted fp.
                put("fingerprint", effectiveFingerprint(server, "reality"))
            }
        }

        val realHost = server.requestHost.ifBlank {
            server.sni.ifBlank { "" }
        }
        when (net) {
            "ws" -> putJsonObject("wsSettings") {
                put("path", server.path.ifBlank { "/" })
                if (realHost.isNotBlank()) putJsonObject("headers") { put("Host", realHost) }
            }
            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                put("path", server.path.ifBlank { "/" })
                if (realHost.isNotBlank()) put("host", realHost)
            }
            "grpc" -> putJsonObject("grpcSettings") {
                put("serviceName", server.path)
            }
            "xhttp" -> putJsonObject("xhttpSettings") {
                put("path", server.path.ifBlank { "/" })
                if (realHost.isNotBlank()) put("host", realHost)
            }
            "h2", "http" -> putJsonObject("httpSettings") {
                put("path", server.path.ifBlank { "/" })
                if (realHost.isNotBlank()) putJsonArray("host") { add(realHost) }
            }
            else -> putJsonObject("tcpSettings") {
                putJsonObject("header") { put("type", server.headerType.ifBlank { "none" }) }
            }
        }

        if (dialerTag != null) {
            putJsonObject("sockopt") { put("dialerProxy", dialerTag) }
        }
    }

    /**
     * Routing rules.
     *
     * Tor coexistence:
     * - **Full-device** (`tor.routeAllDevice`): DNS and loopback first, QUIC blocked, then
     *   TCP catch-all → `tor`; unsupported residual UDP is blocked instead of sent to Tor SOCKS.
     * - **Selected server** (`tor.enabled` without route-all): routing defaults to `proxy`, whose
     *   dialer is the Tor outbound. This preserves VLESS/VMESS/Trojan/SS instead of bypassing the
     *   selected server with an early Tor catch-all.
     */
    private fun routing(
        settings: AppSettings,
        domainFrontRunning: Boolean,
        geositeAvailable: Boolean
    ): JsonObject = buildJsonObject {
        val r = settings.routing
        val torFullDevice = settings.tor.enabled && settings.tor.routeAllDevice
        put("domainStrategy", r.domainStrategy)
        putJsonArray("rules") {
            // DNS must win first-match over Tor catch-all: Tor SOCKS does not carry UDP DNS.
            if (dnsHandled(settings)) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_DNS)
                    put("port", "53")
                }
            } else {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_DIRECT)
                    put("port", "53")
                    putJsonArray("network") { add("udp") }
                }
            }
            // Tor's local SOCKS and DNS listeners must never be captured by the VPN route.
            if (settings.tor.enabled) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_DIRECT)
                    putJsonArray("ip") {
                        add("127.0.0.1")
                        add("::1")
                    }
                }
            }
            // Block ads (needs geosite.dat — silently unavailable until the geo pack is installed).
            if (r.blockAds && geositeAvailable) addJsonObject {
                put("type", "field")
                put("outboundTag", TAG_BLOCK)
                putJsonArray("domain") { add("geosite:category-ads-all") }
            }
            // Chrome/HTTP3 QUIC often fails through TUN proxies → "connected but no internet".
            addJsonObject {
                put("type", "field")
                put("outboundTag", TAG_BLOCK)
                putJsonArray("protocol") { add("quic") }
            }
            // Full-device Tor catch-all must come after DNS and QUIC. Tor SOCKS is TCP-only;
            // sending UDP to it is a silent blackhole, while blocking QUIC makes HTTPS retry TCP.
            if (torFullDevice) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_TOR)
                    putJsonArray("network") { add("tcp") }
                }
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_BLOCK)
                    putJsonArray("network") { add("udp") }
                }
            }
            if (r.mode == RoutingMode.RULE) {
                if (r.bypassLan) addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_DIRECT)
                    putJsonArray("ip") { add("geoip:private") }
                }
                if (r.bypassMainland) {
                    // Domain half needs geosite.dat; the IP half always works (mini geoip has cn).
                    if (geositeAvailable) addJsonObject {
                        put("type", "field")
                        put("outboundTag", TAG_DIRECT)
                        putJsonArray("domain") { add("geosite:cn") }
                    }
                    addJsonObject {
                        put("type", "field")
                        put("outboundTag", TAG_DIRECT)
                        putJsonArray("ip") { add("geoip:cn") }
                    }
                }
                // Iran split: geosite:ir + geoip:ir both live only in the full geo databases
                // (the packaged mini geoip ships cn + private only), so gate the whole block on
                // geositeAvailable. When the pack is missing the UI surfaces a download CTA.
                if (r.bypassIran && geositeAvailable) {
                    addJsonObject {
                        put("type", "field")
                        put("outboundTag", TAG_DIRECT)
                        putJsonArray("domain") { add("geosite:ir") }
                    }
                    addJsonObject {
                        put("type", "field")
                        put("outboundTag", TAG_DIRECT)
                        putJsonArray("ip") { add("geoip:ir") }
                    }
                }
            }
            // Custom user rules. Without the full geo databases (geositeAvailable=false, the
            // v0.9.50 default until the geo pack is installed) any matcher that needs them is
            // stripped: every `geosite:*`, plus `geoip:*` tags outside the packaged mini set
            // (only cn + private ship offline). Xray AND-combines a rule's matcher fields, so
            // silently dropping one broadens the rule (a `{geosite:cn, port:443}→DIRECT` rule
            // would leak all port-443 traffic). Any rule that loses a geo matcher is therefore
            // dropped in full — it falls back to default routing until the geo pack installs.
            r.rules.filter { it.enabled }.forEach { rule ->
                // Defensive fallback for legacy/imported settings. New edits are rejected by
                // validateRouting(), but an empty field rule must never reach Xray.
                if (rule.domains.isEmpty() && rule.ips.isEmpty() &&
                    rule.port.isBlank() && rule.protocol.isEmpty()
                ) return@forEach
                if (!geositeAvailable) {
                    val geoStripped = rule.domains.any { it.startsWith("geosite:", ignoreCase = true) } ||
                        rule.ips.any { !keepGeoipOffline(it) }
                    if (geoStripped) return@forEach
                }
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", rule.outbound.tag)
                    if (rule.domains.isNotEmpty()) putJsonArray("domain") { rule.domains.forEach { add(it) } }
                    if (rule.ips.isNotEmpty()) putJsonArray("ip") { rule.ips.forEach { add(it) } }
                    if (rule.port.isNotBlank()) put("port", rule.port)
                    if (rule.protocol.isNotEmpty()) putJsonArray("protocol") { rule.protocol.forEach { add(it) } }
                }
            }
            // Direct mode: everything else goes direct.
            if (r.mode == RoutingMode.DIRECT) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", TAG_DIRECT)
                    putJsonArray("network") { add("tcp"); add("udp") }
                }
            }
        }
    }
}
