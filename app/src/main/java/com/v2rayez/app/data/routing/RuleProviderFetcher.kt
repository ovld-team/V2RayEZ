package com.v2rayez.app.data.routing

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

    @Throws(IOException::class)
    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "V2RayEz")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Fetch failed (HTTP $code)")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
