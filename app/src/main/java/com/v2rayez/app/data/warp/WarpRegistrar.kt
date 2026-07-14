package com.v2rayez.app.data.warp

import android.util.Base64
import com.v2rayez.app.domain.model.WarpConfig
import com.v2rayez.app.util.Curve25519
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Registers a fresh Cloudflare WARP device (wgcf-style) against the unofficial
 * Cloudflare client API and returns a ready-to-use [WarpConfig]. Runs a plain
 * HTTPS request; must be called off the main thread.
 */
object WarpRegistrar {

    private const val REG_URL = "https://api.cloudflareclient.com/v0a2483/reg"
    private const val CLIENT_VERSION = "a-6.11-2223"
    private const val USER_AGENT = "okhttp/3.12.1"

    @Throws(IOException::class)
    fun register(): WarpConfig {
        val privateKey = Curve25519.generatePrivateKey()
        val publicKey = Curve25519.publicKey(privateKey)
        val privB64 = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)

        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        val body = JSONObject()
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("key", pubB64)
            .put("model", "Android")
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        val response = post(body)
        val root = JSONObject(response)
        val config = root.getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val peerPublicKey = peer.getString("public_key")
        val endpointObj = peer.optJSONObject("endpoint")
        val endpoint = endpointObj?.optString("host")?.takeIf { it.isNotBlank() }
            ?: "engage.cloudflareclient.com:2408"

        val iface = config.getJSONObject("interface").getJSONObject("addresses")
        val addresses = buildList {
            iface.optString("v4").takeIf { it.isNotBlank() }?.let { add("$it/32") }
            iface.optString("v6").takeIf { it.isNotBlank() }?.let { add("$it/128") }
        }.ifEmpty { listOf("172.16.0.2/32") }

        val reserved = reservedFromClientId(config.optString("client_id"))
        val deviceId = root.optString("id")

        return WarpConfig(
            enabled = true,
            privateKey = privB64,
            peerPublicKey = peerPublicKey,
            addresses = addresses,
            reserved = reserved,
            endpoint = endpoint,
            deviceId = deviceId
        )
    }

    /** Cloudflare's base64 client_id decodes to the 3-byte WireGuard "reserved" field. */
    private fun reservedFromClientId(clientId: String): List<Int> {
        if (clientId.isBlank()) return emptyList()
        return runCatching {
            Base64.decode(clientId, Base64.DEFAULT).take(3).map { it.toInt() and 0xff }
        }.getOrDefault(emptyList())
    }

    @Throws(IOException::class)
    private fun post(body: String): String {
        val conn = (URL(REG_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("CF-Client-Version", CLIENT_VERSION)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("WARP registration failed (HTTP $code): ${text.take(200)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
