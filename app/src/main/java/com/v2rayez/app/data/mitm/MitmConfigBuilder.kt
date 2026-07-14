package com.v2rayez.app.data.mitm

import com.v2rayez.app.domain.model.MitmDomainFrontConfig
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
 * Builds a serverless MITM domain-fronting Xray config (no remote proxy server).
 *
 * Traffic path: apps -> `socks-in`/`http-in` (and optionally `tun-in`) -> (matching TLS 443)
 * `redirect-out` -> `tls-decrypt` dokodemo-door (terminates TLS with the on-device CA) ->
 * per-front `tls-repack-*` freedom outbound. Non-matching traffic exits `direct`.
 *
 * QUIC/HTTP3 is blocked so clients fall back to TLS (required for MITM decrypt). TUN builds
 * enable FakeDNS so domain rules match after sniffing remaps fake IPs to SNI.
 *
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM. Certificate paths are
 * passed in so the Android tunnel can use absolute paths while
 * [MitmDesktopExporter] uses `v2rayN`-style relative names.
 */
object MitmConfigBuilder {

    const val TAG_BLOCK = "block"
    const val TAG_DIRECT = "direct"
    const val TAG_REDIRECT = "redirect-out"
    const val TAG_DNS = "dns-out"
    const val TAG_REPACK_DNS = "tls-repack-dns"
    const val TAG_SOCKS_IN = "socks-in"
    const val TAG_HTTP_IN = "http-in"
    const val TAG_TUN_IN = "tun-in"
    const val TAG_DECRYPT = "tls-decrypt"
    const val TAG_DNS_QUERY = "dns-query"

    /** Special ALPN/verify token that tells Xray the peer cert originates from the MITM leg. */
    const val ALPN_FROM_MITM = "fromMitM"

    /** Local port the decrypt dokodemo-door listens on; `redirect-out` targets it. */
    const val DECRYPT_PORT = 4431

    private val json = Json { prettyPrint = false }

    /** Prefix for the generated per-front repack outbound tags. */
    private const val REPACK_TAG_PREFIX = "tls-repack-"

    /**
     * Build the Android MITM config.
     *
     * @param certFile absolute path to the CA certificate the decrypt inbound issues from.
     * @param keyFile absolute path to that CA's private key.
     * @param mtu TUN MTU for the VpnService path (ignored for desktop export / proxy-only).
     * @param includeTun when true, adds a `tun-in` inbound so libv2ray can bind the VPN fd.
     */
    fun build(
        config: MitmDomainFrontConfig,
        certFile: String,
        keyFile: String,
        mtu: Int = 1280,
        includeTun: Boolean = true,
        // Fail-safe default: without geosite.dat a geosite:private entry would stop the core.
        geositeAvailable: Boolean = false
    ): String {
        val rules = FrontRuleParser.parse(config.rulesText)
        // Preserve first-seen order of fronts so tag numbering is stable/testable.
        // Only explicit rules create MITM fronts — unmatched hosts always exit `direct`
        // (defaultFront is not used as a catch-all so casual browsing is not MITM'd).
        val fronts = LinkedHashMap<String, MutableList<FrontRule>>()
        for (rule in rules) fronts.getOrPut(rule.frontSni) { ArrayList() }.add(rule)

        val frontTags = LinkedHashMap<String, String>()
        fronts.keys.forEachIndexed { i, front -> frontTags[front] = "$REPACK_TAG_PREFIX$i" }

        val clientInboundTags = buildList {
            add(TAG_SOCKS_IN)
            add(TAG_HTTP_IN)
            if (includeTun) add(TAG_TUN_IN)
        }

        val obj = buildJsonObject {
            put("log", logBlock())
            put("dns", dnsBlock(config, includeTun, geositeAvailable))
            if (includeTun) put("fakedns", fakednsBlock())
            put("inbounds", inbounds(config, certFile, keyFile, mtu, includeTun))
            put("outbounds", outbounds(config, frontTags))
            put("routing", routing(fronts, frontTags, clientInboundTags))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun logBlock(): JsonObject = buildJsonObject {
        put("loglevel", "warning")
        put("dnsLog", false)
    }

    /** Xray FakeDNS pool: reserved 198.18/15 IPs that sniffing maps back to SNI. */
    private fun fakednsBlock(): JsonArray = buildJsonArray {
        addJsonObject {
            put("ipPool", "198.18.0.0/15")
            put("poolSize", 65535)
        }
    }

    /**
     * DNS for the MITM core. TUN builds prefer FakeDNS + plain UDP so domain routing can match
     * sniffed names; DoH (fronted) remains available for the DNS outbound handler.
     */
    private fun dnsBlock(
        config: MitmDomainFrontConfig,
        includeTun: Boolean,
        geositeAvailable: Boolean
    ): JsonObject = buildJsonObject {
        put("tag", TAG_DNS_QUERY)
        putJsonArray("servers") {
            if (includeTun) add("fakedns")
            // Plain UDP fallback so TUN doesn't depend solely on a live DoH path.
            add(config.dohIp.ifBlank { "1.1.1.1" })
            add("h2c://${config.dohIp}/dns-query")
            // geosite:private needs geosite.dat (on-demand download since v0.9.50); without
            // it the localhost split entry is skipped and DoH answers those names too.
            if (geositeAvailable) {
                addJsonObject {
                    put("address", "localhost")
                    putJsonArray("domains") { add("geosite:private") }
                }
            }
        }
        put("disableFallback", false)
        put("queryStrategy", "UseIPv4")
    }

    private fun inbounds(
        config: MitmDomainFrontConfig,
        certFile: String,
        keyFile: String,
        mtu: Int,
        includeTun: Boolean
    ): JsonArray =
        buildJsonArray {
            // Required for VpnService: libv2ray sets xray.tun.fd only when a tun inbound exists.
            if (includeTun) {
                addJsonObject {
                    put("tag", TAG_TUN_IN)
                    put("protocol", "tun")
                    put("port", 0)
                    putJsonObject("settings") {
                        put("name", "xray0")
                        put("mtu", mtu.coerceIn(1280, 1400))
                    }
                    putJsonObject("sniffing") {
                        put("enabled", true)
                        putJsonArray("destOverride") {
                            add("http"); add("tls"); add("quic"); add("fakedns")
                        }
                    }
                }
            }
            addJsonObject {
                put("tag", TAG_SOCKS_IN)
                put("protocol", "socks")
                put("listen", "127.0.0.1")
                put("port", config.proxyPort)
                putJsonObject("settings") { put("udp", true) }
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls") }
                    put("routeOnly", false)
                }
            }
            addJsonObject {
                put("tag", TAG_HTTP_IN)
                put("protocol", "http")
                put("listen", "127.0.0.1")
                put("port", config.httpPort)
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls") }
                    put("routeOnly", false)
                }
            }
            // MITM decrypt: terminates the intercepted TLS with the local CA so the real SNI
            // (and Host) are readable, then routing repacks it onto the fronted outbound.
            addJsonObject {
                put("tag", TAG_DECRYPT)
                put("protocol", "dokodemo-door")
                put("port", DECRYPT_PORT)
                putJsonObject("settings") {
                    put("network", "tcp")
                    put("port", 443)
                    put("followRedirect", true)
                }
                putJsonObject("streamSettings") {
                    put("security", "tls")
                    putJsonObject("tlsSettings") {
                        putJsonArray("alpn") { add("h2"); add("http/1.1") }
                        putJsonArray("certificates") {
                            addJsonObject {
                                put("usage", "issue")
                                put("certificateFile", certFile)
                                put("keyFile", keyFile)
                            }
                        }
                    }
                }
            }
        }

    private fun outbounds(
        config: MitmDomainFrontConfig,
        frontTags: Map<String, String>
    ): JsonArray = buildJsonArray {
        addJsonObject {
            put("tag", TAG_BLOCK)
            put("protocol", "blackhole")
        }
        addJsonObject {
            put("tag", TAG_DIRECT)
            put("protocol", "freedom")
            putJsonObject("settings") { put("domainStrategy", "ForceIP") }
        }
        // Loops decrypted-and-redirected traffic back into the decrypt dokodemo-door.
        addJsonObject {
            put("tag", TAG_REDIRECT)
            put("protocol", "freedom")
            putJsonObject("settings") { put("redirect", "127.0.0.1:$DECRYPT_PORT") }
        }
        // Per-front repack outbounds: re-establish TLS with the fronted SNI.
        for ((front, tag) in frontTags) {
            add(repackOutbound(tag, front))
        }
        // Fronted DoH leg.
        add(dohRepackOutbound(config))
        addJsonObject {
            put("tag", TAG_DNS)
            put("protocol", "dns")
            putJsonObject("settings") {
                put("nonIPQuery", "skip")
                put("network", "tcp")
                put("address", config.dohIp)
                put("port", 53)
            }
        }
    }

    private fun repackOutbound(tag: String, front: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", "freedom")
        putJsonObject("settings") { put("domainStrategy", "ForceIP") }
        putJsonObject("streamSettings") {
            put("security", "tls")
            putJsonObject("tlsSettings") {
                put("serverName", front)
                put("verifyPeerCertByName", "$ALPN_FROM_MITM,$front")
                putJsonArray("alpn") { add(ALPN_FROM_MITM) }
                put("fingerprint", "chrome")
            }
        }
    }

    private fun dohRepackOutbound(config: MitmDomainFrontConfig): JsonObject = buildJsonObject {
        put("tag", TAG_REPACK_DNS)
        put("protocol", "freedom")
        putJsonObject("settings") { put("domainStrategy", "ForceIP") }
        putJsonObject("streamSettings") {
            put("security", "tls")
            putJsonObject("tlsSettings") {
                put("serverName", config.dohFrontSni)
                val names = listOf(ALPN_FROM_MITM, config.dohFrontSni, config.dohHost)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(",")
                put("verifyPeerCertByName", names)
                putJsonArray("alpn") { add(ALPN_FROM_MITM) }
                put("fingerprint", "chrome")
            }
        }
    }

    private fun routing(
        fronts: Map<String, List<FrontRule>>,
        frontTags: Map<String, String>,
        clientInboundTags: List<String>
    ): JsonObject = buildJsonObject {
        put("domainStrategy", "IPOnDemand")
        putJsonArray("rules") {
            // Force HTTPS clients (YouTube/Chrome) off HTTP/3 so TLS MITM can see SNI.
            addJsonObject {
                put("type", "field")
                put("outboundTag", TAG_BLOCK)
                putJsonArray("protocol") { add("quic") }
            }
            addJsonObject {
                put("type", "field")
                put("outboundTag", TAG_BLOCK)
                put("port", "443")
                putJsonArray("network") { add("udp") }
            }
            // Port-53 traffic from apps resolves via the DNS handler (FakeDNS + DoH).
            addJsonObject {
                put("outboundTag", TAG_DNS)
                putJsonArray("inboundTag") { clientInboundTags.forEach { add(it) } }
                put("port", 53)
            }
            // The DoH query's own outbound TLS leg is fronted.
            addJsonObject {
                put("outboundTag", TAG_REPACK_DNS)
                putJsonArray("inboundTag") { add(TAG_DNS_QUERY) }
            }
            // ONLY hosts listed in the rules go into the MITM decrypt path.
            val allPatterns = fronts.values.flatten().map { it.domainPattern }
            if (allPatterns.isNotEmpty()) {
                addJsonObject {
                    put("outboundTag", TAG_REDIRECT)
                    putJsonArray("inboundTag") { clientInboundTags.forEach { add(it) } }
                    put("network", "tcp")
                    putJsonArray("protocol") { add("tls") }
                    put("port", 443)
                    putJsonArray("domain") { allPatterns.forEach { add(toXrayDomain(it)) } }
                }
            }
            // Everything else from clients exits direct — never MITM'd.
            addJsonObject {
                put("outboundTag", TAG_DIRECT)
                putJsonArray("inboundTag") { clientInboundTags.forEach { add(it) } }
            }
            // Decrypted traffic is repacked onto the matching fronted outbound.
            for ((front, rules) in fronts) {
                val tag = frontTags[front] ?: continue
                addJsonObject {
                    put("outboundTag", tag)
                    putJsonArray("inboundTag") { add(TAG_DECRYPT) }
                    putJsonArray("domain") { rules.forEach { add(toXrayDomain(it.domainPattern)) } }
                }
            }
            // Anything that reached decrypt without a matching rule (should be rare) → direct.
            addJsonObject {
                put("outboundTag", TAG_DIRECT)
                putJsonArray("inboundTag") { add(TAG_DECRYPT) }
            }
            // Final safety net.
            addJsonObject {
                put("outboundTag", TAG_DIRECT)
                put("network", "tcp,udp")
            }
        }
    }

    /** Convert a rule pattern to an Xray routing domain matcher (subdomain-inclusive). */
    private fun toXrayDomain(pattern: String): String {
        val bare = if (pattern.startsWith("*.")) pattern.substring(2) else pattern
        return "domain:$bare"
    }
}
