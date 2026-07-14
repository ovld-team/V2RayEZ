package com.v2rayez.app.data.tor

import android.content.Context
import android.util.Log
import com.v2rayez.app.domain.model.TorTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies Tor bridge lines for a given [TorTransport].
 *
 * Sources, in order:
 *  1. Tor Moat circumvention **settings** JSON (rdsys, no CAPTCHA).
 *  2. Moat circumvention **defaults**.
 *  3. Legacy HTML scrape (tag-stripped).
 *  4. Bundled `assets/tor/default-bridges.txt`.
 */
@Singleton
class BridgeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient
) {

    private val bundled: Map<TorTransport, List<String>> by lazy { loadBundled() }

    private val moatHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun defaultBridges(transport: TorTransport): List<String> =
        if (transport == TorTransport.DIRECT) emptyList() else bundled[transport].orEmpty()

    suspend fun fetchBridges(transport: TorTransport): List<String> = withContext(Dispatchers.IO) {
        if (transport == TorTransport.DIRECT) return@withContext emptyList()
        fetchFromNetwork(transport).takeIf { it.isNotEmpty() } ?: defaultBridges(transport)
    }

    suspend fun fetchFromNetwork(transport: TorTransport): List<String> = withContext(Dispatchers.IO) {
        if (transport == TorTransport.DIRECT) return@withContext emptyList()
        val country = detectCountry()
        val fromSettings = fetchCircumventionSettings(transport, country)
        if (fromSettings.isNotEmpty()) {
            Log.i(TAG, "Moat settings: ${fromSettings.size} ${transport.label} bridge(s) (country=$country)")
            return@withContext fromSettings
        }
        val fromDefaults = fetchCircumventionDefaults(transport)
        if (fromDefaults.isNotEmpty()) {
            Log.i(TAG, "Moat defaults: ${fromDefaults.size} ${transport.label} bridge(s)")
            return@withContext fromDefaults
        }
        val fromHtml = fetchHtmlBridges(transport)
        if (fromHtml.isNotEmpty()) {
            Log.i(TAG, "HTML scrape: ${fromHtml.size} ${transport.label} bridge(s)")
            return@withContext fromHtml
        }
        Log.w(TAG, "Network bridge fetch failed for ${transport.label}")
        emptyList()
    }

    private fun fetchCircumventionSettings(transport: TorTransport, country: String): List<String> {
        val types = apiTypesFor(transport)
        val bodyJson = buildJsonObject {
            put("country", country.lowercase(Locale.US))
            putJsonArray("transports") { types.forEach { add(it) } }
        }.toString()
        val req = Request.Builder()
            .url("$MOAT_BASE/circumvention/settings")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            moatHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "settings HTTP ${resp.code}")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                val parsed = parseCircumventionJson(body, types)
                if (parsed.isEmpty()) {
                    Log.i(TAG, "settings empty for country=$country types=$types (bodyLen=${body.length})")
                }
                parsed
            }
        }.onFailure { Log.w(TAG, "settings fetch failed", it) }.getOrDefault(emptyList())
    }

    private fun fetchCircumventionDefaults(transport: TorTransport): List<String> {
        val types = apiTypesFor(transport)
        val req = Request.Builder()
            .url("$MOAT_BASE/circumvention/defaults")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return runCatching {
            moatHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "defaults HTTP ${resp.code}")
                    return@use emptyList()
                }
                parseCircumventionJson(resp.body?.string().orEmpty(), types)
            }
        }.onFailure { Log.w(TAG, "defaults fetch failed", it) }.getOrDefault(emptyList())
    }

    private fun fetchHtmlBridges(transport: TorTransport): List<String> {
        val param = when (transport) {
            TorTransport.VANILLA -> "0"
            TorTransport.OBFS4 -> "obfs4"
            TorTransport.SNOWFLAKE -> "snowflake"
            TorTransport.MEEK -> "meek"
            TorTransport.WEBTUNNEL -> "webtunnel"
            TorTransport.DIRECT -> return emptyList()
        }
        val req = Request.Builder()
            .url("https://bridges.torproject.org/bridges?transport=$param")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        return runCatching {
            moatHttp.newCall(req).execute().use { resp ->
                parseHtmlBridges(resp.body?.string().orEmpty())
                    .filter { classify(it) == transport }
            }
        }.getOrDefault(emptyList())
    }

    private fun apiTypesFor(transport: TorTransport): Set<String> = when (transport) {
        TorTransport.OBFS4 -> setOf("obfs4")
        TorTransport.SNOWFLAKE -> setOf("snowflake")
        TorTransport.MEEK -> setOf("meek", "meek_lite")
        TorTransport.WEBTUNNEL -> setOf("webtunnel")
        TorTransport.VANILLA -> setOf("vanilla")
        TorTransport.DIRECT -> emptySet()
    }

    private fun detectCountry(): String =
        com.v2rayez.app.data.core.DeviceCountry.detect(context) ?: "us"

    private fun loadBundled(): Map<TorTransport, List<String>> = runCatching {
        val lines = context.assets.open(ASSET_PATH).bufferedReader().use { it.readLines() }
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .groupBy { classify(it) }
    }.getOrDefault(emptyMap())

    private fun classify(line: String): TorTransport {
        val head = line.substringBefore(' ').lowercase(Locale.US)
        return when {
            head.firstOrNull()?.isDigit() == true -> TorTransport.VANILLA
            else -> TorTransport.entries.firstOrNull {
                it.ptName.isNotBlank() && it.ptName.lowercase(Locale.US) == head
            } ?: TorTransport.VANILLA
        }
    }

    companion object {
        private const val TAG = "BridgeProvider"
        private const val ASSET_PATH = "tor/default-bridges.txt"
        private const val MOAT_BASE = "https://bridges.torproject.org/moat"
        private const val USER_AGENT = "v2rayez/0.9.13"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val moatJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse Tor Moat / rdsys circumvention JSON (`settings` → `bridges` → `bridge_strings`).
         * Uses kotlinx.serialization so JVM unit tests and the device share the same path
         * (Android's `org.json` stubs throw in local unit tests).
         */
        fun parseCircumventionJson(json: String, wantedTypes: Set<String>): List<String> {
            if (json.isBlank()) return emptyList()
            return runCatching {
                val root = moatJson.parseToJsonElement(json).jsonObject
                val settings = root["settings"] as? JsonArray ?: return emptyList()
                val wanted = wantedTypes.map { it.lowercase(Locale.US) }.toSet()
                val out = linkedSetOf<String>()
                for (item in settings) {
                    val bridges = (item as? JsonObject)?.get("bridges")?.jsonObject ?: continue
                    val type = bridges["type"]?.jsonPrimitive?.contentOrNull
                        ?.lowercase(Locale.US)
                        .orEmpty()
                    if (type !in wanted) continue
                    val strings = bridges["bridge_strings"] as? JsonArray ?: continue
                    for (entry in strings) {
                        val raw = (entry as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val line = sanitizeBridgeLine(raw)
                        if (line != null && TorController.isPlausibleBridgeLine(line)) out.add(line)
                    }
                }
                out.toList()
            }.getOrDefault(emptyList())
        }

        fun parseHtmlBridges(html: String): List<String> {
            val cleaned = html
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), "\n")
                .replace("&nbsp;", " ")
                .replace(Regex("[ \\t]+"), " ")
            return cleaned.lineSequence()
                .mapNotNull { sanitizeBridgeLine(it) }
                .filter { TorController.isPlausibleBridgeLine(it) }
                .distinct()
                .toList()
        }

        fun sanitizeBridgeLine(raw: String): String? {
            var t = raw.trim()
                .removePrefix("Bridge ")
                .trim()
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (t.isEmpty()) return null
            t = t.trimEnd(',', ';', '.')
            return t.takeIf { it.isNotBlank() }
        }
    }
}
