package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import com.v2rayez.app.data.analytics.PiiScrubber
import com.v2rayez.app.data.analytics.FirebaseTelemetry
import com.v2rayez.app.data.download.DownloadOutcome
import com.v2rayez.app.data.download.DownloadTransport
import com.v2rayez.app.domain.model.DownloadMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Install state of the full geo routing databases (for the Core-manager UX). */
enum class GeoDataState {
    /** Full geoip.dat + geosite.dat downloaded into `filesDir/assets`. */
    DOWNLOADED,
    /** Only the packaged mini geoip (cn + private) is available — geosite rules are gated off. */
    BUILT_IN_MINI,
}

sealed class GeoInstallResult {
    data class Success(val geoipBytes: Long, val geositeBytes: Long) : GeoInstallResult()
    data class Failed(val reason: String) : GeoInstallResult()
}

/**
 * Downloads the full geo routing databases (`geoip.dat` + `geosite.dat`) on demand into
 * `filesDir/assets` — the exact directory [V2RayCore] hands to `initCoreEnv`, so Xray picks
 * them up on the next core start with no extra wiring.
 *
 * The APK no longer bundles the full dats (v0.9.50 size wave — see app/build.gradle.kts
 * `androidResources.ignoreAssetsPatterns`). Out of the box only the AAR's tiny
 * `geoip-only-cn-private.dat` is packaged; [V2RayCore.ensureInitialized] copies it in as a
 * mini `geoip.dat` so `geoip:private` / `geoip:cn` routing always works. `geosite:*` rules
 * are gated on [geositeAvailable] by [ConfigBuilder]/[MitmConfigBuilder] callers.
 */
@Singleton
class GeoAssetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadTransport: DownloadTransport,
    private val firebaseTelemetry: FirebaseTelemetry
) {
    companion object {
        private const val TAG = "GeoAssetManager"
        const val GEOIP = "geoip.dat"
        const val GEOSITE = "geosite.dat"

        /** Marker written after a verified full download; distinguishes full vs mini geoip. */
        private const val MARKER = "geo-full.txt"

        /** Loyalsoldier publishes both dats under these exact asset names on every release. */
        private const val BASE_URL =
            "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download"

        /** A full geoip.dat is ~15–20 MB; anything tiny is a truncated/HTML error body. */
        const val MIN_GEOIP_BYTES = 1L * 1024 * 1024
        const val MIN_GEOSITE_BYTES = 512L * 1024
    }

    /** Progress/cancel tags used for the two underlying [fetchOne] calls — Core manager combines them. */
    fun downloadTags(): List<String> = listOf("geo-$GEOIP", "geo-$GEOSITE")

    /** Cancel an in-flight [download], if any. */
    fun cancelDownload() {
        downloadTags().forEach { downloadTransport.cancel(it) }
    }

    /** Same directory [V2RayCore] passes to `initCoreEnv`. */
    fun assetDir(): File = File(context.filesDir, "assets").apply { mkdirs() }

    private fun marker() = File(assetDir(), MARKER)

    private fun geositeFile() = File(assetDir(), GEOSITE)
    private fun geoipFile() = File(assetDir(), GEOIP)

    /**
     * True when a verified full geosite database is installed — gates every `geosite:*` rule.
     *
     * Requires the download marker AND both dats to meet the same minimum sizes used at
     * download time. A truncated/corrupt geosite.dat that merely `exists() && length() > 0`
     * previously passed and caused Xray `geosite:ir … EOF` connect failures (Crashlytics).
     *
     * Read-only: never deletes files here (mid-session probes must not strip geosite under a
     * live core). Call [repairCorruptPackIfNeeded] from connect / Core Manager / startup.
     */
    fun geositeAvailable(): Boolean = isFullPackHealthy()

    fun state(): GeoDataState =
        if (isFullPackHealthy()) GeoDataState.DOWNLOADED else GeoDataState.BUILT_IN_MINI

    /** Marker + both dats present at verified minimum sizes. */
    private fun isFullPackHealthy(): Boolean {
        if (!marker().exists()) return false
        val geosite = geositeFile()
        val geoip = geoipFile()
        return geosite.isFile && geosite.length() >= MIN_GEOSITE_BYTES &&
            geoip.isFile && geoip.length() >= MIN_GEOIP_BYTES
    }

    /**
     * If an incomplete/corrupt full pack is present (marker or undersized dats), delete it and
     * restore the mini geoip. Safe to call before connect / from Core Manager — not from hot
     * read paths.
     */
    fun repairCorruptPackIfNeeded(): Boolean {
        if (isFullPackHealthy()) return false
        if (!geositeFile().exists() && !marker().exists() && !geoipFile().exists()) return false
        // Mini geoip alone (no marker) is the normal out-of-box state — leave it.
        if (!marker().exists() && !geositeFile().exists()) return false
        Log.w(TAG, "Corrupt/incomplete geo pack detected — repairing to mini geoip")
        repairCorruptPack()
        return true
    }

    /** Drop bad full dats and restore the packaged mini geoip so routing stays usable. */
    private fun repairCorruptPack() {
        runCatching {
            geositeFile().delete()
            // Only delete geoip when it looks truncated; keep a healthy full geoip if present.
            val geoip = geoipFile()
            if (!geoip.isFile || geoip.length() < MIN_GEOIP_BYTES) {
                geoip.delete()
            }
            marker().delete()
            V2RayCore.copyMiniGeoipFallback(context, assetDir())
        }.onFailure { Log.e(TAG, "geo pack repair failed", it) }
    }
    /** Human-readable installed version (release tag is not tracked; expose the download date). */
    fun installedLabel(): String? =
        marker().takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    /** Serializes [download] — concurrent runs would interleave writes on the same staged files. */
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Download both dats (sha256-verified against the release's `.sha256sum` sidecars when
     * reachable). Files are staged with DownloadTransport's `.part` + rename, so a running
     * core never sees a half-written database.
     */
    suspend fun download(mode: DownloadMode = DownloadMode.AUTO): GeoInstallResult =
        downloadMutex.withLock {
            firebaseTelemetry.traceSuspend("geo_download", mapOf("mode" to mode.name)) {
                withContext(Dispatchers.IO) {
            val geoip = when (val r = fetchOne(GEOIP, MIN_GEOIP_BYTES, mode)) {
                is FetchResult.Ok -> r.bytes
                is FetchResult.Error -> {
                    runCatching { firebaseTelemetry.captureDownloadFailure(GEOIP, r.reason) }
                    return@withContext GeoInstallResult.Failed("$GEOIP — ${r.reason}")
                }
            }
            val geosite = when (val r = fetchOne(GEOSITE, MIN_GEOSITE_BYTES, mode)) {
                is FetchResult.Ok -> r.bytes
                is FetchResult.Error -> {
                    runCatching { firebaseTelemetry.captureDownloadFailure(GEOSITE, r.reason) }
                    return@withContext GeoInstallResult.Failed("$GEOSITE — ${r.reason}")
                }
            }
            runCatching {
                marker().writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date())
                )
            }
            GeoInstallResult.Success(geoip, geosite)
                }
            }
        }

    /** Remove the full databases; [V2RayCore] restores the mini geoip on next init. */
    fun delete() {
        File(assetDir(), GEOSITE).delete()
        File(assetDir(), GEOIP).delete()
        marker().delete()
        // Put the mini geoip back immediately so geoip:private/cn rules keep working even
        // if the core re-initializes before ensureInitialized runs again.
        V2RayCore.copyMiniGeoipFallback(context, assetDir())
    }

    private sealed class FetchResult {
        data class Ok(val bytes: Long) : FetchResult()
        data class Error(val reason: String) : FetchResult()
    }

    private suspend fun fetchOne(name: String, minBytes: Long, mode: DownloadMode): FetchResult {
        val dest = File(assetDir(), name)
        val staged = File(assetDir(), "$name.dl")
        val outcome = downloadTransport.download("$BASE_URL/$name", staged, mode = mode, tag = "geo-$name")
        val bytes = when (outcome) {
            is DownloadOutcome.Success -> outcome.bytes
            is DownloadOutcome.Failed -> {
                // error.message embeds the download URL; scrub before it reaches Logcat or
                // Firebase telemetry. captureDownloadFailure() scrubs again at the remote boundary.
                Log.w(TAG, "$name download failed: ${PiiScrubber.scrub(outcome.error.message ?: "")}")
                staged.delete()
                return FetchResult.Error(outcome.error.message ?: "download failed")
            }
        }
        if (bytes < minBytes) {
            Log.w(TAG, "$name too small ($bytes B) — rejecting")
            staged.delete()
            return FetchResult.Error("truncated download ($bytes bytes)")
        }
        val expected = downloadTransport.downloadText("$BASE_URL/$name.sha256sum", mode = mode)
            ?.trim()?.split(Regex("""\s+"""))?.firstOrNull()
        if (!expected.isNullOrBlank() && !NativeBinaryStore.verifySha256(staged, expected)) {
            Log.w(TAG, "$name sha256 mismatch — rejecting")
            staged.delete()
            return FetchResult.Error("checksum mismatch")
        }
        if (!staged.renameTo(dest)) {
            staged.copyTo(dest, overwrite = true)
            staged.delete()
        }
        return FetchResult.Ok(bytes)
    }
}
