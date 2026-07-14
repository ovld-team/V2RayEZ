package com.v2rayez.app.data.download

import android.util.Log
import com.v2rayez.app.domain.model.DownloadMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Typed download failures for on-demand core/addon packs. Never a bare [Exception] surfaces
 * to callers — every failure mode is named so the UI can show an actionable message.
 */
sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(url: String) : DownloadError("Timed out downloading $url")
    class Unreachable(url: String, cause: Throwable? = null) : DownloadError("Network unreachable: $url", cause)
    class HttpStatus(val code: Int, url: String) : DownloadError("HTTP $code for $url")
    class Cancelled(url: String) : DownloadError("Download cancelled: $url")
    class NoRoute(url: String) : DownloadError("No active tunnel for THROUGH download: $url")
    class Io(url: String, cause: Throwable) : DownloadError("I/O error for $url: ${cause.message}", cause)
}

/** Outcome of [DownloadTransport.download]. */
sealed class DownloadOutcome {
    data class Success(val file: File, val bytes: Long, val viaProxy: Boolean) : DownloadOutcome()
    data class Failed(val error: DownloadError) : DownloadOutcome()
}

/**
 * Bridge to [android.net.VpnService.protect] so a direct-mode download socket escapes this
 * app's own tunnel instead of looping back into the TUN it owns. Set by the VPN service while
 * a session is active; left `null` (no-op) otherwise — most downloads happen before/without an
 * active tunnel and need no protection.
 */
fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

/**
 * Supplies the local SOCKS endpoint of the currently running proxy core (if any), used for
 * [DownloadMode.THROUGH] / the AUTO fallback so a download can ride the already-established
 * tunnel instead of a raw clearnet connection.
 */
fun interface ProxyEndpointProvider {
    fun activeSocksEndpoint(): InetSocketAddress?
}

/**
 * Downloads on-demand core/addon packs honoring [DownloadMode]:
 * - [DownloadMode.DIRECT]: clearnet only, via a socket protected against this app's own tunnel.
 * - [DownloadMode.THROUGH]: forced through the active proxy/VPN's local SOCKS endpoint; falls
 *   back to DIRECT when no tunnel is running (nothing to route through).
 * - [DownloadMode.AUTO]: try DIRECT first, then THROUGH if a tunnel is available and DIRECT failed.
 *
 * Retries with backoff per attempt kind; a whole [download] call is cancellable via structured
 * concurrency (cancel the coroutine/job) or by [cancel] with the same `tag`.
 */
@Singleton
class DownloadTransport @Inject constructor(
    private val baseClient: OkHttpClient
) {

    @Volatile private var socketProtector: SocketProtector? = null
    @Volatile private var proxyEndpointProvider: ProxyEndpointProvider? = null
    private val activeCalls = ConcurrentHashMap<String, Call>()
    /** Tags whose download must stop for good — checked by the retry loop, not just the live call. */
    private val cancelledTags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun setSocketProtector(protector: SocketProtector?) {
        socketProtector = protector
    }

    fun setProxyEndpointProvider(provider: ProxyEndpointProvider?) {
        proxyEndpointProvider = provider
    }

    /**
     * Cancel an in-flight [download] started with a matching `tag`, if any. Marks the tag so the
     * retry loop exits too — cancelling only the live OkHttp call would let the next retry (or
     * the PROXIED fallback leg) restart the download.
     */
    fun cancel(tag: String) {
        cancelledTags.add(tag)
        activeCalls.remove(tag)?.cancel()
    }

    /**
     * Small GET → UTF-8 string (GitHub API / SHA256SUMS). Honors [mode] the same way as [download].
     */
    suspend fun downloadText(
        url: String,
        mode: DownloadMode = DownloadMode.AUTO,
        tag: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("dl-text-", ".tmp", File(System.getProperty("java.io.tmpdir")!!))
        try {
            when (val outcome = download(url, tmp, mode = mode, tag = tag)) {
                is DownloadOutcome.Success -> tmp.readText(Charsets.UTF_8)
                is DownloadOutcome.Failed -> {
                    Log.w(TAG, "downloadText failed: ${outcome.error.message}")
                    null
                }
            }
        } finally {
            tmp.delete()
        }
    }

    suspend fun download(
        url: String,
        destination: File,
        mode: DownloadMode = DownloadMode.AUTO,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
        maxAttemptsPerKind: Int = DEFAULT_MAX_ATTEMPTS,
        retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
        tag: String? = null
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (tag != null) cancelledTags.remove(tag) // fresh download resets an old cancel mark
        val tunnelEndpoint = proxyEndpointProvider?.activeSocksEndpoint()
        val plan = planAttempts(mode, tunnelEndpoint != null)
        if (mode == DownloadMode.THROUGH && tunnelEndpoint == null) {
            Log.w(TAG, "THROUGH requested but no active tunnel — falling back to DIRECT for $url")
        }
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, "${destination.name}.part")
        var lastError: DownloadError = DownloadError.Unreachable(url)
        val hasProxiedFallback = plan.contains(AttemptKind.PROXIED)
        for (kind in plan) {
            if (kind == AttemptKind.PROXIED && tunnelEndpoint == null) {
                lastError = DownloadError.NoRoute(url)
                continue
            }
            // Fast-fail the clearnet leg (1 try) when a tunnel fallback follows: in a censored
            // network the direct path is dead, so don't spend the full retry/backoff budget on it
            // before routing through the tunnel. The tunnel leg keeps the full retry budget.
            val attemptsForKind = if (kind == AttemptKind.DIRECT && hasProxiedFallback) 1 else maxAttemptsPerKind
            var attempt = 0
            while (attempt < attemptsForKind) {
                attempt++
                currentCoroutineContext().ensureActive()
                val client = buildClient(kind, tunnelEndpoint, connectTimeoutMs, readTimeoutMs)
                try {
                    val bytes = executeDownload(client, url, tmp, tag)
                    if (!tmp.renameTo(destination)) {
                        tmp.copyTo(destination, overwrite = true)
                        tmp.delete()
                    }
                    return@withContext DownloadOutcome.Success(destination, bytes, kind == AttemptKind.PROXIED)
                } catch (c: CancellationException) {
                    tmp.delete()
                    throw c
                } catch (t: Throwable) {
                    tmp.delete()
                    if (tag != null && tag in cancelledTags) {
                        return@withContext DownloadOutcome.Failed(DownloadError.Cancelled(url))
                    }
                    lastError = mapError(url, t)
                    Log.w(TAG, "download attempt failed ($kind #$attempt): ${lastError.message}")
                    val statusErr = lastError as? DownloadError.HttpStatus
                    if (statusErr != null && statusErr.code in 400..499) break
                    if (attempt < attemptsForKind) delay(retryBackoffMs * attempt)
                } finally {
                    if (tag != null) activeCalls.remove(tag)
                }
            }
        }
        DownloadOutcome.Failed(lastError)
    }

    private fun buildClient(
        kind: AttemptKind,
        tunnelEndpoint: InetSocketAddress?,
        connectTimeoutMs: Long,
        readTimeoutMs: Long
    ): OkHttpClient {
        // Reuse shared dispatcher/connection pool from Hilt OkHttp; override only per-attempt knobs.
        val builder = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(connectTimeoutMs + readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
            .socketFactory(SocketFactory.getDefault())
        when (kind) {
            AttemptKind.DIRECT -> {
                socketProtector?.let { builder.socketFactory(ProtectingSocketFactory(it)) }
            }
            AttemptKind.PROXIED -> {
                if (tunnelEndpoint != null) {
                    builder.proxy(Proxy(Proxy.Type.SOCKS, tunnelEndpoint))
                }
            }
        }
        return builder.build()
    }

    private suspend fun executeDownload(client: OkHttpClient, url: String, tmp: File, tag: String?): Long {
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        if (tag != null) activeCalls[tag] = call
        val response = executeCancellable(call)
        return response.use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
            val body = resp.body ?: throw IOException("Empty response body for $url")
            var total = 0L
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                }
            }
            total
        }
    }

    private suspend fun executeCancellable(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // resume-with-onCancellation: if the coroutine is cancelled between the
                // isCancelled check and delivery, the response body is still closed.
                if (!cont.isCancelled) {
                    cont.resume(response) { _ -> runCatching { response.close() } }
                } else {
                    response.close()
                }
            }
        })
    }

    companion object {
        private const val TAG = "DownloadTransport"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_BACKOFF_MS = 800L

        /** Pure decision of which transport(s) to try, and in what order, for [mode]. */
        internal fun planAttempts(mode: DownloadMode, tunnelAvailable: Boolean): List<AttemptKind> =
            when (mode) {
                DownloadMode.DIRECT -> listOf(AttemptKind.DIRECT)
                DownloadMode.THROUGH ->
                    if (tunnelAvailable) listOf(AttemptKind.PROXIED) else listOf(AttemptKind.DIRECT)
                DownloadMode.AUTO ->
                    if (tunnelAvailable) listOf(AttemptKind.DIRECT, AttemptKind.PROXIED) else listOf(AttemptKind.DIRECT)
            }

        /** Pure mapping from a caught [Throwable] to a typed [DownloadError]. */
        internal fun mapError(url: String, t: Throwable): DownloadError = when (t) {
            is HttpStatusException -> DownloadError.HttpStatus(t.code, url)
            is SocketTimeoutException -> DownloadError.Timeout(url)
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                DownloadError.Unreachable(url, t)
            // OkHttp's callTimeout throws InterruptedIOException("timeout") — that's a timeout,
            // not a user cancel. Only a genuine interrupt maps to Cancelled.
            is InterruptedIOException ->
                if (t.message?.contains("timeout", ignoreCase = true) == true) {
                    DownloadError.Timeout(url)
                } else {
                    DownloadError.Cancelled(url)
                }
            is IOException -> DownloadError.Io(url, t)
            else -> DownloadError.Io(url, t)
        }
    }
}

internal enum class AttemptKind { DIRECT, PROXIED }

private class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Calls [protector] on every socket this factory creates, before OkHttp connects it. */
private class ProtectingSocketFactory(private val protector: SocketProtector) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also {
        // An unprotected socket under an active VPN loops back into our own TUN and hangs
        // until timeout — surface the failure instead of silently proceeding.
        if (!protector.protect(it)) {
            Log.w("DownloadTransport", "VpnService.protect failed — DIRECT socket may route into own tunnel")
        }
    }

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}
