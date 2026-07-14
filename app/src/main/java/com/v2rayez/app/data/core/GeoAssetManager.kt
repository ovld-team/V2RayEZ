package com.v2rayez.app.data.core

import android.content.Context
import android.util.Log
import com.v2rayez.app.data.analytics.RemoteTelemetry
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
    private val remoteTelemetry: RemoteTelemetry
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
        private const val MIN_GEOIP_BYTES = 1L * 1024 * 1024
        private const val MIN_GEOSITE_BYTES = 512L * 1024
    }

    /** Same directory [V2RayCore] passes to `initCoreEnv`. */
    fun assetDir(): File = File(context.filesDir, "assets").apply { mkdirs() }

    private fun marker() = File(assetDir(), MARKER)

    /** True when the full geosite database is installed — gates every `geosite:*` config rule. */
    fun geositeAvailable(): Boolean = File(assetDir(), GEOSITE).let { it.exists() && it.length() > 0 }

    fun state(): GeoDataState =
        if (marker().exists() && geositeAvailable()) GeoDataState.DOWNLOADED else GeoDataState.BUILT_IN_MINI

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
        downloadMutex.withLock { withContext(Dispatchers.IO) {
            val geoip = when (val r = fetchOne(GEOIP, MIN_GEOIP_BYTES, mode)) {
                is FetchResult.Ok -> r.bytes
                is FetchResult.Error -> {
                    runCatching { remoteTelemetry.captureDownloadFailure(GEOIP, r.reason) }
                    return@withContext GeoInstallResult.Failed("$GEOIP — ${r.reason}")
                }
            }
            val geosite = when (val r = fetchOne(GEOSITE, MIN_GEOSITE_BYTES, mode)) {
                is FetchResult.Ok -> r.bytes
                is FetchResult.Error -> {
                    runCatching { remoteTelemetry.captureDownloadFailure(GEOSITE, r.reason) }
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
        } }

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
                Log.w(TAG, "$name download failed: ${outcome.error.message}")
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
