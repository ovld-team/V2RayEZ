package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server

/**
 * Builds a minimal Mihomo (Clash Meta) YAML config: mixed-port + one proxy + GLOBAL select.
 */
object ClashConfigBuilder {

    fun build(server: Server, settings: AppSettings, mixedPort: Int): String {
        val name = "node"
        val proxy = proxyYaml(server, name)
        return buildString {
            appendLine("mixed-port: $mixedPort")
            appendLine("allow-lan: false")
            appendLine("mode: global")
            appendLine("log-level: warning")
            appendLine("ipv6: ${settings.enableIpv6}")
            appendLine("proxies:")
            appendLine(proxy)
            appendLine("proxy-groups:")
            appendLine("  - name: GLOBAL")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - $name")
            appendLine("rules:")
            appendLine("  - MATCH,GLOBAL")
        }
    }

    private fun proxyYaml(server: Server, name: String): String {
        val net = server.network.ifBlank { "tcp" }
        val security = server.streamSecurity.lowercase().ifBlank {
            when (server.protocol) {
                Protocol.SHADOWSOCKS -> ""
                Protocol.TROJAN -> "tls"
                else -> if (server.publicKey.isNotBlank()) "reality" else if (server.sni.isNotBlank()) "tls" else ""
            }
        }
        val sni = server.sni.ifBlank { server.host }
        return when (server.protocol) {
            Protocol.SHADOWSOCKS -> """
              |  - name: $name
              |    type: ss
              |    server: ${server.host}
              |    port: ${server.port}
              |    cipher: ${server.method}
              |    password: "${escape(server.password)}"
            """.trimMargin()
            Protocol.TROJAN -> buildString {
                appendLine("  - name: $name")
                appendLine("    type: trojan")
                appendLine("    server: ${server.host}")
                appendLine("    port: ${server.port}")
                appendLine("    password: \"${escape(server.password)}\"")
                appendNetwork(net, server)
                appendTls(security, sni, server)
            }
            Protocol.VMESS -> buildString {
                appendLine("  - name: $name")
                appendLine("    type: vmess")
                appendLine("    server: ${server.host}")
                appendLine("    port: ${server.port}")
                appendLine("    uuid: ${server.uuid}")
                appendLine("    alterId: ${server.alterId}")
                appendLine("    cipher: ${server.method.ifBlank { "auto" }}")
                appendNetwork(net, server)
                appendTls(security, sni, server)
            }
            Protocol.VLESS -> buildString {
                appendLine("  - name: $name")
                appendLine("    type: vless")
                appendLine("    server: ${server.host}")
                appendLine("    port: ${server.port}")
                appendLine("    uuid: ${server.uuid}")
                if (server.flow.isNotBlank()) appendLine("    flow: ${server.flow}")
                appendNetwork(net, server)
                appendTls(security, sni, server)
            }
            Protocol.SSH -> buildString {
                appendLine("  - name: $name")
                appendLine("    type: ssh")
                appendLine("    server: ${server.host}")
                appendLine("    port: ${server.port}")
                appendLine("    username: ${server.sshUser.ifBlank { "root" }}")
                if (server.password.isNotBlank()) appendLine("    password: \"${escape(server.password)}\"")
                if (server.sshPrivateKey.isNotBlank()) appendLine("    private-key: \"${escape(server.sshPrivateKey)}\"")
            }
            Protocol.WIREGUARD -> buildString {
                appendLine("  - name: $name")
                appendLine("    type: wireguard")
                appendLine("    server: ${server.host}")
                appendLine("    port: ${server.port}")
                appendLine("    private-key: \"${escape(server.wgPrivateKey)}\"")
                appendLine("    public-key: \"${escape(server.wgPeerPublicKey)}\"")
                if (server.wgLocalAddresses.isNotEmpty()) {
                    appendLine("    ip: ${server.wgLocalAddresses.first().substringBefore("/")}")
                }
                if (server.wgPreSharedKey.isNotBlank()) appendLine("    pre-shared-key: \"${escape(server.wgPreSharedKey)}\"")
                if (server.wgMtu > 0) appendLine("    mtu: ${server.wgMtu}")
            }
            // DNS-tunnel / Psiphon are not mihomo proxy types — they run on dedicated engines.
            // Emit an inert direct node so the YAML stays valid if a stale core pin reaches here.
            Protocol.DNSTUNNEL, Protocol.PSIPHON -> """
              |  - name: $name
              |    type: direct
            """.trimMargin()
        }
    }

    private fun StringBuilder.appendNetwork(net: String, server: Server) {
        when (net) {
            "ws" -> {
                appendLine("    network: ws")
                appendLine("    ws-opts:")
                appendLine("      path: ${server.path.ifBlank { "/" }}")
                val host = server.requestHost.ifBlank { server.sni }
                if (host.isNotBlank()) {
                    appendLine("      headers:")
                    appendLine("        Host: $host")
                }
            }
            "grpc" -> {
                appendLine("    network: grpc")
                appendLine("    grpc-opts:")
                appendLine("      grpc-service-name: ${server.path}")
            }
            "h2", "http" -> {
                appendLine("    network: h2")
                appendLine("    h2-opts:")
                appendLine("      path: ${server.path.ifBlank { "/" }}")
            }
            else -> appendLine("    network: tcp")
        }
    }

    private fun StringBuilder.appendTls(security: String, sni: String, server: Server) {
        when (security) {
            "tls", "xtls" -> {
                appendLine("    tls: true")
                appendLine("    servername: $sni")
                appendLine("    skip-cert-verify: ${server.allowInsecure}")
                appendLine("    client-fingerprint: ${server.fingerprint.ifBlank { "chrome" }}")
                if (server.alpn.isNotBlank()) {
                    appendLine("    alpn:")
                    server.alpn.split(",").forEach { appendLine("      - ${it.trim()}") }
                }
            }
            "reality" -> {
                appendLine("    tls: true")
                appendLine("    servername: $sni")
                appendLine("    client-fingerprint: ${server.fingerprint.ifBlank { "chrome" }}")
                appendLine("    reality-opts:")
                appendLine("      public-key: ${server.publicKey}")
                appendLine("      short-id: ${server.shortId}")
            }
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
