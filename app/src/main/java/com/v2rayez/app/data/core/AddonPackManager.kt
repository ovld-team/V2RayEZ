package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import com.v2rayez.app.data.analytics.FirebaseTelemetry
import com.v2rayez.app.data.download.DownloadOutcome
import com.v2rayez.app.data.download.DownloadTransport
import com.v2rayez.app.domain.model.DownloadMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Known downloadable addon binaries (native, non-core). Ids are stable — used as directory names. */
enum class AddonPackId(val binaryFileName: String, val label: String) {
    TOR("tor", "Tor"),
    LYREBIRD("lyrebird", "obfs4 / meek (lyrebird)"),
    SNOWFLAKE("snowflake", "Snowflake"),
    WEBTUNNEL("webtunnel", "WebTunnel"),
    BYEDPI("byedpi", "ByeDPI"),

    // --- v0.9.71 P7 protocol packs (native, per-ABI, downloaded on demand) ---
    /** Psiphon console client (`psiphon-tunnel-core`) — GPL binary from `_ref/psiphon-tunnel-core`. */
    PSIPHON("psiphon-tunnel-core", "Psiphon"),
    /** dnstt DNS-tunnel client. */
    DNSTUNNEL("dnstt", "DNS Tunnel");

    companion object {
        fun fromId(id: String): AddonPackId? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

/**
 * A resolved, downloadable release for one [AddonPackId] + device ABI. Callers (e.g. a future
 * pack-availability resolver) build this from a GitHub release or any other index; this manager
 * only cares about the URL, asset name, and (optionally) a pinned sha256.
 */
data class AddonRelease(
    val packId: AddonPackId,
    val version: String,
    val downloadUrl: String,
    val assetName: String,
    val abi: String,
    val sha256: String? = null
)

sealed class AddonInstallResult {
    data class Success(val packId: AddonPackId, val version: String, val binary: File) : AddonInstallResult()
    data class Failed(val packId: AddonPackId, val reason: String) : AddonInstallResult()
}

/** Where a runnable [AddonPackId] binary comes from on this device (for status UX). */
enum class PackSource {
    /** A user-downloaded + verified version under `filesDir/addons/`. */
    DOWNLOADED,
    /** The bundled jniLibs PIE (`nativeLibraryDir`). */
    BUNDLED,
    /** Nothing runnable — must be downloaded (or the pack was stripped from this APK). */
    MISSING
}

/**
 * Downloads, verifies (sha256 + ABI/ELF), and extracts on-demand addon binaries (Tor, pluggable
 * transports, ByeDPI) into `filesDir/addons/<packId>/<version>/`. Mirrors [CoreBinaryManager]'s
 * contract for `filesDir/cores/` so both stores resolve "user-downloaded first, then bundled
 * jniLibs" the same way — see [resolveBinary].
 *
 * Reuses [CoreBinaryManager] for device-ABI detection (single source of truth for "which ABI is
 * this device") and [NativeBinaryStore] for the sha256/ELF/extraction primitives shared with cores.
 */
@Singleton
class AddonPackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreBinaryManager: CoreBinaryManager,
    private val downloadTransport: DownloadTransport,
    private val firebaseTelemetry: FirebaseTelemetry
) {
    companion object {
        private const val TAG = "AddonPackManager"
        private const val ABI_META = "abi.txt"

        /**
         * Pure (no [Context] needed): human-readable reject reason for [dir] holding a runnable
         * [packId] binary for [abi], or null if OK. Exercised directly in unit tests; the
         * instance method [validateInstalled] just supplies this device's [DeviceAbi].
         */
        internal fun validateInstalledBinary(packId: AddonPackId, dir: File, abi: DeviceAbi): String? {
            val f = File(dir, packId.binaryFileName)
            if (!f.exists()) return "missing binary"
            if (!f.canExecute()) return "not executable"
            val meta = File(dir, ABI_META)
            val abiOk = if (meta.exists()) meta.readText().trim() == abi.androidAbi else true
            if (!abiOk) return "abi mismatch (need ${abi.androidAbi})"
            if (!NativeBinaryStore.elfMatches(f, abi.elfMachine)) {
                return "ELF arch mismatch (need ${abi.androidAbi})"
            }
            return null
        }

        /**
         * Parse GitHub Releases API JSON (single release object or an array) for an asset named
         * [wantAsset]. Pure / unit-testable; networking lives in [resolveRelease].
         */
        internal fun parseReleaseJson(
            body: String,
            packId: AddonPackId,
            wantAsset: String,
            abi: String,
            singleRelease: Boolean
        ): AddonRelease? {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val releases: List<JsonObject> = if (singleRelease) {
                listOf(json.parseToJsonElement(body).jsonObject)
            } else {
                json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            }
            for (obj in releases) {
                val tag = obj["tag_name"]?.jsonPrimitive?.content ?: continue
                val assets = obj["assets"]?.jsonArray ?: continue
                for (assetEl in assets) {
                    val a = assetEl.jsonObject
                    val name = a["name"]?.jsonPrimitive?.content ?: continue
                    if (name.equals(wantAsset, ignoreCase = true)) {
                        val url = a["browser_download_url"]?.jsonPrimitive?.content ?: continue
                        // GitHub now exposes an immutable asset digest. Prefer it over a
                        // separately downloaded sums file so every current release install is
                        // hash-verified even when SHA256SUMS.txt is censored/unreachable.
                        val digest = a["digest"]?.jsonPrimitive?.content
                            ?.removePrefix("sha256:")
                            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                        return AddonRelease(
                            packId = packId,
                            version = tag,
                            downloadUrl = url,
                            assetName = name,
                            abi = abi,
                            sha256 = digest
                        )
                    }
                }
            }
            return null
        }
    }

    private val deviceAbi: DeviceAbi get() = DeviceAbi.fromAndroidAbi(coreBinaryManager.deviceAbiLabel()) ?: DeviceAbi.ARM64

    fun addonsDir(): File = File(context.filesDir, "addons").apply { mkdirs() }

    fun packDir(packId: AddonPackId, version: String): File =
        File(addonsDir(), "${packId.name.lowercase()}/$version").apply { mkdirs() }

    /** Bundled jniLibs PIE for [packId], if present and matching this device's ABI. */
    fun bundledBinary(packId: AddonPackId): File? {
        val name = "lib${packId.binaryFileName}.so"
        val f = File(context.applicationInfo.nativeLibraryDir, name)
        return f.takeIf { it.exists() && NativeBinaryStore.elfMatches(it, deviceAbi.elfMachine) }
    }

    fun isBundled(packId: AddonPackId): Boolean = bundledBinary(packId) != null

    fun installedVersions(packId: AddonPackId): List<String> {
        val root = File(addonsDir(), packId.name.lowercase())
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && validateInstalled(packId, it) == null }
            ?.map { it.name }
            ?.sortedWith(VersionOrder.descending)
            .orEmpty()
    }

    /**
     * Resolve the binary to run for [packId]: a specific downloaded+verified [version] under
     * `filesDir/addons/`, or — when [version] is null/blank — the newest verified downloaded
     * version, falling back to the bundled jniLibs PIE. Returns null when nothing usable exists;
     * never silently substitutes an unverified binary.
     */
    fun resolveBinary(packId: AddonPackId, version: String? = null): File? {
        if (!version.isNullOrBlank()) {
            val dir = packDir(packId, version)
            return if (validateInstalled(packId, dir) == null) File(dir, packId.binaryFileName) else null
        }
        installedVersions(packId).firstOrNull()?.let { newest ->
            val dir = packDir(packId, newest)
            if (validateInstalled(packId, dir) == null) return File(dir, packId.binaryFileName)
        }
        return bundledBinary(packId)
    }

    /** Human-readable reject reason, or null if [dir] holds a runnable binary for [packId]. */
    fun validateInstalled(packId: AddonPackId, dir: File): String? =
        validateInstalledBinary(packId, dir, deviceAbi)

    /** True when a runnable [packId] binary exists (downloaded first, then bundled jniLibs). */
    fun isAvailable(packId: AddonPackId): Boolean = resolveBinary(packId) != null

    /** Newest downloaded+verified version of [packId], or null when none is installed. */
    fun installedVersion(packId: AddonPackId): String? = installedVersions(packId).firstOrNull()

    /**
     * Where the runnable [packId] binary comes from: a user download, the bundled jniLibs PIE,
     * or nothing (MISSING). Drives the Core-manager status/CTA UX.
     */
    fun packSource(packId: AddonPackId): PackSource = when {
        installedVersion(packId) != null -> PackSource.DOWNLOADED
        isBundled(packId) -> PackSource.BUNDLED
        else -> PackSource.MISSING
    }

    fun deletePack(packId: AddonPackId, version: String): Boolean =
        File(addonsDir(), "${packId.name.lowercase()}/$version").deleteRecursively()

    /** Remove every downloaded version of [packId] from `filesDir/addons/`. */
    fun deleteAll(packId: AddonPackId): Boolean =
        File(addonsDir(), packId.name.lowercase()).deleteRecursively()

    /**
     * Resolve a downloadable [AddonRelease] for [packId] on this device ABI from the configured
     * GitHub Releases repo ([com.v2rayez.app.BuildConfig.ADDONS_GITHUB_REPO]). Assets follow
     * `scripts/pack-addons.sh`: `<packId>-<abi>.zip` (+ optional `SHA256SUMS.txt` in the same release).
     */
    suspend fun resolveRelease(packId: AddonPackId): AddonRelease? = withContext(Dispatchers.IO) {
        val repo = com.v2rayez.app.BuildConfig.ADDONS_GITHUB_REPO.trim()
        if (repo.isBlank() || !repo.contains('/')) {
            Log.w(TAG, "ADDONS_GITHUB_REPO unset — cannot resolve ${packId.label}")
            return@withContext null
        }
        val abi = deviceAbi.androidAbi
        val wantAsset = "${packId.name.lowercase()}-$abi.zip"
        val pinnedTag = com.v2rayez.app.BuildConfig.ADDONS_RELEASE_TAG.trim()
        runCatching {
            val url = if (pinnedTag.isNotEmpty()) {
                "https://api.github.com/repos/$repo/releases/tags/${java.net.URLEncoder.encode(pinnedTag, "UTF-8")}"
            } else {
                "https://api.github.com/repos/$repo/releases?per_page=15"
            }
            val body = downloadTransport.downloadText(url, tag = "addon-index")
                ?: error("empty releases response")
            val found = parseReleaseJson(body, packId, wantAsset, abi, pinnedTag.isNotEmpty())
            if (found != null) {
                if (found.sha256 != null) {
                    found
                } else {
                    val sha = fetchSha256ForAsset(repo, found.version, found.assetName)
                    if (sha != null) found.copy(sha256 = sha) else found
                }
            } else null
        }.onFailure { Log.e(TAG, "resolveRelease failed for ${packId.label}", it) }
            .getOrNull()
    }

    private suspend fun fetchSha256ForAsset(repo: String, tag: String, assetName: String): String? {
        val sumsUrl =
            "https://github.com/$repo/releases/download/${java.net.URLEncoder.encode(tag, "UTF-8")}/SHA256SUMS.txt"
        val text = downloadTransport.downloadText(sumsUrl, tag = "addon-sha") ?: return null
        // Match the exact filename token (`<hash>␠␠<name>`) — a substring test would let a
        // superstring entry like `tor-arm64-v8a.zip.asc` win and pin the wrong hash.
        val line = text.lineSequence().firstOrNull { row ->
            row.trim().substringAfterLast(' ').substringAfterLast('*') == assetName
        } ?: return null
        return line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length == 64 }
    }

    /**
     * Download [release] (via [DownloadTransport], honoring [mode]), verify sha256 + ABI/ELF,
     * extract the executable, and install it under `filesDir/addons/<packId>/<version>/`.
     */
    suspend fun install(
        release: AddonRelease,
        mode: DownloadMode = DownloadMode.AUTO
    ): AddonInstallResult = withContext(Dispatchers.IO) {
        firebaseTelemetry.traceSuspend(
            "addon_install",
            mapOf("pack" to release.packId.name, "mode" to mode.name)
        ) {
        val packId = release.packId
        val work = File(context.cacheDir, "addon-dl-${packId.name}-${release.version}-${deviceAbi.goArch}")
        runCatching {
            require(release.abi == deviceAbi.androidAbi) {
                "Asset ABI ${release.abi} != device ${deviceAbi.androidAbi}"
            }
            if (work.exists()) work.deleteRecursively()
            work.mkdirs()
            val archive = File(work, release.assetName)
            Log.i(TAG, "Downloading ${release.assetName} for ${packId.label} (${deviceAbi.androidAbi}, mode=$mode)")
            val outcome = downloadTransport.download(release.downloadUrl, archive, mode = mode, tag = "addon:${packId.name}:${release.version}")
            val downloaded = (outcome as? DownloadOutcome.Success)
                ?: error("download failed: ${(outcome as DownloadOutcome.Failed).error.message}")
            if (release.sha256.isNullOrBlank()) {
                // SHA256SUMS.txt sidecar was unreachable — integrity then rests on GitHub HTTPS
                // plus the ABI/ELF checks below. Log loudly rather than fail a censored user who
                // can't reach the sidecar but got the archive through the tunnel.
                Log.w(TAG, "No sha256 pinned for ${release.assetName} — installing on HTTPS+ELF trust only")
            }
            require(NativeBinaryStore.verifySha256(downloaded.file, release.sha256)) {
                "sha256 mismatch for ${release.assetName}"
            }
            val extracted = NativeBinaryStore.extractArchive(archive, work, listOf(packId.binaryFileName))
                ?: error("binary not found in archive ${release.assetName}")
            require(NativeBinaryStore.elfMatches(extracted, deviceAbi.elfMachine)) {
                "ELF arch mismatch for ${deviceAbi.androidAbi}"
            }
            val destDir = packDir(packId, release.version)
            val out = File(destDir, packId.binaryFileName)
            extracted.copyTo(out, overwrite = true)
            NativeBinaryStore.markExecutable(out)
            File(destDir, ABI_META).writeText(deviceAbi.androidAbi)
            val reject = validateInstalled(packId, destDir)
            require(reject == null) { "post-install validation failed: $reject" }
            Log.i(TAG, "Installed ${packId.label} ${release.version} (${deviceAbi.androidAbi}) → ${out.absolutePath}")
            AddonInstallResult.Success(packId, release.version, out) as AddonInstallResult
        }.getOrElse {
            Log.e(TAG, "install failed for ${packId.label}", it)
            val reason = it.message ?: "unknown error"
            runCatching { firebaseTelemetry.captureDownloadFailure(packId.name, reason) }
            AddonInstallResult.Failed(packId, reason)
        }.also {
            // Archive + extracted binary are tens of MB per install — never leave them in cache.
            runCatching { work.deleteRecursively() }
        }
        }
    }

    /** Cancel an in-flight [install] for [packId]/[version], if any. */
    fun cancelInstall(packId: AddonPackId, version: String) {
        downloadTransport.cancel("addon:${packId.name}:$version")
    }
}
