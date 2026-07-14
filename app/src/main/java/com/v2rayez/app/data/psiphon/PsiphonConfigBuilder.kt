package com.v2rayez.app.data.psiphon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Produces the JSON config consumed by the `psiphon-tunnel-core` console client
 * (`-config <file>`). The user supplies the propagation/sponsor ids + embedded/remote server
 * list (the Psiphon-provided secrets) as [userConfig]; this builder overlays the runtime knobs
 * V2RayEz controls — the local SOCKS port and a private data directory — and forces diagnostic
 * notices so [PsiphonEngine] can detect the "listening" transition.
 *
 * Pure Kotlin (JVM-testable). See `_ref/psiphon-tunnel-core/ConsoleClient/main.go` +
 * `artifacts/ref-shiro-features.md` for the field provenance.
 */
object PsiphonConfigBuilder {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    /**
     * Merge [userConfig] (Psiphon JSON blob) with the runtime overlay.
     * [socksPort] is the local SOCKS proxy the tunnel exposes; [dataDir] persists Psiphon state.
     * Returns a config string; when [userConfig] is blank a minimal skeleton is emitted so the
     * console client fails with a clear "config required" notice rather than a parse crash.
     */
    fun build(userConfig: String, socksPort: Int, dataDir: String): String {
        val base: JsonObject = runCatching {
            if (userConfig.isBlank()) buildJsonObject { }
            else json.parseToJsonElement(userConfig).jsonObject
        }.getOrElse { buildJsonObject { } }

        val merged = buildJsonObject {
            // Preserve every user-supplied field (PropagationChannelId, SponsorId, server list…).
            base.forEach { (k, v) ->
                if (k !in OVERRIDDEN_KEYS) put(k, v)
            }
            // Runtime overlay owned by the app.
            put("LocalSocksProxyPort", socksPort)
            put("LocalHttpProxyPort", 0) // SOCKS only; hev bridges the SOCKS port.
            put("DataRootDirectory", dataDir)
            put("EmitDiagnosticNotices", true)
            put("EmitBytesTransferred", false)
            // Keep whatever protocol selection the user's blob defined; do not force one here.
        }
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    private val OVERRIDDEN_KEYS = setOf(
        "LocalSocksProxyPort",
        "LocalHttpProxyPort",
        "DataRootDirectory",
        "EmitDiagnosticNotices",
        "EmitBytesTransferred"
    )

    /** True when [userConfig] at least declares the ids Psiphon needs to attempt a connection. */
    fun looksComplete(userConfig: String): Boolean {
        if (userConfig.isBlank()) return false
        val obj = runCatching { json.parseToJsonElement(userConfig).jsonObject }.getOrNull() ?: return false
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.content.orEmpty()
        return str("PropagationChannelId").isNotBlank() && str("SponsorId").isNotBlank()
    }
}
