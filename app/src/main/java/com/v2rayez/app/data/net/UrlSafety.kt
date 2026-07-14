package com.v2rayez.app.data.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URL

/**
 * SSRF guard for user-supplied fetch URLs (subscriptions, rule providers): refuses anything that
 * resolves to loopback / private-LAN / link-local (incl. cloud metadata `169.254.169.254`) /
 * multicast / wildcard addresses, and caps how many bytes a response body may contain.
 *
 * Both subscription and rule-provider fetch loops re-validate on every redirect hop (see
 * [RealServerRepository.fetch] / [RuleProviderFetcher.download]) — a single pre-flight check on
 * the original URL would not catch a 30x that repoints at an internal host.
 */
object UrlSafety {

    /** Thrown instead of returning a `Boolean` so callers can't silently ignore a failed check. */
    class UnsafeUrlException(message: String) : IOException(message)

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /** Validate [rawUrl]'s scheme and every address its host resolves to. */
    @Throws(UnsafeUrlException::class)
    fun assertSafe(rawUrl: String) {
        val url = runCatching { URL(rawUrl) }.getOrNull()
            ?: throw UnsafeUrlException("Malformed URL")
        val scheme = url.protocol?.lowercase().orEmpty()
        if (scheme !in ALLOWED_SCHEMES) {
            throw UnsafeUrlException("Unsupported scheme: $scheme")
        }
        val host = url.host
        if (host.isNullOrBlank()) throw UnsafeUrlException("Missing host")
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) throw UnsafeUrlException("Could not resolve host: $host")
        val blocked = addresses.firstOrNull { isBlockedAddress(it) }
        if (blocked != null) {
            throw UnsafeUrlException("Refusing private/loopback address: ${blocked.hostAddress}")
        }
    }

    /** True when [addr] is loopback, link-local (incl. metadata `169.254.0.0/16`), RFC1918/ULA, multicast, or wildcard. */
    fun isBlockedAddress(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress ||
            addr.isAnyLocalAddress

    /** Reads at most [maxBytes] of [input] as UTF-8 text; throws once the cap is exceeded. */
    @Throws(IOException::class)
    fun readBounded(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response exceeded ${maxBytes / (1024 * 1024)}MB cap")
            buffer.write(chunk, 0, n)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}
