package com.v2rayez.app.data.parser

import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * Parses VLESS / VMESS / Trojan / Shadowsocks share links into [Server] models,
 * and serializes them back to share URIs. Pure Kotlin (JVM-testable, no Android deps).
 */
object ProxyParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse a single share link, or null if unsupported / malformed. */
    fun parse(raw: String, group: ServerGroup = ServerGroup.MANUAL, subscriptionId: String? = null): Server? {
        val uri = raw.trim()
        return runCatching {
            when {
                uri.startsWith("vmess://", true) -> parseVmess(uri, group, subscriptionId)
                uri.startsWith("vless://", true) -> parseVless(uri, group, subscriptionId)
                uri.startsWith("trojan://", true) -> parseTrojan(uri, group, subscriptionId)
                uri.startsWith("ss://", true) -> parseShadowsocks(uri, group, subscriptionId)
                uri.startsWith("ssh://", true) -> parseSsh(uri, group, subscriptionId)
                uri.startsWith("wireguard://", true) || uri.startsWith("wg://", true) ->
                    parseWireguard(uri, group, subscriptionId)
                uri.startsWith("dnstt://", true) || uri.startsWith("dns://", true) ->
                    parseDnsTunnel(uri, group, subscriptionId)
                uri.startsWith("psiphon://", true) -> parsePsiphon(uri, group, subscriptionId)
                uri.startsWith("[interface]", true) -> parseWireguardConf(uri, group, subscriptionId)
                else -> null
            }
        }.getOrNull()
    }

    /** Parse many links from pasted text or a (possibly base64) subscription blob. */
    fun parseMany(text: String, group: ServerGroup = ServerGroup.MANUAL, subscriptionId: String? = null): List<Server> {
        return parseManyDetailed(text, group, subscriptionId).servers
    }

    data class ParseManyResult(
        val servers: List<Server>,
        val totalLinks: Int,
        val skippedUnsupported: Int,
        val skippedMalformed: Int,
        val unsupportedSchemes: Map<String, Int>
    ) {
        fun summaryMessage(): String {
            if (servers.isEmpty()) return "No valid servers found"
            if (skippedUnsupported == 0 && skippedMalformed == 0) {
                return "Imported ${servers.size} server(s)"
            }
            val skipBits = buildList {
                if (skippedUnsupported > 0) {
                    val schemes = unsupportedSchemes.entries
                        .sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key}×${it.value}" }
                    add("skipped $skippedUnsupported unsupported ($schemes)")
                }
                if (skippedMalformed > 0) add("skipped $skippedMalformed malformed")
            }
            return "Imported ${servers.size} server(s); ${skipBits.joinToString("; ")}"
        }
    }

    /** Like [parseMany] but also reports unsupported schemes (e.g. hysteria2). */
    fun parseManyDetailed(
        text: String,
        group: ServerGroup = ServerGroup.MANUAL,
        subscriptionId: String? = null
    ): ParseManyResult {
        val raw = text.trim()
        // A pasted WireGuard `.conf` ([Interface]/[Peer]) has no `://` and spans many lines —
        // treat the whole blob as a single server instead of splitting it line-by-line.
        if (raw.contains("[Interface]", ignoreCase = true) && raw.contains("[Peer]", ignoreCase = true)) {
            val wg = runCatching { parseWireguardConf(raw, group, subscriptionId) }.getOrNull()
            return ParseManyResult(
                servers = listOfNotNull(wg),
                totalLinks = 1,
                skippedUnsupported = 0,
                skippedMalformed = if (wg == null) 1 else 0,
                unsupportedSchemes = emptyMap()
            )
        }
        val decoded = tryDecodeBase64(raw)
        val body = if (decoded != null && decoded.contains("://")) decoded else raw
        val links = body.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("://") }
        val servers = mutableListOf<Server>()
        val unsupported = mutableMapOf<String, Int>()
        var malformed = 0
        for (link in links) {
            val scheme = link.substringBefore("://").lowercase()
            when (scheme) {
                "vmess", "vless", "trojan", "ss",
                "ssh", "wireguard", "wg", "dnstt", "dns", "psiphon" -> {
                    val s = parse(link, group, subscriptionId)
                    if (s != null) servers += s else malformed++
                }
                else -> unsupported[scheme] = (unsupported[scheme] ?: 0) + 1
            }
        }
        return ParseManyResult(
            servers = servers,
            totalLinks = links.size,
            skippedUnsupported = unsupported.values.sum(),
            skippedMalformed = malformed,
            unsupportedSchemes = unsupported
        )
    }

    // ---------------------------------------------------------------- VMESS
    private fun parseVmess(uri: String, group: ServerGroup, subId: String?): Server? {
        val payload = uri.removePrefix("vmess://").substringBefore("#").trim()
        val decoded = tryDecodeBase64(payload) ?: return null
        val obj = json.parseToJsonElement(decoded).jsonObject
        fun s(key: String): String = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val host = s("add")
        val port = s("port").toIntOrNull() ?: 443
        val net = s("net").ifBlank { "tcp" }
        val tls = s("tls").ifBlank { "none" }
        val name = s("ps").ifBlank { "$host:$port" }
        return baseServer(
            name = name, protocol = Protocol.VMESS, host = host, port = port,
            network = net, streamSecurity = tls, group = group, subId = subId, rawUri = uri
        ).copy(
            uuid = s("id"),
            alterId = s("aid").toIntOrNull() ?: 0,
            method = s("scy").ifBlank { "auto" },
            headerType = s("type").ifBlank { "none" },
            path = s("path"),
            requestHost = s("host"),
            sni = s("sni").ifBlank { s("host") },
            alpn = s("alpn"),
            fingerprint = s("fp")
        )
    }

    // ---------------------------------------------------------------- VLESS
    private fun parseVless(uri: String, group: ServerGroup, subId: String?): Server {
        val (userInfo, host, port, q, frag, uriPath) = splitStandardUri(uri, "vless://")
        val security = q["security"] ?: "none"
        val net = q["type"] ?: "tcp"
        return baseServer(
            name = frag.ifBlank { "$host:$port" }, protocol = Protocol.VLESS, host = host, port = port,
            network = net, streamSecurity = security, group = group, subId = subId, rawUri = uri
        ).copy(
            uuid = userInfo,
            flow = q["flow"].orEmpty(),
            method = q["encryption"] ?: "none",
            headerType = q["headerType"] ?: "none",
            path = q["path"] ?: (q["serviceName"] ?: uriPath),
            requestHost = q["host"].orEmpty(),
            sni = q["sni"] ?: (q["host"] ?: host),
            alpn = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            allowInsecure = q["allowInsecure"] == "1"
        )
    }

    // ---------------------------------------------------------------- Trojan
    private fun parseTrojan(uri: String, group: ServerGroup, subId: String?): Server {
        val (userInfo, host, port, q, frag, uriPath) = splitStandardUri(uri, "trojan://")
        val security = q["security"] ?: "tls"
        val net = q["type"] ?: "tcp"
        return baseServer(
            name = frag.ifBlank { "$host:$port" }, protocol = Protocol.TROJAN, host = host, port = port,
            network = net, streamSecurity = security, group = group, subId = subId, rawUri = uri
        ).copy(
            password = userInfo,
            path = q["path"] ?: (q["serviceName"] ?: uriPath),
            requestHost = q["host"].orEmpty(),
            sni = q["sni"] ?: (q["peer"] ?: host),
            alpn = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            flow = q["flow"].orEmpty(),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            allowInsecure = q["allowInsecure"] == "1"
        )
    }

    // ---------------------------------------------------------------- Shadowsocks
    private fun parseShadowsocks(uri: String, group: ServerGroup, subId: String?): Server? {
        val withoutScheme = uri.removePrefix("ss://")
        val frag = withoutScheme.substringAfter("#", "").let { decode(it) }
        val main = withoutScheme.substringBefore("#")
        val query = main.substringAfter("?", "")
            .split("&")
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
        var method: String
        var password: String
        var host: String
        var port: Int

        if (main.contains("@")) {
            // SIP002: base64(method:password)@host:port
            val userPart = main.substringBefore("@")
            val hostPart = main.substringAfter("@").substringBefore("/").substringBefore("?")
            val creds = tryDecodeBase64(userPart) ?: "$userPart"
            method = creds.substringBefore(":")
            password = creds.substringAfter(":")
            host = hostPart.substringBeforeLast(":")
            port = hostPart.substringAfterLast(":").toIntOrNull() ?: 443
        } else {
            // Legacy: base64(method:password@host:port)
            val creds = tryDecodeBase64(main.substringBefore("?")) ?: return null
            method = creds.substringBefore(":")
            val rest = creds.substringAfter(":")
            password = rest.substringBeforeLast("@")
            val hostPart = rest.substringAfterLast("@")
            host = hostPart.substringBeforeLast(":")
            port = hostPart.substringAfterLast(":").toIntOrNull() ?: 443
        }
        val pluginValue = query["plugin"].orEmpty()
        return baseServer(
            name = frag.ifBlank { "$host:$port" }, protocol = Protocol.SHADOWSOCKS, host = host, port = port,
            network = "tcp", streamSecurity = "none", group = group, subId = subId, rawUri = uri
        ).copy(
            method = method,
            password = password,
            ssPlugin = pluginValue.substringBefore(";"),
            ssPluginOptions = pluginValue.substringAfter(";", ""),
            preferredCore = if (pluginValue.isNotBlank()) {
                com.v2rayez.app.domain.model.CorePreference.SING_BOX
            } else {
                com.v2rayez.app.domain.model.CorePreference.SYSTEM
            }
        )
    }

    // ---------------------------------------------------------------- SSH
    // ssh://user:password@host:port?hostKey=<base64>&pk=<base64 PEM>#name
    private fun parseSsh(uri: String, group: ServerGroup, subId: String?): Server {
        val (userInfo, host, port, q, frag, _) = splitStandardUri(uri, "ssh://")
        val user = userInfo.substringBefore(":").ifBlank { q["user"] ?: "root" }
        val pass = if (userInfo.contains(":")) userInfo.substringAfter(":") else (q["password"] ?: "")
        // splitStandardUri defaults an omitted port to 443; SSH's well-known default is 22.
        val authority = uri.substringBefore("#").substringBefore("?").substringAfter("@")
        val hadExplicitPort = authority.substringAfterLast(":", "").toIntOrNull() != null
        val effPort = if (hadExplicitPort) port else 22
        val privateKey = q["pk"]?.let { tryDecodeBase64(it) ?: it }
            ?: q["privateKey"]?.let { tryDecodeBase64(it) ?: it }
            ?: ""
        return baseServer(
            name = frag.ifBlank { "$host:$effPort" }, protocol = Protocol.SSH, host = host, port = effPort,
            network = "tcp", streamSecurity = "none", group = group, subId = subId, rawUri = uri
        ).copy(
            sshUser = user,
            password = pass,
            sshPrivateKey = privateKey,
            sshHostKey = q["hostKey"]?.let { tryDecodeBase64(it) ?: it } ?: (q["host_key"] ?: ""),
            preferredCore = com.v2rayez.app.domain.model.CorePreference.SING_BOX
        )
    }

    // ---------------------------------------------------------------- WireGuard (URI form)
    // wireguard://<privateKey>@host:port?publickey=<pbk>&presharedkey=&reserved=a,b,c&address=10.0.0.2/32&mtu=1408#name
    private fun parseWireguard(uri: String, group: ServerGroup, subId: String?): Server {
        val scheme = if (uri.startsWith("wireguard://", true)) "wireguard://" else "wg://"
        val (userInfo, host, port, q, frag, _) = splitStandardUri(uri, scheme)
        val privateKey = (userInfo.ifBlank { q["privatekey"] ?: q["secretkey"] ?: q["private_key"] ?: "" })
        val addresses = (q["address"] ?: q["ip"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val allowedIps = (q["allowedips"] ?: q["allowed_ips"] ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val reserved = (q["reserved"] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }
        return baseServer(
            name = frag.ifBlank { "$host:$port" }, protocol = Protocol.WIREGUARD, host = host, port = port,
            network = "udp", streamSecurity = "none", group = group, subId = subId, rawUri = uri
        ).copy(
            wgPrivateKey = privateKey,
            wgPeerPublicKey = q["publickey"] ?: q["public_key"] ?: q["pbk"] ?: "",
            wgPreSharedKey = q["presharedkey"] ?: q["pre_shared_key"] ?: q["psk"] ?: "",
            wgLocalAddresses = addresses.ifEmpty { listOf("10.0.0.2/32") },
            wgAllowedIps = allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") },
            wgReserved = reserved,
            wgMtu = (q["mtu"] ?: "").toIntOrNull() ?: 0,
            preferredCore = com.v2rayez.app.domain.model.CorePreference.SING_BOX
        )
    }

    // ---------------------------------------------------------------- WireGuard (.conf form)
    private fun parseWireguardConf(text: String, group: ServerGroup, subId: String?): Server? {
        val kv = mutableMapOf<String, String>()
        var section = ""
        for (line in text.lines()) {
            val t = line.substringBefore("#").trim()
            if (t.isEmpty()) continue
            if (t.startsWith("[")) { section = t.trim('[', ']').lowercase(); continue }
            val key = t.substringBefore("=").trim().lowercase()
            val value = t.substringAfter("=").trim()
            kv["$section.$key"] = value
        }
        val endpoint = kv["peer.endpoint"] ?: return null
        val host = endpoint.substringBeforeLast(":")
        val port = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820
        val addresses = (kv["interface.address"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return baseServer(
            name = "WireGuard $host:$port", protocol = Protocol.WIREGUARD, host = host, port = port,
            network = "udp", streamSecurity = "none", group = group, subId = subId, rawUri = ""
        ).copy(
            wgPrivateKey = kv["interface.privatekey"] ?: "",
            wgPeerPublicKey = kv["peer.publickey"] ?: "",
            wgPreSharedKey = kv["peer.presharedkey"] ?: "",
            wgLocalAddresses = addresses.ifEmpty { listOf("10.0.0.2/32") },
            wgAllowedIps = (kv["peer.allowedips"] ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("0.0.0.0/0", "::/0") },
            wgMtu = (kv["interface.mtu"] ?: "").toIntOrNull() ?: 0,
            preferredCore = com.v2rayez.app.domain.model.CorePreference.SING_BOX
        )
    }

    // ---------------------------------------------------------------- DNS tunnel (dnstt)
    // dnstt://<pubkeyhex>@<tunnel-domain>?resolver=https://1.1.1.1/dns-query&mode=doh#name
    private fun parseDnsTunnel(uri: String, group: ServerGroup, subId: String?): Server {
        val scheme = if (uri.startsWith("dnstt://", true)) "dnstt://" else "dns://"
        val body = uri.substring(scheme.length)
        val frag = decode(body.substringAfter("#", ""))
        val main = body.substringBefore("#")
        val pubKey = decode(main.substringBefore("@", ""))
        val afterAt = main.substringAfter("@")
        val domain = afterAt.substringBefore("?")
        val q = afterAt.substringAfter("?", "").split("&")
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
        return baseServer(
            name = frag.ifBlank { "DNS · $domain" }, protocol = Protocol.DNSTUNNEL, host = domain, port = 53,
            network = "udp", streamSecurity = "none", group = group, subId = subId, rawUri = uri
        ).copy(
            dnsTunnelDomain = domain,
            dnsTunnelPubKey = pubKey.ifBlank { q["pubkey"] ?: q["key"] ?: "" },
            dnsTunnelResolver = q["resolver"] ?: q["doh"] ?: "https://1.1.1.1/dns-query",
            dnsTunnelMode = (q["mode"] ?: "doh").lowercase()
        )
    }

    // ---------------------------------------------------------------- Psiphon
    // psiphon://<base64(config json)>#name  — or psiphon://config?name=…
    private fun parsePsiphon(uri: String, group: ServerGroup, subId: String?): Server? {
        val body = uri.removePrefix("psiphon://")
        val frag = decode(body.substringAfter("#", ""))
        val payload = body.substringBefore("#").substringBefore("?").trim()
        val config = when {
            payload.isBlank() -> ""
            payload.trimStart().startsWith("{") -> payload
            else -> tryDecodeBase64(payload) ?: payload
        }
        return baseServer(
            name = frag.ifBlank { "Psiphon" }, protocol = Protocol.PSIPHON, host = "psiphon", port = 0,
            network = "tcp", streamSecurity = "none", group = group, subId = subId, rawUri = uri
        ).copy(psiphonConfig = config)
    }

    // ---------------------------------------------------------------- Serialization (share)
    fun toUri(s: Server): String {
        if (s.rawUri.isNotBlank()) return s.rawUri
        return when (s.protocol) {
            Protocol.VLESS -> buildString {
                append("vless://${s.uuid}@${s.host}:${s.port}?")
                append("encryption=${s.method.ifBlank { "none" }}")
                append("&security=${s.streamSecurity.ifBlank { "none" }}")
                append("&type=${s.network}")
                if (s.sni.isNotBlank()) append("&sni=${s.sni}")
                if (s.flow.isNotBlank()) append("&flow=${s.flow}")
                if (s.path.isNotBlank()) append("&path=${encode(s.path)}")
                if (s.requestHost.isNotBlank()) append("&host=${s.requestHost}")
                if (s.fingerprint.isNotBlank()) append("&fp=${s.fingerprint}")
                if (s.alpn.isNotBlank()) append("&alpn=${encode(s.alpn)}")
                if (s.publicKey.isNotBlank()) append("&pbk=${s.publicKey}")
                if (s.shortId.isNotBlank()) append("&sid=${s.shortId}")
                // xReality: spiderX must round-trip so re-imported REALITY servers keep the crawler path.
                if (s.spiderX.isNotBlank()) append("&spx=${encode(s.spiderX)}")
                append("#${encode(s.name)}")
            }
            Protocol.TROJAN -> buildString {
                append("trojan://${encode(s.password)}@${s.host}:${s.port}?")
                append("security=${s.streamSecurity.ifBlank { "tls" }}&type=${s.network}")
                if (s.sni.isNotBlank()) append("&sni=${s.sni}")
                if (s.path.isNotBlank()) append("&path=${encode(s.path)}")
                if (s.requestHost.isNotBlank()) append("&host=${encode(s.requestHost)}")
                if (s.flow.isNotBlank()) append("&flow=${encode(s.flow)}")
                if (s.fingerprint.isNotBlank()) append("&fp=${encode(s.fingerprint)}")
                if (s.alpn.isNotBlank()) append("&alpn=${encode(s.alpn)}")
                if (s.publicKey.isNotBlank()) append("&pbk=${encode(s.publicKey)}")
                if (s.shortId.isNotBlank()) append("&sid=${encode(s.shortId)}")
                if (s.spiderX.isNotBlank()) append("&spx=${encode(s.spiderX)}")
                if (s.allowInsecure) append("&allowInsecure=1")
                append("#${encode(s.name)}")
            }
            Protocol.SHADOWSOCKS -> buildString {
                val creds = encodeBase64("${s.method}:${s.password}")
                append("ss://$creds@${s.host}:${s.port}")
                if (s.ssPlugin.isNotBlank()) {
                    val plugin = listOf(s.ssPlugin, s.ssPluginOptions)
                        .filter { it.isNotBlank() }.joinToString(";")
                    append("?plugin=${encode(plugin)}")
                }
                append("#${encode(s.name)}")
            }
            Protocol.VMESS -> {
                val obj = buildJsonObject {
                    put("v", "2"); put("ps", s.name); put("add", s.host); put("port", s.port.toString())
                    put("id", s.uuid); put("aid", s.alterId.toString())
                    put("scy", s.method.ifBlank { "auto" }); put("net", s.network)
                    put("type", s.headerType); put("host", s.requestHost); put("path", s.path)
                    put("tls", if (s.streamSecurity == "none") "" else s.streamSecurity)
                    put("sni", s.sni); put("alpn", s.alpn); put("fp", s.fingerprint)
                }
                val jsonStr = json.encodeToString(JsonObject.serializer(), obj)
                "vmess://${encodeBase64(jsonStr)}"
            }
            Protocol.SSH -> buildString {
                val user = s.sshUser.ifBlank { "root" }
                append("ssh://${encode(user)}")
                if (s.password.isNotBlank()) append(":${encode(s.password)}")
                append("@${s.host}:${s.port}")
                val query = buildList {
                    if (s.sshHostKey.isNotBlank()) add("hostKey=${encodeBase64(s.sshHostKey)}")
                    if (s.sshPrivateKey.isNotBlank()) add("pk=${encodeBase64(s.sshPrivateKey)}")
                }
                if (query.isNotEmpty()) append("?${query.joinToString("&")}")
                append("#${encode(s.name)}")
            }
            Protocol.WIREGUARD -> buildString {
                append("wireguard://${s.wgPrivateKey}@${s.host}:${s.port}?")
                append("publickey=${encode(s.wgPeerPublicKey)}")
                if (s.wgPreSharedKey.isNotBlank()) append("&presharedkey=${encode(s.wgPreSharedKey)}")
                if (s.wgLocalAddresses.isNotEmpty()) append("&address=${encode(s.wgLocalAddresses.joinToString(","))}")
                if (s.wgAllowedIps.isNotEmpty()) append("&allowedips=${encode(s.wgAllowedIps.joinToString(","))}")
                if (s.wgReserved.isNotEmpty()) append("&reserved=${s.wgReserved.joinToString(",")}")
                if (s.wgMtu > 0) append("&mtu=${s.wgMtu}")
                append("#${encode(s.name)}")
            }
            Protocol.DNSTUNNEL -> buildString {
                append("dnstt://${encode(s.dnsTunnelPubKey)}@${s.dnsTunnelDomain}?")
                append("resolver=${encode(s.dnsTunnelResolver)}&mode=${s.dnsTunnelMode}")
                append("#${encode(s.name)}")
            }
            Protocol.PSIPHON ->
                "psiphon://${encodeBase64(s.psiphonConfig)}#${encode(s.name)}"
        }
    }

    // ---------------------------------------------------------------- helpers
    private data class UriParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
        /** Path from `host:port/path` form (before `?`), empty if absent. */
        val uriPath: String = ""
    )

    private fun splitStandardUri(uri: String, scheme: String): UriParts {
        val body = uri.removePrefix(scheme)
        val fragment = decode(body.substringAfter("#", ""))
        val main = body.substringBefore("#")
        val userInfo = decode(main.substringBefore("@", ""))
        val afterAt = main.substringAfter("@")
        // host:port[/path]?query — Xeovo VLESS uses :443/potosi?path=/potosi&...
        val beforeQuery = afterAt.substringBefore("?")
        val hostPort = beforeQuery.substringBefore("/")
        val rawUriPath = beforeQuery.substringAfter("/", missingDelimiterValue = "")
        val uriPath = when {
            rawUriPath.isBlank() -> ""
            rawUriPath.startsWith("/") -> decode(rawUriPath)
            else -> decode("/$rawUriPath")
        }
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            host = hostPort.substringAfter("[").substringBefore("]")
            port = hostPort.substringAfter("]:", "").toIntOrNull() ?: 443
        } else {
            host = hostPort.substringBeforeLast(":")
            port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
        }
        val query = afterAt.substringAfter("?", "").split("&")
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
        return UriParts(userInfo, host, port, query, fragment, uriPath)
    }

    private fun baseServer(
        name: String, protocol: Protocol, host: String, port: Int,
        network: String, streamSecurity: String, group: ServerGroup, subId: String?, rawUri: String
    ): Server {
        val transportLabel = when (network.lowercase()) {
            "ws" -> "WS"
            "grpc" -> "gRPC"
            "h2", "http" -> "HTTP/2"
            "quic" -> "QUIC"
            "httpupgrade" -> "HTTPUpgrade"
            else -> "TCP"
        }
        val securityLabel = when (streamSecurity.lowercase()) {
            "tls" -> "TLS"
            "reality" -> "Reality"
            "xtls" -> "XTLS"
            else -> "None"
        }
        val cc = CountryGuesser.guess(name)
        return Server(
            // Subscription servers get a DETERMINISTIC id (subId + rawUri) so identity is stable
            // across refreshes and merges preserve ping/favorites/edits. Manual servers stay random.
            id = if (subId != null) stableId(subId, rawUri) else UUID.randomUUID().toString(),
            name = name,
            country = cc.second,
            countryCode = cc.first,
            protocol = protocol,
            transport = transportLabel,
            security = securityLabel,
            sni = host,
            address = "$host:$port",
            pingMs = -1,
            signal = 3,
            group = group,
            isFavorite = false,
            host = host,
            port = port,
            network = network.lowercase(),
            streamSecurity = streamSecurity.lowercase(),
            subscriptionId = subId,
            rawUri = rawUri
        )
    }

    /** Stable, collision-resistant id derived from the owning subscription + the raw share URI. */
    private fun stableId(subId: String, rawUri: String): String =
        UUID.nameUUIDFromBytes("$subId|$rawUri".toByteArray(Charsets.UTF_8)).toString()

    private fun tryDecodeBase64(input: String): String? {
        val cleaned = input.trim().replace('-', '+').replace('_', '/')
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
    }

    private fun encodeBase64(input: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray())

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun encode(s: String): String = runCatching { java.net.URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}
