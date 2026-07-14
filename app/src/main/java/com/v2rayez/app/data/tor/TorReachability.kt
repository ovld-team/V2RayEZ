package com.v2rayez.app.data.tor

import com.v2rayez.app.domain.model.TorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Tor readiness checks. SOCKS TCP accept alone is **not** enough — Tor opens the
 * listener before circuits exist. Use [exitReachable] to confirm the network works.
 */
object TorReachability {

    /** True if the local SOCKS port accepts a TCP connection. */
    suspend fun socksAccepts(config: TorConfig, timeoutMs: Int = 2_000): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(config.socksHost, config.socksPort), timeoutMs)
                    s.isConnected
                }
            }.getOrDefault(false)
        }

    /**
     * HTTP HEAD through Tor SOCKS to a generate_204 endpoint.
     * Succeeds only when Tor has usable circuits (post-bootstrap).
     */
    suspend fun exitReachable(
        config: TorConfig,
        timeoutMs: Long = 12_000
    ): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(config.socksHost, config.socksPort)))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
        val urls = listOf(
            "https://www.gstatic.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            "https://check.torproject.org/api/ip"
        )
        for (url in urls) {
            val ok = runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    resp.code in 200..399 || resp.code == 204
                }
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }
}
