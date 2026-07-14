package com.v2rayez.app.data.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.v2rayez.app.data.analytics.FailureCategory
import com.v2rayez.app.data.analytics.RemoteTelemetry
import com.v2rayez.app.data.download.DownloadOutcome
import com.v2rayez.app.data.download.DownloadTransport
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.DownloadMode
import com.v2rayez.app.domain.model.ProxyCoreType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs sing-box / mihomo (or a downloaded Xray binary) as a PIE process with a local SOCKS/mixed port.
 * TUN bridging is handled separately by [HevTunBridge].
 */
@Singleton
class ProcessProxyCore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val binaryManager: CoreBinaryManager,
    private val remoteTelemetry: RemoteTelemetry
) : ProxyCore {

    companion object {
        private const val TAG = "ProcessProxyCore"
    }

    @Volatile private var activeType: ProxyCoreType = ProxyCoreType.SING_BOX
    private val processRef = AtomicReference<Process?>(null)
    @Volatile private var socksPort: Int = 0
    @Volatile private var lastVersion: String = "unknown"

    /**
     * Serializes `startProcess`/`stopProcess` so two concurrent callers (e.g. a stray retry
     * racing a teardown) can't interleave `ProcessBuilder.start()` / `Process.destroy()` calls
     * against the same [processRef] (W-06). Not reentrant — internal `*Locked` helpers must
     * never be called except while already holding [processMutex].
     */
    private val processMutex = Mutex()

    override val type: ProxyCoreType get() = activeType
    override val isRunning: Boolean get() = processRef.get()?.isAlive == true
    val isHealthy: Boolean get() = isRunning && socksPort in 1..65535
    override fun version(): String = lastVersion

    fun localSocksPort(): Int = socksPort

    /**
     * Start [type] with [configText] already bound to [listenPort].
     * [selectedVersion] is `bundled` or a downloaded tag.
     */
    suspend fun startProcess(
        type: ProxyCoreType,
        configText: String,
        selectedVersion: String,
        listenPort: Int
    ): Boolean = processMutex.withLock {
        startProcessLocked(type, configText, selectedVersion, listenPort)
    }

    private suspend fun startProcessLocked(
        type: ProxyCoreType,
        configText: String,
        selectedVersion: String,
        listenPort: Int
    ): Boolean = withContext(Dispatchers.IO) {
        stopProcessLocked()
        activeType = type
        val binary = binaryManager.resolveBinary(type, selectedVersion) ?: run {
            val reason = "No binary for ${type.label} abi=${binaryManager.deviceAbiLabel()}"
            Log.e(TAG, reason)
            runCatching { remoteTelemetry.captureVpnFailure(FailureCategory.VPN_CONNECT, reason) }
            return@withContext false
        }
        lastVersion = selectedVersion.ifBlank { CORE_VERSION_BUNDLED }
        socksPort = listenPort

        val workDir = File(context.filesDir, "core-run/${type.name.lowercase()}").apply { mkdirs() }
        val configFile = File(workDir, if (type == ProxyCoreType.CLASH) "config.yaml" else "config.json")
        configFile.writeText(configText)

        val cmd = when (type) {
            ProxyCoreType.SING_BOX -> NativeBinaryStore.processArgv(binary, listOf("run", "-c", configFile.absolutePath))
            ProxyCoreType.CLASH -> NativeBinaryStore.processArgv(binary, listOf("-d", workDir.absolutePath, "-f", configFile.absolutePath))
            ProxyCoreType.XRAY -> NativeBinaryStore.processArgv(binary, listOf("run", "-c", configFile.absolutePath))
        }
        Log.i(TAG, "Starting ${type.label} (${binaryManager.deviceAbiLabel()}): ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
        val proc = runCatching { pb.start() }.onFailure {
            Log.e(TAG, "Failed to start process", it)
            runCatching {
                remoteTelemetry.captureVpnFailure(
                    FailureCategory.VPN_CONNECT,
                    "${type.label} process start failed: ${it.javaClass.simpleName}"
                )
            }
        }.getOrNull() ?: return@withContext false
        processRef.set(proc)
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "[${type.label}] $line")
                    }
                }
            }
            if (processRef.compareAndSet(proc, null)) {
                socksPort = 0
                Log.e(TAG, "${type.label} process exited")
            }
        }, "core-log-${type.name}").apply { isDaemon = true }.start()

        try {
            // Cancellable (unlike the old Thread.sleep(400)): if the connect attempt is
            // superseded/cancelled mid-wait, we still land in the catch below and kill the
            // process we just spawned instead of leaking it as an orphaned PIE (W-07).
            delay(400)
        } catch (c: kotlinx.coroutines.CancellationException) {
            destroyQuietly(proc)
            if (processRef.compareAndSet(proc, null)) socksPort = 0
            throw c
        }
        if (!proc.isAlive) {
            val reason = "${type.label} exited early code=${proc.exitValue()}"
            Log.e(TAG, reason)
            processRef.set(null)
            socksPort = 0
            runCatching { remoteTelemetry.captureVpnFailure(FailureCategory.VPN_CONNECT, reason) }
            return@withContext false
        }
        true
    }

    suspend fun stopProcess() = processMutex.withLock { stopProcessLocked() }

    private suspend fun stopProcessLocked() = withContext(Dispatchers.IO) {
        val p = processRef.getAndSet(null) ?: return@withContext
        destroyQuietly(p)
        socksPort = 0
    }

    private fun destroyQuietly(p: Process) {
        runCatching {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
    }

    override suspend fun start(configText: String, tunFd: Int): Boolean {
        Log.w(TAG, "Use startProcess + HevTunBridge; start(config,tunFd) is unsupported")
        return false
    }

    override suspend fun stop() = stopProcess()

    override fun queryTrafficStats(): Pair<Long, Long> = 0L to 0L

    override suspend fun measureDelay(configText: String, url: String): Long = -1L

    suspend fun measureViaSocks(
        port: Int = socksPort,
        url: String = "https://www.gstatic.com/generate_204",
        timeoutMs: Int = 8_000
    ): Long = withContext(Dispatchers.IO) {
        val p = port.takeIf { it in 1..65535 } ?: return@withContext -1L
        val start = System.currentTimeMillis()
        runCatching {
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", p)))
                .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build()
            client.newCall(
                okhttp3.Request.Builder().url(url).head().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful && resp.code !in 200..399) return@withContext -1L
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }.getOrElse {
            Log.w(TAG, "measureViaSocks failed: ${it.message}")
            -1L
        }
    }
}

/**
 * Device CPU ABI used to pick the correct GitHub release asset for core downloads.
 * Prefer the ABI that matches this app's installed native libs when possible.
 */
enum class DeviceAbi(
    val androidAbi: String,
    val goArch: String,
    val mihomoArch: String,
    val xrayAssetToken: String,
    val elfMachine: Int
) {
    ARM64("arm64-v8a", "arm64", "arm64-v8", "arm64-v8a", 183),
    ARM32("armeabi-v7a", "arm", "armv7", "arm32-v7a", 40),
    X86_64("x86_64", "amd64", "amd64", "amd64", 62),
    X86("x86", "386", "386", "386", 3);

    companion object {
        fun fromAndroidAbi(abi: String): DeviceAbi? = entries.firstOrNull { it.androidAbi == abi }

        /**
         * Resolve the ABI this process should download for.
         * 1) Path segment of [Context.getApplicationInfo].nativeLibraryDir
         * 2) First entry of [Build.SUPPORTED_ABIS]
         */
        fun resolve(context: Context): DeviceAbi {
            val fromLibDir = context.applicationInfo.nativeLibraryDir
                .split('/', '\\')
                .asReversed()
                .firstNotNullOfOrNull { fromAndroidAbi(it) }
            if (fromLibDir != null) return fromLibDir
            for (abi in Build.SUPPORTED_ABIS) {
                fromAndroidAbi(abi)?.let { return it }
            }
            return ARM64
        }
    }
}

/**
 * Resolves and downloads proxy core binaries **for this device's ABI only**.
 */
@Singleton
class CoreBinaryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadTransport: DownloadTransport
) {
    companion object {
        private const val TAG = "CoreBinaryManager"
        const val BUNDLED_SING_BOX = "v1.13.14"
        const val BUNDLED_MIHOMO = "v1.19.28"
        const val BUNDLED_XRAY = "bundled-aar"
        private const val ABI_META = "abi.txt"

        /**
         * sing-box: `sing-box-*-android-arm64.tar.gz` — must not match `android-arm` ↔ `android-arm64`.
         */
        fun matchesSingBoxAndroid(name: String, abi: DeviceAbi): Boolean {
            if (!name.endsWith(".tar.gz") || name.contains("legacy", ignoreCase = true)) return false
            if (!name.contains("android-", ignoreCase = true)) return false
            val token = Regex("""android-([a-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.getOrNull(1)?.lowercase()
                ?: return false
            return token == abi.goArch
        }

        /** mihomo: `mihomo-android-arm64-v8-v1.19.28.gz` */
        fun matchesMihomoAndroid(name: String, abi: DeviceAbi): Boolean {
            if (!name.endsWith(".gz") || name.endsWith(".tar.gz")) return false
            val prefix = "mihomo-android-${abi.mihomoArch}-"
            return name.startsWith(prefix, ignoreCase = true)
        }

        /**
         * Xray: `Xray-android-arm64-v8a.zip`, `Xray-android-amd64.zip`.
         * Exact android + arch token; ignore .dgst sidecars.
         */
        fun matchesXrayAndroid(name: String, abi: DeviceAbi): Boolean {
            if (!name.endsWith(".zip", ignoreCase = true)) return false
            if (name.contains(".dgst", ignoreCase = true)) return false
            val lower = name.lowercase()
            if (!lower.startsWith("xray-android-")) return false
            return when (abi) {
                DeviceAbi.ARM64 -> lower == "xray-android-arm64-v8a.zip" ||
                    lower.startsWith("xray-android-arm64-v8a-")
                DeviceAbi.ARM32 -> lower.contains("android-arm32") || lower.contains("android-armeabi")
                DeviceAbi.X86_64 -> lower == "xray-android-amd64.zip" ||
                    lower.startsWith("xray-android-amd64-")
                DeviceAbi.X86 -> lower.contains("android-386") || lower.endsWith("android-x86.zip")
            }
        }

        /** ELF e_machine from a little-endian ELF binary. Delegates to the shared [NativeBinaryStore]. */
        fun readElfMachine(file: File): Int? = NativeBinaryStore.readElfMachine(file)
    }

    private val deviceAbi: DeviceAbi by lazy { DeviceAbi.resolve(context) }

    fun deviceAbiLabel(): String = deviceAbi.androidAbi

    fun coresDir(): File = File(context.filesDir, "cores").apply { mkdirs() }

    fun versionDir(type: ProxyCoreType, version: String): File =
        File(coresDir(), "${type.name.lowercase()}/$version").apply { mkdirs() }

    fun bundledVersionLabel(type: ProxyCoreType): String = when (type) {
        ProxyCoreType.XRAY -> BUNDLED_XRAY
        ProxyCoreType.SING_BOX -> BUNDLED_SING_BOX
        ProxyCoreType.CLASH -> BUNDLED_MIHOMO
    }

    fun installedVersions(type: ProxyCoreType): List<String> {
        val list = mutableListOf<String>()
        if (type == ProxyCoreType.XRAY || isBundledRunnable(type)) {
            list.add(CORE_VERSION_BUNDLED)
        }
        val root = File(coresDir(), type.name.lowercase())
        if (root.isDirectory) {
            root.listFiles()
                ?.filter { dir ->
                    dir.isDirectory && validateInstalledBinary(type, dir) == null
                }
                ?.map { it.name }
                ?.sortedWith(VersionOrder.descending)
                ?.let { list.addAll(it) }
        }
        return list.distinct()
    }

    /** True when the packaged jniLibs PIE for [type] exists and matches this device ABI. */
    fun isBundledRunnable(type: ProxyCoreType): Boolean = bundledNative(type) != null

    /**
     * Resolve a process binary for [type]/[version].
     * Returns null when unavailable — never silently substitutes a different version.
     */
    fun resolveBinary(type: ProxyCoreType, version: String): File? {
        if (type == ProxyCoreType.XRAY && (version == CORE_VERSION_BUNDLED || version == BUNDLED_XRAY || version.isBlank())) {
            return null // AAR path, not a process binary
        }
        if (version == CORE_VERSION_BUNDLED || version.isBlank()) {
            return bundledNative(type)
        }
        val dir = versionDir(type, version)
        val reason = validateInstalledBinary(type, dir)
        if (reason != null) {
            Log.w(TAG, "reject $type/$version: $reason")
            return null
        }
        return File(dir, binaryFileName(type))
    }

    /** Human-readable reject reason, or null if OK. */
    fun validateInstalledBinary(type: ProxyCoreType, dir: File): String? {
        val f = File(dir, binaryFileName(type))
        if (!f.exists()) return "missing binary"
        if (!f.canExecute()) return "not executable"
        if (!abiMatchesDevice(dir)) return "abi mismatch (need ${deviceAbi.androidAbi})"
        if (!elfMatchesDevice(f)) return "ELF arch mismatch (need ${deviceAbi.androidAbi})"
        return null
    }

    fun lastResolveError(type: ProxyCoreType, version: String): String {
        if (type == ProxyCoreType.XRAY && (version == CORE_VERSION_BUNDLED || version.isBlank())) {
            return "Xray uses bundled AAR"
        }
        if (version == CORE_VERSION_BUNDLED || version.isBlank()) {
            return if (isBundledRunnable(type)) "ok" else "bundled ${type.label} binary missing for ${deviceAbi.androidAbi}"
        }
        return validateInstalledBinary(type, versionDir(type, version))
            ?: "ok"
    }

    private fun binaryFileName(type: ProxyCoreType): String = when (type) {
        ProxyCoreType.XRAY -> "xray"
        ProxyCoreType.SING_BOX -> "sing-box"
        ProxyCoreType.CLASH -> "mihomo"
    }

    private fun bundledNative(type: ProxyCoreType): File? {
        val name = when (type) {
            ProxyCoreType.SING_BOX -> "libsingbox.so"
            ProxyCoreType.CLASH -> "libmihomo.so"
            ProxyCoreType.XRAY -> return null
        }
        val f = File(context.applicationInfo.nativeLibraryDir, name)
        return f.takeIf { it.exists() && elfMatchesDevice(it) }
    }

    data class RemoteRelease(
        val tag: String,
        val downloadUrl: String,
        val assetName: String,
        /** Android ABI this asset targets (e.g. arm64-v8a). */
        val abi: String,
        /** Expected sha256 hex of the downloaded asset, when known (e.g. from a `.dgst` sidecar). */
        val sha256: String? = null
    )

    suspend fun listRemoteReleases(type: ProxyCoreType, limit: Int = 15): List<RemoteRelease> =
        withContext(Dispatchers.IO) {
            val abi = deviceAbi
            val (repo, assetMatcher) = when (type) {
                ProxyCoreType.SING_BOX -> "SagerNet/sing-box" to { name: String ->
                    matchesSingBoxAndroid(name, abi)
                }
                ProxyCoreType.CLASH -> "MetaCubeX/mihomo" to { name: String ->
                    matchesMihomoAndroid(name, abi)
                }
                ProxyCoreType.XRAY -> "XTLS/Xray-core" to { name: String ->
                    matchesXrayAndroid(name, abi)
                }
            }
            runCatching {
                // Via DownloadTransport: timeouts, retries, VPN socket protection, and the
                // THROUGH-tunnel fallback — raw URL.openStream() had none of those and could
                // hang forever on a dead network.
                val url = "https://api.github.com/repos/$repo/releases?per_page=$limit"
                val body = downloadTransport.downloadText(url, tag = "core-releases-${type.name}")
                    ?: error("release index unreachable")
                parseReleases(body, assetMatcher, abi.androidAbi)
            }.onFailure { Log.e(TAG, "listRemoteReleases failed abi=${abi.androidAbi}", it) }
                .getOrDefault(emptyList())
        }

    /**
     * Download [release] via [DownloadTransport] (honoring [mode] — AUTO/DIRECT/THROUGH, with
     * timeout/retry/protect already wired in the transport), verify its sha256 (when known),
     * extract with [NativeBinaryStore], and reject anything whose ELF arch doesn't match this
     * device before it's ever marked executable.
     */
    suspend fun downloadAndInstall(
        type: ProxyCoreType,
        release: RemoteRelease,
        mode: DownloadMode = DownloadMode.AUTO
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "core-dl-${type.name}-${release.tag}-${deviceAbi.goArch}")
        runCatching {
            require(release.abi == deviceAbi.androidAbi) {
                "Asset ABI ${release.abi} != device ${deviceAbi.androidAbi}"
            }
            require(assetMatchesType(type, release.assetName, deviceAbi)) {
                "Asset ${release.assetName} does not match ${deviceAbi.androidAbi}"
            }
            val destDir = versionDir(type, release.tag)
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            val archive = File(tmp, release.assetName)
            Log.i(TAG, "Downloading ${release.assetName} for ${deviceAbi.androidAbi} (mode=$mode)")
            val outcome = downloadTransport.download(release.downloadUrl, archive, mode = mode)
            val downloaded = outcome as? DownloadOutcome.Success
                ?: error("download failed: ${(outcome as DownloadOutcome.Failed).error.message}")
            if (release.sha256.isNullOrBlank()) {
                // Upstream (sing-box/mihomo) publish per-asset .dgst sidecars we don't fetch yet;
                // integrity then rests on GitHub HTTPS plus the ELF/ABI checks below.
                Log.w(TAG, "No sha256 pinned for ${release.assetName} — installing on HTTPS+ELF trust only")
            }
            require(NativeBinaryStore.verifySha256(downloaded.file, release.sha256)) {
                "sha256 mismatch for ${release.assetName}"
            }
            val extracted = extractBinary(type, archive, tmp)
                ?: error("binary not found in archive ${release.assetName}")
            require(elfMatchesDevice(extracted)) {
                "Downloaded binary ELF arch mismatch for ${deviceAbi.androidAbi}"
            }
            destDir.mkdirs()
            val out = File(destDir, binaryFileName(type))
            extracted.copyTo(out, overwrite = true)
            NativeBinaryStore.markExecutable(out)
            File(destDir, ABI_META).writeText(deviceAbi.androidAbi)
            val reject = validateInstalledBinary(type, destDir)
            require(reject == null) { "post-install validation failed: $reject" }
            Log.i(TAG, "Installed ${type.label} ${release.tag} (${deviceAbi.androidAbi}) → ${out.absolutePath}")
            true
        }.onFailure { Log.e(TAG, "downloadAndInstall failed", it) }.getOrDefault(false).also {
            // Archive + extracted copy are tens of MB — never leave them in cache.
            runCatching { tmp.deleteRecursively() }
        }
    }

    /** Download the newest GitHub asset for this device ABI and select it. */
    suspend fun downloadLatest(type: ProxyCoreType, mode: DownloadMode = DownloadMode.AUTO): RemoteRelease? =
        withContext(Dispatchers.IO) {
            val latest = listRemoteReleases(type, limit = 8).firstOrNull() ?: return@withContext null
            if (downloadAndInstall(type, latest, mode)) latest else null
        }

    fun deleteVersion(type: ProxyCoreType, version: String): Boolean {
        if (version == CORE_VERSION_BUNDLED) return false
        return File(coresDir(), "${type.name.lowercase()}/$version").deleteRecursively()
    }

    private fun abiMatchesDevice(dir: File): Boolean {
        val meta = File(dir, ABI_META)
        if (!meta.exists()) {
            val bin = File(dir, binaryFileName(typeHintFromDir(dir)))
            val fallback = dir.listFiles()?.firstOrNull { it.isFile && it.name != ABI_META }
            val target = when {
                bin.exists() -> bin
                fallback != null -> fallback
                else -> return false
            }
            return elfMatchesDevice(target)
        }
        return meta.readText().trim() == deviceAbi.androidAbi
    }

    private fun typeHintFromDir(dir: File): ProxyCoreType {
        val parent = dir.parentFile?.name.orEmpty().lowercase()
        return when (parent) {
            "clash" -> ProxyCoreType.CLASH
            "xray" -> ProxyCoreType.XRAY
            else -> ProxyCoreType.SING_BOX
        }
    }

    private fun elfMatchesDevice(file: File): Boolean = NativeBinaryStore.elfMatches(file, deviceAbi.elfMachine)

    /** Extraction delegates to the shared [NativeBinaryStore] (used identically by [AddonPackManager]). */
    private fun extractBinary(type: ProxyCoreType, archive: File, work: File): File? {
        val nameHints = when (type) {
            ProxyCoreType.SING_BOX -> listOf("sing-box")
            ProxyCoreType.CLASH -> listOf("mihomo", "clash")
            ProxyCoreType.XRAY -> listOf("xray", "Xray")
        }
        return NativeBinaryStore.extractArchive(archive, work, nameHints)
    }

    private fun parseReleases(
        body: String,
        assetMatcher: (String) -> Boolean,
        abi: String
    ): List<RemoteRelease> {
        val releases = mutableListOf<RemoteRelease>()
        runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tag = obj.getString("tag_name")
                val assets = obj.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.getString("name")
                    if (assetMatcher(name)) {
                        releases.add(
                            RemoteRelease(
                                tag = tag,
                                downloadUrl = a.getString("browser_download_url"),
                                assetName = name,
                                abi = abi
                            )
                        )
                        break
                    }
                }
            }
        }.onFailure { Log.e(TAG, "parseReleases", it) }
        return releases
    }

    private fun assetMatchesType(type: ProxyCoreType, name: String, abi: DeviceAbi): Boolean =
        when (type) {
            ProxyCoreType.SING_BOX -> matchesSingBoxAndroid(name, abi)
            ProxyCoreType.CLASH -> matchesMihomoAndroid(name, abi)
            ProxyCoreType.XRAY -> matchesXrayAndroid(name, abi)
        }
}
