package com.v2rayez.app

import com.v2rayez.app.data.download.AttemptKind
import com.v2rayez.app.data.download.DownloadError
import com.v2rayez.app.data.download.DownloadOutcome
import com.v2rayez.app.data.download.DownloadTransport
import com.v2rayez.app.domain.model.DownloadMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/**
 * [DownloadTransport] uses a tiny loopback-only fake HTTP server ([FakeHttpServer]) so these
 * tests exercise real socket I/O (timeouts/retries/cancel) without touching the internet.
 */
class DownloadTransportTest {

    private fun transport() = DownloadTransport(OkHttpClient())
    // ---- pure planning / error-mapping helpers ----

    @Test
    fun planAttemptsDirectModeIsAlwaysDirectOnly() {
        assertEquals(
            listOf(AttemptKind.DIRECT),
            DownloadTransport.planAttempts(DownloadMode.DIRECT, tunnelAvailable = true)
        )
        assertEquals(
            listOf(AttemptKind.DIRECT),
            DownloadTransport.planAttempts(DownloadMode.DIRECT, tunnelAvailable = false)
        )
    }

    @Test
    fun planAttemptsThroughPrefersProxiedButFallsBackWithoutTunnel() {
        assertEquals(
            listOf(AttemptKind.PROXIED),
            DownloadTransport.planAttempts(DownloadMode.THROUGH, tunnelAvailable = true)
        )
        assertEquals(
            listOf(AttemptKind.DIRECT),
            DownloadTransport.planAttempts(DownloadMode.THROUGH, tunnelAvailable = false)
        )
    }

    @Test
    fun planAttemptsAutoTriesDirectThenProxiedOnlyWhenTunnelAvailable() {
        assertEquals(
            listOf(AttemptKind.DIRECT, AttemptKind.PROXIED),
            DownloadTransport.planAttempts(DownloadMode.AUTO, tunnelAvailable = true)
        )
        assertEquals(
            listOf(AttemptKind.DIRECT),
            DownloadTransport.planAttempts(DownloadMode.AUTO, tunnelAvailable = false)
        )
    }

    @Test
    fun mapErrorProducesTypedVariants() {
        val url = "https://example.invalid/pack.zip"
        assertTrue(DownloadTransport.mapError(url, SocketTimeoutException("timeout")) is DownloadError.Timeout)
        assertTrue(DownloadTransport.mapError(url, UnknownHostException("host")) is DownloadError.Unreachable)
        assertTrue(DownloadTransport.mapError(url, ConnectException("refused")) is DownloadError.Unreachable)
        assertTrue(DownloadTransport.mapError(url, IOException("boom")) is DownloadError.Io)
        assertTrue(DownloadTransport.mapError(url, RuntimeException("other")) is DownloadError.Io)
    }

    // ---- real socket I/O against a loopback fake server ----

    @Test
    fun directDownloadSucceedsAndWritesBytes(): Unit = runBlocking {
        val body = "hello-pack-bytes".toByteArray()
        val server = FakeHttpServer { FakeHttpServer.Response(200, body) }
        try {
            val dir = createTempDirectory("dlt-ok").toFile()
            val dest = java.io.File(dir, "out.bin")
            val transport = transport()
            val outcome = transport.download(server.url("/pack.bin"), dest, mode = DownloadMode.DIRECT)
            val success = outcome as DownloadOutcome.Success
            assertEquals(body.size.toLong(), success.bytes)
            assertFalse(success.viaProxy)
            assertEquals(String(body), dest.readText())
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }

    @Test
    fun throughModeWithoutTunnelFallsBackToDirect(): Unit = runBlocking {
        val server = FakeHttpServer { FakeHttpServer.Response(200, "ok".toByteArray()) }
        try {
            val dir = createTempDirectory("dlt-through").toFile()
            val dest = java.io.File(dir, "out.bin")
            val transport = transport() // no ProxyEndpointProvider set -> no active tunnel
            val outcome = transport.download(server.url("/pack.bin"), dest, mode = DownloadMode.THROUGH)
            val success = outcome as DownloadOutcome.Success
            assertFalse(success.viaProxy)
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }

    @Test
    fun httpClientErrorDoesNotRetry(): Unit = runBlocking {
        val server = FakeHttpServer { FakeHttpServer.Response(404, ByteArray(0)) }
        try {
            val dir = createTempDirectory("dlt-404").toFile()
            val dest = java.io.File(dir, "out.bin")
            val transport = transport()
            val outcome = transport.download(
                server.url("/missing.bin"),
                dest,
                mode = DownloadMode.DIRECT,
                maxAttemptsPerKind = 3,
                retryBackoffMs = 5
            )
            val failed = outcome as DownloadOutcome.Failed
            assertTrue(failed.error is DownloadError.HttpStatus)
            assertEquals(404, (failed.error as DownloadError.HttpStatus).code)
            assertEquals(1, server.requestCount.get())
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }

    @Test
    fun serverErrorRetriesUpToMaxAttempts(): Unit = runBlocking {
        val server = FakeHttpServer { FakeHttpServer.Response(503, ByteArray(0)) }
        try {
            val dir = createTempDirectory("dlt-503").toFile()
            val dest = java.io.File(dir, "out.bin")
            val transport = transport()
            val outcome = transport.download(
                server.url("/flaky.bin"),
                dest,
                mode = DownloadMode.DIRECT,
                maxAttemptsPerKind = 3,
                retryBackoffMs = 5
            )
            assertTrue(outcome is DownloadOutcome.Failed)
            assertEquals(3, server.requestCount.get())
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }

    @Test
    fun unreachablePortMapsToTypedFailure(): Unit = runBlocking {
        // Bind + immediately close to get a port nothing is listening on.
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val deadPort = probe.localPort
        probe.close()

        val dir = createTempDirectory("dlt-unreachable").toFile()
        val dest = java.io.File(dir, "out.bin")
        val transport = transport()
        val outcome = transport.download(
            "http://127.0.0.1:$deadPort/pack.bin",
            dest,
            mode = DownloadMode.DIRECT,
            maxAttemptsPerKind = 1,
            connectTimeoutMs = 2_000
        )
        assertTrue(outcome is DownloadOutcome.Failed)
        assertFalse(dest.exists())
        dir.deleteRecursively()
    }

    @Test
    fun cancellationAbortsDownloadAndCleansTempFile(): Unit = runBlocking {
        val server = FakeHttpServer { FakeHttpServer.Response(200, "slow-body".toByteArray(), delayMs = 3_000) }
        try {
            val dir = createTempDirectory("dlt-cancel").toFile()
            val dest = java.io.File(dir, "out.bin")
            val transport = transport()
            val deferred = async {
                transport.download(server.url("/slow.bin"), dest, mode = DownloadMode.DIRECT, connectTimeoutMs = 10_000)
            }
            delay(200)
            deferred.cancel()
            var threw = false
            try {
                deferred.await()
            } catch (c: CancellationException) {
                threw = true
            }
            assertTrue(threw)
            assertFalse(dest.exists())
            assertFalse(java.io.File(dir, "out.bin.part").exists())
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }

    @Test
    fun autoWithTunnelFastFailsDirectLegWithSingleAttempt(): Unit = runBlocking {
        // 503 on every request: the DIRECT leg must burn only ONE attempt (not the full retry
        // budget) when a PROXIED fallback exists, so censored-clearnet users reach the tunnel
        // path quickly. The proxied leg here points at a dead SOCKS port, so overall it fails.
        val server = FakeHttpServer { FakeHttpServer.Response(503, ByteArray(0)) }
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val deadSocksPort = probe.localPort
        probe.close()
        try {
            val dir = createTempDirectory("dlt-fastfail").toFile()
            val dest = java.io.File(dir, "out.bin")
            val transport = transport()
            transport.setProxyEndpointProvider { java.net.InetSocketAddress("127.0.0.1", deadSocksPort) }
            val outcome = transport.download(
                server.url("/pack.bin"),
                dest,
                mode = DownloadMode.AUTO,
                maxAttemptsPerKind = 3,
                retryBackoffMs = 5,
                connectTimeoutMs = 2_000
            )
            assertTrue(outcome is DownloadOutcome.Failed)
            assertEquals(1, server.requestCount.get())
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }

    @Test
    fun socketProtectorIsInvokedForDirectAttempts(): Unit = runBlocking {
        val server = FakeHttpServer { FakeHttpServer.Response(200, "x".toByteArray()) }
        try {
            val dir = createTempDirectory("dlt-protect").toFile()
            val dest = java.io.File(dir, "out.bin")
            val calls = AtomicInteger(0)
            val transport = transport()
            transport.setSocketProtector { socket -> calls.incrementAndGet(); true }
            val outcome = transport.download(server.url("/p.bin"), dest, mode = DownloadMode.DIRECT)
            assertTrue(outcome is DownloadOutcome.Success)
            assertTrue(calls.get() >= 1)
            dir.deleteRecursively()
        } finally {
            server.stop()
        }
    }
}

/** Minimal loopback-only HTTP/1.1 server for [DownloadTransportTest] — no external network. */
private class FakeHttpServer(private val handler: (String) -> Response) {
    data class Response(val status: Int, val body: ByteArray, val delayMs: Long = 0)

    val requestCount = AtomicInteger(0)
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            Thread {
                socket.use { s ->
                    runCatching {
                        val input = s.getInputStream().bufferedReader()
                        val requestLine = input.readLine() ?: return@runCatching
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        requestCount.incrementAndGet()
                        val resp = handler(requestLine)
                        if (resp.delayMs > 0) Thread.sleep(resp.delayMs)
                        val statusText = if (resp.status in 200..299) "OK" else "Error"
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 ${resp.status} $statusText\r\n".toByteArray())
                        out.write("Content-Length: ${resp.body.size}\r\n".toByteArray())
                        out.write("Connection: close\r\n\r\n".toByteArray())
                        out.write(resp.body)
                        out.flush()
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    fun stop() {
        runCatching { server.close() }
    }
}
