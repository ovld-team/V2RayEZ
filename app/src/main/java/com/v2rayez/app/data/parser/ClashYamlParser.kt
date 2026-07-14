package com.v2rayez.app.data.parser

import com.v2rayez.app.domain.model.CorePreference
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.util.UUID

/** Imports the supported proxy entries from Clash/Mihomo YAML subscriptions. */
object ClashYamlParser {
    private val loader = Load(
        LoadSettings.builder()
            .setLabel("Clash subscription")
            .setCodePointLimit(4 * 1024 * 1024)
            .setMaxAliasesForCollections(20)
            .build()
    )

    fun looksLikeYaml(text: String): Boolean =
        Regex("""(?m)^\s*(proxies|proxy-providers)\s*:""").containsMatchIn(text)

    fun parse(
        text: String,
        group: ServerGroup = ServerGroup.MANUAL,
        subscriptionId: String? = null
    ): List<Server> {
        val root = runCatching { loader.loadFromString(text).asMap() }.getOrNull() ?: return emptyList()
        val proxies = root["proxies"] as? List<*> ?: return emptyList()
        return proxies.mapNotNull { parseProxy(it.asMap(), group, subscriptionId) }
    }

    private fun parseProxy(raw: Map<String, Any?>, group: ServerGroup, subId: String?): Server? {
        val type = raw.string("type").lowercase()
        val protocol = when (type) {
            "vless" -> Protocol.VLESS
            "vmess" -> Protocol.VMESS
            "trojan" -> Protocol.TROJAN
            "ss", "shadowsocks" -> Protocol.SHADOWSOCKS
            "wireguard", "wg" -> Protocol.WIREGUARD
            "ssh" -> Protocol.SSH
            else -> return null
        }
        val host = raw.string("server")
        val port = raw.int("port", if (protocol == Protocol.SSH) 22 else 443)
        if (host.isBlank() || port !in 1..65535) return null

        val name = raw.string("name").ifBlank { "$host:$port" }
        val network = raw.string("network").ifBlank {
            if (protocol == Protocol.WIREGUARD) "udp" else "tcp"
        }.lowercase()
        val reality = raw.map("reality-opts")
        val ws = raw.map("ws-opts")
        val grpc = raw.map("grpc-opts")
        val http = raw.map("h2-opts").ifEmpty { raw.map("http-opts") }
        val tls = raw.bool("tls") || raw.bool("reality") || reality.isNotEmpty()
        val security = when {
            raw.bool("reality") || reality.isNotEmpty() -> "reality"
            tls -> "tls"
            else -> "none"
        }
        val requestHost = when (network) {
            "ws" -> ws.map("headers").string("Host").ifBlank { ws.map("headers").string("host") }
            "h2", "http" -> http.stringList("host").firstOrNull().orEmpty()
            else -> ""
        }
        val path = when (network) {
            "ws" -> ws.string("path")
            "grpc" -> grpc.string("grpc-service-name").ifBlank { grpc.string("service-name") }
            "h2", "http" -> http.string("path")
            else -> raw.string("path")
        }
        val plugin = raw.string("plugin")
        val pluginOpts = raw.map("plugin-opts").entries
            .joinToString(";") { (key, value) -> "$key=${scalar(value)}" }

        return Server(
            id = stableId(subId, raw),
            name = name,
            country = CountryGuesser.guess(name).second,
            countryCode = CountryGuesser.guess(name).first,
            protocol = protocol,
            transport = transportLabel(network),
            security = securityLabel(security),
            sni = raw.string("servername").ifBlank { raw.string("sni").ifBlank { host } },
            address = "$host:$port",
            pingMs = -1,
            signal = 3,
            group = group,
            host = host,
            port = port,
            uuid = raw.string("uuid"),
            password = raw.string("password"),
            method = raw.string("cipher"),
            alterId = raw.int("alterId", raw.int("alter-id", 0)),
            flow = raw.string("flow"),
            network = network,
            headerType = raw.string("header-type").ifBlank { "none" },
            path = path,
            requestHost = requestHost,
            streamSecurity = security,
            alpn = raw.stringList("alpn").joinToString(","),
            fingerprint = raw.string("client-fingerprint").ifBlank { raw.string("fingerprint") },
            allowInsecure = raw.bool("skip-cert-verify"),
            publicKey = reality.string("public-key").ifBlank { raw.string("public-key") },
            shortId = reality.string("short-id").ifBlank { raw.string("short-id") },
            ssPlugin = plugin,
            ssPluginOptions = pluginOpts,
            sshUser = raw.string("username").ifBlank { raw.string("user") },
            sshPrivateKey = raw.string("private-key"),
            sshHostKey = raw.stringList("host-key").firstOrNull().orEmpty(),
            wgPrivateKey = raw.string("private-key"),
            wgPeerPublicKey = raw.string("public-key"),
            wgPreSharedKey = raw.string("pre-shared-key"),
            wgLocalAddresses = raw.stringList("ip").ifEmpty { raw.stringList("address") },
            wgAllowedIps = raw.stringList("allowed-ips").ifEmpty { listOf("0.0.0.0/0", "::/0") },
            wgReserved = raw.intList("reserved"),
            wgMtu = raw.int("mtu", 0),
            subscriptionId = subId,
            preferredCore = when {
                protocol.requiresSingBox() || plugin.isNotBlank() -> CorePreference.SING_BOX
                else -> CorePreference.SYSTEM
            }
        )
    }

    private fun stableId(subId: String?, raw: Map<String, Any?>): String =
        if (subId == null) UUID.randomUUID().toString()
        else UUID.nameUUIDFromBytes("$subId|$raw".toByteArray()).toString()

    private fun transportLabel(network: String): String = when (network) {
        "ws" -> "WS"
        "grpc" -> "gRPC"
        "h2", "http" -> "HTTP/2"
        "quic" -> "QUIC"
        "kcp", "mkcp" -> "mKCP"
        else -> "TCP"
    }

    private fun securityLabel(security: String): String = when (security) {
        "tls" -> "TLS"
        "reality" -> "Reality"
        else -> "None"
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap()
    private fun Map<String, Any?>.string(key: String): String = scalar(this[key])
    private fun Map<String, Any?>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: string(key).toIntOrNull() ?: default
    private fun Map<String, Any?>.bool(key: String): Boolean =
        this[key] == true || string(key).equals("true", true)
    private fun Map<String, Any?>.stringList(key: String): List<String> = when (val value = this[key]) {
        is List<*> -> value.map(::scalar).filter { it.isNotBlank() }
        null -> emptyList()
        else -> scalar(value).split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    private fun Map<String, Any?>.intList(key: String): List<Int> =
        stringList(key).mapNotNull { it.toIntOrNull() }
    private fun scalar(value: Any?): String = value?.toString().orEmpty()
}
