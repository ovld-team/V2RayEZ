package com.v2rayez.app.data.sni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/** Result of probing one candidate SNI domain via the Cloudflare trace endpoint. */
data class SniScanResult(
    val domain: String,
    val success: Boolean,
    /** Average round-trip latency of the successful tries (ms); 9999 when unreachable. */
    val pingMs: Int,
    /** Percentage of tries that succeeded (0..100). */
    val stability: Int = 0,
    /** Ranking score: stability * 10 - ping (higher is better). */
    val score: Int = 0,
    /** DNS-resolved A record for the domain. */
    val resolvedIp: String = "N/A",
    /** Edge IP reported by Cloudflare's trace (`ip=`). */
    val cfIp: String = "",
    /** Cloudflare colo / datacenter code (`colo=`). */
    val colo: String = "",
    /** Country reported by the trace (`loc=`). */
    val country: String = "",
    /** Cloudflare ray id (`ray=`). */
    val ray: String = ""
)

/**
 * SNI scanner following the UAC-SNI-Spoofer model: for each candidate domain it issues an
 * HTTPS request to `https://<domain>/cdn-cgi/trace`, measures the round-trip latency and
 * parses the Cloudflare trace payload (`ip` / `colo` / `loc` / `ray`). Each domain is probed
 * [DEFAULT_TRIES] times to derive a stability percentage; the surviving results are ranked by
 * stability (desc) then latency (asc) so the caller can auto-apply the best-performing SNI.
 *
 * When [serverIp] is provided, a domain only counts as a success if its Cloudflare edge IP
 * matches it (useful for CDN-fronted configs); left blank, any reachable domain qualifies.
 */
@Singleton
class SniScanner @Inject constructor() {

    suspend fun scan(
        domains: List<String>,
        serverIp: String = "",
        tries: Int = DEFAULT_TRIES,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (Int, Int, SniScanResult) -> Unit = { _, _, _ -> }
    ): List<SniScanResult> = coroutineScope {
        val total = domains.size
        val done = AtomicInteger(0)
        val gate = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
        val expectedIp = serverIp.trim()
        domains.map { domain ->
            async(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val result = gate.withPermit {
                    currentCoroutineContext().ensureActive()
                    check(domain, expectedIp, tries, timeoutMs)
                }
                onProgress(done.incrementAndGet(), total, result)
                result
            }
        }.awaitAll()
            .filter { it.success }
            .sortedWith(
                compareByDescending<SniScanResult> { it.stability }
                    .thenBy { it.pingMs }
                    .thenBy { it.domain }
            )
    }

    private suspend fun check(
        domain: String,
        expectedIp: String,
        tries: Int,
        timeoutMs: Int
    ): SniScanResult {
        currentCoroutineContext().ensureActive()
        val resolved = resolve(domain)
        val attempts = tries.coerceAtLeast(1)
        var ok = 0
        var pingTotal = 0
        var cfIp = ""; var colo = ""; var country = ""; var ray = ""
        var matched = false
        repeat(attempts) {
            currentCoroutineContext().ensureActive()
            val trace = requestTrace(domain, timeoutMs)
            if (trace != null) {
                ok++
                pingTotal += trace.pingMs
                cfIp = trace.cfIp; colo = trace.colo; country = trace.country; ray = trace.ray
                if (expectedIp.isNotEmpty() && expectedIp == trace.cfIp) matched = true
            }
        }
        val stability = ((ok.toDouble() / attempts) * 100).toInt()
        val ping = if (ok > 0) pingTotal / ok else UNREACHABLE_MS
        val success = ok > 0 && (expectedIp.isEmpty() || matched)
        return SniScanResult(
            domain = domain,
            success = success,
            pingMs = ping,
            stability = stability,
            score = stability * 10 - ping,
            resolvedIp = resolved,
            cfIp = cfIp,
            colo = colo,
            country = country,
            ray = ray
        )
    }

    private fun requestTrace(domain: String, timeoutMs: Int): Trace? {
        val start = System.nanoTime()
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL("https://$domain/cdn-cgi/trace")
            conn = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = minOf(5000, timeoutMs)
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.connect()
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            if (!body.contains("ip=")) return null
            val ping = ((System.nanoTime() - start) / 1_000_000).toInt()
            var cfIp = ""; var colo = ""; var country = ""; var ray = ""
            body.split('\n').forEach { line ->
                when {
                    line.startsWith("ip=") -> cfIp = line.substring(3).trim()
                    line.startsWith("colo=") -> colo = line.substring(5).trim()
                    line.startsWith("loc=") -> country = line.substring(4).trim()
                    line.startsWith("ray=") -> ray = line.substring(4).trim()
                }
            }
            Trace(ping, cfIp, colo, country, ray)
        } catch (_: Exception) {
            // Drain and close the error stream too, or the underlying socket lingers
            // until the Cleaner flags it ("A resource failed to call end").
            runCatching { conn?.errorStream?.use { it.readBytes() } }
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun resolve(domain: String): String =
        runCatching { InetAddress.getByName(domain).hostAddress ?: "N/A" }.getOrDefault("N/A")

    private data class Trace(
        val pingMs: Int,
        val cfIp: String,
        val colo: String,
        val country: String,
        val ray: String
    )

    private companion object {
        // Keep low: hundreds of domains × tries exhaust sockets and thrash the UI.
        const val DEFAULT_CONCURRENCY = 4
        const val MAX_CONCURRENCY = 8
        const val DEFAULT_TRIES = 2
        const val DEFAULT_TIMEOUT_MS = 4000
        const val UNREACHABLE_MS = 9999
        const val USER_AGENT = "Mozilla/5.0 V2RayEz-SNI"
    }
}
