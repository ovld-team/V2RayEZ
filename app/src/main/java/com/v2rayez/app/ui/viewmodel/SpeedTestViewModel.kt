package com.v2rayez.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.data.mock.MockSettingsRepository
import com.v2rayez.app.data.mock.MockVpnController
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SpeedTestPhase { IDLE, LATENCY, DOWNLOAD, UPLOAD, DONE, ERROR }

data class SpeedTestUiState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    /** True while the VPN tunnel is up, enabling the Through-VPN mode. */
    val tunnelAvailable: Boolean = false,
    /** User choice: measure through the local Xray proxy instead of directly. */
    val useTunnel: Boolean = false,
    val serverName: String? = null,
    val pingMs: Int = -1,
    val jitterMs: Int = -1,
    val downloadMbps: Double = -1.0,
    val uploadMbps: Double = -1.0,
    /** Instantaneous Mbps for the gauge while a throughput phase runs. */
    val liveMbps: Double = 0.0,
    /** Rolling Mbps samples for the running graph while testing. */
    val samples: List<Float> = emptyList(),
    /** 0..1 progress within the current phase. */
    val progress: Float = 0f,
    val error: String? = null
) {
    val running: Boolean get() = phase == SpeedTestPhase.LATENCY || phase == SpeedTestPhase.DOWNLOAD || phase == SpeedTestPhase.UPLOAD
}

/**
 * Multi-phase speed test (latency + jitter, download, upload) against Cloudflare's
 * speed endpoints. When the VPN is connected the test can run through the local
 * Xray HTTP proxy so the result reflects the actual tunnel throughput.
 */
@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val vpn: VpnController,
    private val settings: SettingsRepository
) : ViewModel() {
    constructor() : this(MockVpnController(), MockSettingsRepository())

    private val _state = MutableStateFlow(SpeedTestUiState())
    val state: StateFlow<SpeedTestUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            vpn.connectionState.collect { conn ->
                val connected = conn.status == ConnectionStatus.CONNECTED
                _state.update {
                    it.copy(
                        tunnelAvailable = connected,
                        serverName = conn.server?.name?.takeIf { _ -> connected },
                        // Follow the tunnel by default; drop tunnel mode if VPN goes down.
                        useTunnel = if (!connected) false else if (it.phase == SpeedTestPhase.IDLE) true else it.useTunnel
                    )
                }
            }
        }
    }

    fun setUseTunnel(value: Boolean) {
        if (_state.value.running) return
        _state.update { it.copy(useTunnel = value && it.tunnelAvailable) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(phase = if (it.downloadMbps >= 0) SpeedTestPhase.DONE else SpeedTestPhase.IDLE, liveMbps = 0.0, progress = 0f) }
    }

    fun start() {
        if (_state.value.running) return
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = SpeedTestPhase.LATENCY,
                    pingMs = -1, jitterMs = -1,
                    downloadMbps = -1.0, uploadMbps = -1.0,
                    liveMbps = 0.0, samples = emptyList(), progress = 0f, error = null
                )
            }
            try {
                val client = buildClient()
                runLatencyPhase(client)
                _state.update { it.copy(phase = SpeedTestPhase.DOWNLOAD, progress = 0f, liveMbps = 0.0) }
                val down = runDownloadPhase(client)
                _state.update { it.copy(downloadMbps = down, phase = SpeedTestPhase.UPLOAD, progress = 0f, liveMbps = 0.0) }
                val up = runUploadPhase(client)
                _state.update { it.copy(uploadMbps = up, phase = SpeedTestPhase.DONE, liveMbps = 0.0, progress = 1f) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.ERROR,
                        error = e.message ?: "Test failed",
                        liveMbps = 0.0
                    )
                }
            }
        }
    }

    private suspend fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (_state.value.useTunnel && _state.value.tunnelAvailable) {
            val s = settings.current()
            // Prefer HTTP inbound (Xray); fall back to SOCKS for process cores.
            val httpUp = runCatching {
                java.net.Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", s.httpPort), 400)
                    true
                }
            }.getOrDefault(false)
            if (httpUp) {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", s.httpPort)))
            } else {
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", s.socksPort)))
            }
        }
        return builder.build()
    }

    // ------------------------------------------------------------- latency
    private suspend fun runLatencyPhase(client: OkHttpClient) = withContext(Dispatchers.IO) {
        val rtts = mutableListOf<Long>()
        // Warm-up request establishes TLS/connection so it doesn't skew the samples.
        runCatching { timedGet(client, "$BASE/__down?bytes=0") }
        repeat(LATENCY_SAMPLES) { i ->
            val rtt = timedGet(client, "$BASE/__down?bytes=0")
            rtts += rtt
            _state.update { it.copy(progress = (i + 1f) / LATENCY_SAMPLES) }
        }
        val ping = rtts.min().toInt()
        val jitter = if (rtts.size > 1) {
            rtts.zipWithNext { a, b -> abs(a - b) }.average().roundToInt()
        } else 0
        _state.update { it.copy(pingMs = ping, jitterMs = jitter) }
    }

    private fun timedGet(client: OkHttpClient, url: String): Long {
        val start = System.nanoTime()
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
        return (System.nanoTime() - start) / 1_000_000
    }

    // ------------------------------------------------------------ download
    private suspend fun runDownloadPhase(client: OkHttpClient): Double = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/__down?bytes=$DOWNLOAD_BYTES").build()
        val start = System.nanoTime()
        var total = 0L
        var windowBytes = 0L
        var windowStart = start
        client.newCall(req).execute().use { resp ->
            val input = resp.body?.byteStream() ?: throw IllegalStateException("Empty response")
            val buf = ByteArray(64 * 1024)
            while (isActive) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                windowBytes += n
                val now = System.nanoTime()
                if (now - windowStart >= SAMPLE_WINDOW_NS) {
                    pushSample(windowBytes, now - windowStart, total.toFloat() / DOWNLOAD_BYTES)
                    windowBytes = 0
                    windowStart = now
                }
                if (now - start > MAX_PHASE_NS) break
            }
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        if (total <= 0L || elapsed <= 0.0) throw IllegalStateException("No data received")
        mbps(total, elapsed)
    }

    // -------------------------------------------------------------- upload
    private suspend fun runUploadPhase(client: OkHttpClient): Double = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        var windowStart = start

        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = UPLOAD_BYTES

            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(64 * 1024)
                var sent = 0L
                var windowBytes = 0L
                while (sent < UPLOAD_BYTES) {
                    val n = minOf(chunk.size.toLong(), UPLOAD_BYTES - sent).toInt()
                    sink.write(chunk, 0, n)
                    sent += n
                    windowBytes += n
                    val now = System.nanoTime()
                    if (now - windowStart >= SAMPLE_WINDOW_NS) {
                        pushSample(windowBytes, now - windowStart, sent.toFloat() / UPLOAD_BYTES)
                        windowBytes = 0
                        windowStart = now
                    }
                    if (now - start > MAX_PHASE_NS) break
                }
            }
        }
        val req = Request.Builder().url("$BASE/__up").post(body).build()
        client.newCall(req).execute().use { /* drain */ }
        val elapsed = (System.nanoTime() - start) / 1e9
        if (elapsed <= 0.0) throw IllegalStateException("Upload failed")
        mbps(UPLOAD_BYTES, elapsed)
    }

    private fun pushSample(bytes: Long, windowNs: Long, progress: Float) {
        val mbps = mbps(bytes, windowNs / 1e9)
        _state.update {
            it.copy(
                liveMbps = mbps,
                progress = progress.coerceIn(0f, 1f),
                samples = (it.samples + mbps.toFloat()).takeLast(MAX_SAMPLES)
            )
        }
    }

    private fun mbps(bytes: Long, seconds: Double): Double =
        if (seconds <= 0.0) 0.0 else bytes * 8.0 / 1_000_000.0 / seconds

    private companion object {
        const val BASE = "https://speed.cloudflare.com"
        const val LATENCY_SAMPLES = 6
        const val DOWNLOAD_BYTES = 60_000_000L
        const val UPLOAD_BYTES = 15_000_000L
        const val SAMPLE_WINDOW_NS = 250_000_000L
        /** Cap each throughput phase at ~12s so slow links still finish. */
        const val MAX_PHASE_NS = 12_000_000_000L
        const val MAX_SAMPLES = 60
    }
}
