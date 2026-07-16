package com.v2rayez.app.data.analytics

/**
 * Strips anything that could identify a server, bridge, or user config before it reaches
 * remote telemetry: hosts/domains, full URIs, IPv4/IPv6 literals, UUIDs, emails,
 * PEM key/cert blocks, Tor bridge lines, fingerprints, and long base64 blobs.
 *
 * Pure string -> string; no Android/Firebase deps, so it is directly unit-testable.
 */
object PiiScrubber {

    fun scrub(input: String): String {
        var out = input
        out = PEM_BLOCK.replace(out, "[pem]")
        out = URI_SCHEME.replace(out, "[uri]")
        out = BRIDGE_LINE.replace(out, "[bridge]")
        out = EMAIL.replace(out, "[email]")
        out = UUID.replace(out, "[uuid]")
        out = HOSTNAME.replace(out, "[host]")
        out = IPV6_CANDIDATE.replace(out) { m -> if (looksLikeIpv6(m.value)) "[ip]" else m.value }
        out = IPV4.replace(out, "[ip]")
        out = HEX_FINGERPRINT.replace(out, "[fp]")
        out = BASE64_BLOB.replace(out, "[b64]")
        return out
    }

    fun scrubOrNull(input: String?): String? = if (input == null) null else scrub(input)

    // vless://, vmess://, trojan://, ss://, ssr://, obfs4://, http(s)://, ws(s)://, grpc://, tor://, ...
    private val URI_SCHEME = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]{1,15}://\S+""")

    // Bare RFC 4122 UUIDs (VLESS ids, Room server ids, Crashlytics server_ref).
    private val UUID = Regex(
        """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
    )

    // Local-part@domain — applied before HOSTNAME so the whole address becomes [email].
    private val EMAIL = Regex("""\b[^\s@]+@[^\s@]+\.[^\s@]+\b""")

    // Bare `host.example.com` or `host.example.com:443` with no scheme.
    private val HOSTNAME = Regex(
        """\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}(?::\d{1,5})?\b"""
    )

    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")

    // Candidate hex/colon runs; [looksLikeIpv6] disambiguates real IPv6 (incl. "::" compression)
    // from a plain HH:MM:SS timestamp ("14:32:10"), which must never be redacted.
    private val IPV6_CANDIDATE = Regex("""\b[0-9A-Fa-f:]{2,45}\b""")

    private fun looksLikeIpv6(token: String): Boolean {
        val colons = token.count { it == ':' }
        if (colons < 2) return false
        if ("::" in token) return true
        if (colons >= 3) return true
        return token.any { it in 'a'..'f' || it in 'A'..'F' }
    }

    private val PEM_BLOCK = Regex("""-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----""")

    // obfs4/snowflake/webtunnel/meek(_lite) bridge lines (torrc `Bridge ...` format).
    private val BRIDGE_LINE = Regex(
        """\b(?:Bridge\s+)?(obfs4|snowflake|webtunnel|meek_lite|meek)\s+\S[^\n]*""",
        RegexOption.IGNORE_CASE
    )

    // Bridge fingerprints / cert hashes: bare 40-hex-char tokens.
    private val HEX_FINGERPRINT = Regex("""\b[A-Fa-f0-9]{40}\b""")

    // Subscription bodies / long encoded config blobs.
    private val BASE64_BLOB = Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b""")
}
