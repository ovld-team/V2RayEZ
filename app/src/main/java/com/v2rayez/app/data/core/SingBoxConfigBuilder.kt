package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a minimal sing-box JSON config: SOCKS inbound + one outbound for [server].
 * Tor / fronting / fragment are not wired here (Xray-only features).
 */
object SingBoxConfigBuilder {
    private val json = Json { prettyPrint = false }

    fun build(server: Server, settings: AppSettings, socksPort: Int): String {
        val isWireguard = server.protocol == Protocol.WIREGUARD
        val obj = buildJsonObject {
            put("log", buildJsonObject { put("level", "warn") })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", socksPort)
                    put("udp", true)
                })
            })
            if (isWireguard) {
                // sing-box ≥1.11 models WireGuard as a routable endpoint, not an outbound.
                put("endpoints", buildJsonArray { add(wireguardEndpoint(server)) })
                put("outbounds", buildJsonArray {
                    add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                })
            } else {
                put("outbounds", buildJsonArray {
                    add(proxyOutbound(server))
                    add(buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    })
                })
            }
            put("route", buildJsonObject {
                put("final", "proxy")
                putJsonArray("rules") {
                    if (settings.tor.enabled) {
                        add(buildJsonObject {
                            put("domain_suffix", buildJsonArray {
                                add(JsonPrimitive(".onion"))
                            })
                            put("outbound", "direct") // Tor SOCKS is separate; onion via OS/Tor when enabled
                        })
                    }
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** sing-box `wireguard` endpoint (tag `proxy`) for a generic WG peer. */
    private fun wireguardEndpoint(server: Server): JsonObject = buildJsonObject {
        put("type", "wireguard")
        put("tag", "proxy")
        put("system", false)
        putJsonArray("address") {
            server.wgLocalAddresses.ifEmpty { listOf("10.0.0.2/32") }.forEach { add(it) }
        }
        put("private_key", server.wgPrivateKey)
        if (server.wgMtu > 0) put("mtu", server.wgMtu)
        putJsonArray("peers") {
            add(buildJsonObject {
                put("address", server.host)
                put("port", server.port)
                put("public_key", server.wgPeerPublicKey)
                if (server.wgPreSharedKey.isNotBlank()) put("pre_shared_key", server.wgPreSharedKey)
                putJsonArray("allowed_ips") {
                    server.wgAllowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") }.forEach { add(it) }
                }
                if (server.wgReserved.isNotEmpty()) {
                    putJsonArray("reserved") { server.wgReserved.forEach { add(JsonPrimitive(it)) } }
                }
            })
        }
    }

    private fun proxyOutbound(server: Server): JsonObject = buildJsonObject {
        put("tag", "proxy")
        val net = server.network.ifBlank { "tcp" }
        val security = server.streamSecurity.lowercase().ifBlank {
            when (server.protocol) {
                Protocol.SHADOWSOCKS -> "none"
                Protocol.TROJAN -> "tls"
                else -> if (server.publicKey.isNotBlank()) "reality" else if (server.sni.isNotBlank()) "tls" else "none"
            }
        }
        when (server.protocol) {
            Protocol.VLESS -> {
                put("type", "vless")
                put("server", server.host)
                put("server_port", server.port)
                put("uuid", server.uuid)
                if (server.flow.isNotBlank()) put("flow", server.flow)
                putTransport(net, server)
                putTls(security, server)
            }
            Protocol.VMESS -> {
                put("type", "vmess")
                put("server", server.host)
                put("server_port", server.port)
                put("uuid", server.uuid)
                put("security", server.method.ifBlank { "auto" })
                put("alter_id", server.alterId)
                putTransport(net, server)
                putTls(security, server)
            }
            Protocol.TROJAN -> {
                put("type", "trojan")
                put("server", server.host)
                put("server_port", server.port)
                put("password", server.password)
                putTransport(net, server)
                putTls(security, server)
            }
            Protocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("server", server.host)
                put("server_port", server.port)
                put("method", server.method)
                put("password", server.password)
                if (server.ssPlugin.isNotBlank()) put("plugin", server.ssPlugin)
                if (server.ssPluginOptions.isNotBlank()) put("plugin_opts", server.ssPluginOptions)
            }
            Protocol.SSH -> {
                put("type", "ssh")
                put("server", server.host)
                put("server_port", server.port)
                put("user", server.sshUser.ifBlank { "root" })
                if (server.password.isNotBlank()) put("password", server.password)
                if (server.sshPrivateKey.isNotBlank()) {
                    putJsonArray("private_key") { server.sshPrivateKey.split("\n").forEach { add(it) } }
                }
                if (server.sshHostKey.isNotBlank()) {
                    putJsonArray("host_key") { add(server.sshHostKey) }
                }
                put("client_version", "SSH-2.0-OpenSSH_9.0")
            }
            // WireGuard is emitted as a route endpoint by build(); DNS-tunnel / Psiphon run on
            // their own engines. These branches keep the `when` exhaustive and never tunnel in
            // the clear if somehow reached.
            Protocol.WIREGUARD -> {
                put("type", "direct")
            }
            Protocol.DNSTUNNEL, Protocol.PSIPHON -> {
                put("type", "block")
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTransport(net: String, server: Server) {
        when (net) {
            "ws" -> putJsonObject("transport") {
                put("type", "ws")
                put("path", server.path.ifBlank { "/" })
                val host = server.requestHost.ifBlank { server.sni }
                if (host.isNotBlank()) putJsonObject("headers") { put("Host", host) }
            }
            "grpc" -> putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", server.path)
            }
            "http", "h2" -> putJsonObject("transport") {
                put("type", "http")
                put("path", server.path.ifBlank { "/" })
                val host = server.requestHost.ifBlank { server.sni }
                if (host.isNotBlank()) putJsonArray("host") { add(host) }
            }
            "httpupgrade" -> putJsonObject("transport") {
                put("type", "httpupgrade")
                put("path", server.path.ifBlank { "/" })
                val host = server.requestHost.ifBlank { server.sni }
                if (host.isNotBlank()) put("host", host)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTls(security: String, server: Server) {
        when (security) {
            "tls", "xtls" -> putJsonObject("tls") {
                put("enabled", true)
                put("server_name", server.sni.ifBlank { server.host })
                put("insecure", server.allowInsecure)
                if (server.alpn.isNotBlank()) {
                    putJsonArray("alpn") { server.alpn.split(",").forEach { add(it.trim()) } }
                }
                // xReality hardening: always present a modern uTLS ClientHello (default chrome).
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", ConfigBuilder.effectiveFingerprint(server, security))
                }
            }
            "reality" -> putJsonObject("tls") {
                put("enabled", true)
                put("server_name", server.sni.ifBlank { server.host })
                put("insecure", false)
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", ConfigBuilder.effectiveFingerprint(server, "reality"))
                }
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", server.publicKey)
                    put("short_id", server.shortId)
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) =
        add(kotlinx.serialization.json.JsonPrimitive(value))
}
