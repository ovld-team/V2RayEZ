package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the vendored `libv2ray` Xray core (AndroidLibXrayLite).
 *
 * This build uses the in-core TUN handler: the VPN file descriptor is passed
 * straight to [startLoop], so no external tun2socks process is required.
 *
 * All start / stop / outbound-delay probes share [exclusive] so only one native
 * Xray instance is created at a time. Parallel ping/SNI latency tests queue
 * instead of spawning overlapping cores (which OOMs and janks the UI).
 */
@Singleton
class V2RayCore @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "V2RayCore"
        private val GEO_FILES = listOf("geoip.dat", "geosite.dat")

        /** Mini cn+private geoip packaged inside libv2ray.aar's assets (~76 KB). */
        private const val MINI_GEOIP_ASSET = "geoip-only-cn-private.dat"

        /** Copy the packaged mini geoip in as `geoip.dat` unless a (full) one already exists. */
        fun copyMiniGeoipFallback(context: Context, targetDir: File) {
            val out = File(targetDir, "geoip.dat")
            if (out.exists() && out.length() > 0) return
            runCatching {
                context.assets.open(MINI_GEOIP_ASSET).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { Log.e(TAG, "mini geoip fallback copy failed", it) }
        }
    }

    private val stateLock = Any()
    /** Serializes startLoop / stopLoop / measureOutboundDelay across the process. */
    private val exclusive = Mutex()
    private var controller: CoreController? = null
    @Volatile private var initialized = false
    @Volatile private var statsErrorLogged = false
    @Volatile private var startError: String? = null
    @Volatile private var lastStatus: String? = null

    /** Status callback: (code, message) emitted from the Go core. */
    var onStatus: ((Long, String) -> Unit)? = null

    val isRunning: Boolean get() = synchronized(stateLock) { controller?.isRunning == true }

    /**
     * Startup-only failure detail (init / startLoop). Never falls back to mid-session
     * [lastStatus] callbacks — those can poison connect-error UI after a clean start.
     */
    fun lastStartError(): String? = startError

    /** Copy the bundled geo assets into files dir and initialise the core env once. */
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(stateLock) {
            if (initialized) return true
            val assetDir = File(appContext.filesDir, "assets").apply { mkdirs() }
            copyGeoAssets(assetDir)
            val ok = runCatching {
                Libv2ray.initCoreEnv(assetDir.absolutePath, "")
            }.onFailure {
                startError = throwableDetail("Xray environment initialization failed", it)
                Log.e(TAG, "initCoreEnv failed", it)
            }.isSuccess
            if (ok) initialized = true
            return ok
        }
    }

    /**
     * Start the core with a generated [configJson], bound to the VPN [tunFd].
     * Suspends until any in-flight latency probe (or previous start/stop) finishes.
     */
    suspend fun startLoop(configJson: String, tunFd: Int): Boolean = exclusive.withLock {
        withContext(Dispatchers.IO) { startLoopUnlocked(configJson, tunFd) }
    }

    /**
     * Start Xray with SOCKS/HTTP (and other non-TUN) inbounds only — no VpnService fd.
     *
     * Passes `tunFd = -1` to the AAR; the config must omit `tun-in` (see
     * [com.v2rayez.app.data.mitm.MitmConfigBuilder] `includeTun=false`). Shares [exclusive]
     * with [startLoop] so VPN and proxy-only never overlap.
     *
     * @return false if init fails, another core is already running, or native start throws.
     */
    suspend fun startProxyLoop(configJson: String): Boolean = exclusive.withLock {
        withContext(Dispatchers.IO) {
            val existing = synchronized(stateLock) { controller }
            if (existing?.isRunning == true) {
                Log.w(TAG, "startProxyLoop refused: core already running")
                return@withContext false
            }
            startLoopUnlocked(configJson, tunFd = -1)
        }
    }

    private fun startLoopUnlocked(configJson: String, tunFd: Int): Boolean {
        startError = null
        lastStatus = null
        if (!ensureInitialized()) return false
        val existing = synchronized(stateLock) { controller }
        if (existing?.isRunning == true) return true

        // Only fold status into startError while startLoop is in flight — runtime
        // onEmitStatus after a successful start must not overwrite startup diagnostics.
        val capturingStartStatus = AtomicBoolean(true)
        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(code: Long, message: String?): Long {
                val detail = message.orEmpty().trim()
                if (detail.isNotBlank()) {
                    lastStatus = "Xray status $code: $detail"
                    if (capturingStartStatus.get() &&
                        (code != 0L || detail.looksLikeCoreFailure())
                    ) {
                        startError = lastStatus
                    }
                }
                runCatching { onStatus?.invoke(code, detail) }
                    .onFailure { Log.w(TAG, "onStatus consumer failed", it) }
                return 0L
            }
        }
        return runCatching {
            val c = Libv2ray.newCoreController(handler)
            c.startLoop(configJson, tunFd)
            synchronized(stateLock) { controller = c }
            if (!c.isRunning) {
                capturingStartStatus.set(false)
                startError = startError ?: lastStatus ?: "Xray returned without a running core"
                false
            } else {
                capturingStartStatus.set(false)
                startError = null
                true
            }
        }.onFailure {
            capturingStartStatus.set(false)
            startError = throwableDetail("Xray start failed", it)
            Log.e(TAG, "startLoop failed (tunFd=$tunFd): $startError", it)
        }.getOrDefault(false)
    }

    private fun String.looksLikeCoreFailure(): Boolean {
        val lower = lowercase()
        return listOf("error", "failed", "failure", "invalid", "cannot", "unable", "panic", "fatal")
            .any(lower::contains)
    }

    private fun throwableDetail(prefix: String, throwable: Throwable): String {
        val chain = generateSequence(throwable) { it.cause?.takeIf { cause -> cause !== it } }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(4)
            .joinToString(" → ")
        return if (chain.isBlank()) "$prefix (${throwable.javaClass.simpleName})" else "$prefix: $chain"
    }

    /** Stop the running tunnel core; waits for any in-flight exclusive op. */
    suspend fun stopLoop() = exclusive.withLock {
        withContext(Dispatchers.IO) { stopLoopUnlocked() }
    }

    /**
     * Blocking stop for cleanup paths that are not suspend (e.g. [failAndStop]).
     * Prefer [stopLoop] from coroutines.
     */
    fun stopLoopBlocking() {
        runBlocking(Dispatchers.IO) { stopLoop() }
    }

    private fun stopLoopUnlocked() {
        val c = synchronized(stateLock) {
            val current = controller
            controller = null
            current
        }
        runCatching { c?.stopLoop() }.onFailure { Log.e(TAG, "stopLoop failed", it) }
    }

    /** Byte deltas since the last query (counters reset), split by outbound kind. */
    data class TrafficSnapshot(
        val proxyUp: Long = 0L,
        val proxyDown: Long = 0L,
        val directUp: Long = 0L,
        val directDown: Long = 0L
    ) {
        val totalUp: Long get() = proxyUp + directUp
        val totalDown: Long get() = proxyDown + directDown
    }

    /**
     * Uplink/downlink bytes for ALL outbounds since the last query (counters reset).
     * Uses `queryAllOutboundTrafficStats` ("tag,direction,value;..."), so traffic that
     * routing rules send to tags other than "proxy" (tor, warp, chained hops, direct)
     * is still counted instead of silently reading 0.
     */
    fun queryTrafficStats(): TrafficSnapshot {
        val c = synchronized(stateLock) { controller } ?: return TrafficSnapshot()
        val raw = runCatching { c.queryAllOutboundTrafficStats() }
            .onFailure {
                if (!statsErrorLogged) {
                    statsErrorLogged = true
                    Log.w(TAG, "queryAllOutboundTrafficStats failed", it)
                }
            }
            .onSuccess { statsErrorLogged = false }
            .getOrNull() ?: return TrafficSnapshot()
        var proxyUp = 0L; var proxyDown = 0L; var directUp = 0L; var directDown = 0L
        raw.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(',')
            if (parts.size != 3) return@forEach
            val tag = parts[0]
            val value = parts[2].toLongOrNull() ?: return@forEach
            if (tag == ConfigBuilder.TAG_BLOCK || tag == ConfigBuilder.TAG_DNS) return@forEach
            val direct = tag == ConfigBuilder.TAG_DIRECT
            when (parts[1]) {
                "uplink" -> if (direct) directUp += value else proxyUp += value
                "downlink" -> if (direct) directDown += value else proxyDown += value
            }
        }
        return TrafficSnapshot(proxyUp, proxyDown, directUp, directDown)
    }

    /**
     * Round-trip delay (ms) through the RUNNING tunnel's outbound; -1 on failure.
     * Not exclusive: the live controller is already up.
     */
    fun measureConnectedDelay(url: String = "https://www.gstatic.com/generate_204"): Long {
        val c = synchronized(stateLock) { controller } ?: return -1L
        return runCatching { c.measureDelay(url) }.getOrDefault(-1L)
    }

    /**
     * Measure real handshake latency (ms) with a throwaway Xray instance.
     * Queued behind [exclusive] so ping-all never overlaps cores or VPN start.
     */
    suspend fun measureDelay(
        configJson: String,
        url: String = "https://www.gstatic.com/generate_204"
    ): Long = exclusive.withLock {
        withContext(Dispatchers.IO) {
            if (synchronized(stateLock) { controller?.isRunning == true }) {
                Log.w(TAG, "measureDelay refused: VPN tunnel is active")
                return@withContext -1L
            }
            if (!ensureInitialized()) return@withContext -1L
            runCatching { Libv2ray.measureOutboundDelay(configJson, url) }.getOrDefault(-1L)
        }
    }

    fun coreVersion(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("unknown")

    private fun copyGeoAssets(target: File) {
        GEO_FILES.forEach { name ->
            val out = File(target, name)
            if (out.exists() && out.length() > 0) return@forEach
            val copied = runCatching {
                appContext.assets.open(name).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            // v0.9.50: full dats are no longer packaged (GeoAssetManager downloads them).
            // Fall back to the AAR's mini geoip so geoip:private / geoip:cn always resolve;
            // geosite has no mini fallback — ConfigBuilder gates geosite:* rules instead.
            if (!copied && name == "geoip.dat") copyMiniGeoipFallback(appContext, target)
        }
    }
}
