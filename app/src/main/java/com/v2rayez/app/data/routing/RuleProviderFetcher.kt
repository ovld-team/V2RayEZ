package com.v2rayez.app.data.routing

import com.v2rayez.app.data.net.UrlSafety
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and parses a remote ruleset (GitHub raw / any URL). Supports common
 * formats: plain one-entry-per-line lists, `hosts` files (`0.0.0.0 domain`), and
 * simple adblock syntax (`||domain^`). Lines are classified into domains vs IP/CIDR.
 */
object RuleProviderFetcher {

    data class Parsed(val domains: List<String>, val ips: List<String>)

    private const val MAX_ENTRIES = 5000

    /** Ruleset bodies are plain text lists — 8 MiB is generous headroom. */
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308
    )
    private val IP_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val IPV6_HINT = Regex("""^[0-9a-fA-F:]+(/\d{1,3})?$""")

    @Throws(IOException::class)
    fun fetch(url: String): Parsed {
        val text = download(url)
        val domains = LinkedHashSet<String>()
        val ips = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            val entry = normalize(raw) ?: return@forEach
            when {
                entry.startsWith("geosite:") || entry.startsWith("domain:") ||
                    entry.startsWith("keyword:") || entry.startsWith("regexp:") -> domains.add(entry)
                entry.startsWith("geoip:") -> ips.add(entry)
                IP_REGEX.matches(entry) -> ips.add(entry)
                entry.contains(":") && IPV6_HINT.matches(entry) -> ips.add(entry)
                entry.contains(".") -> domains.add("domain:$entry")
            }
            if (domains.size + ips.size >= MAX_ENTRIES) return Parsed(domains.toList(), ips.toList())
        }
        return Parsed(domains.toList(), ips.toList())
    }

    /** Strip comments, hosts prefixes and adblock markup; return a clean token or null. */
    private fun normalize(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("#") || line.startsWith("!") || line.startsWith(";")) return null
        // hosts file: "0.0.0.0 domain" / "127.0.0.1 domain"
        if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1")) {
            line = line.substringAfter(' ').trim()
        }
        // adblock: ||domain^
        line = line.removePrefix("||").substringBefore('^').substringBefore('$')
        line = line.trim().trimEnd('.', '/')
        // drop inline comments
        line = line.substringBefore('#').substringBefore(" !").trim()
        return line.takeIf { it.isNotEmpty() && !it.contains(' ') }
    }

    /**
     * SSRF-guarded, size-capped ruleset fetch. Auto-redirects are disabled so every hop — not
     * just the original URL — is re-validated against [UrlSafety] before being connected to.
     */
    @Throws(IOException::class)
    private fun download(url: String): String {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            UrlSafety.assertSafe(current)
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "V2RayEz")
            }
            try {
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect ($code) missing Location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("Fetch failed (HTTP $code)")
                if (conn.contentLengthLong > MAX_BYTES) {
                    throw IOException("Response exceeded ${MAX_BYTES / (1024 * 1024)}MB cap")
                }
                return UrlSafety.readBounded(conn.inputStream, MAX_BYTES)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects for $url")
    }
}
