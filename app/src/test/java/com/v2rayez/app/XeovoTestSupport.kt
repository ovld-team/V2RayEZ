package com.v2rayez.app

import java.io.File
import java.net.URL

/**
 * Offline-first Xeovo matrix loader. Default CI uses [FIXTURE_PATH]; live fetch requires
 * [LIVE_NETWORK_ENV]=1 plus optional /tmp/xeovo-sub.txt or [SUB_URL_ENV].
 */
object XeovoTestSupport {
    const val LIVE_NETWORK_ENV = "V2RAYEZ_LIVE_NETWORK"
    private const val SUB_URL_ENV = "XEOVO_SUB_URL"
    private const val FIXTURE_PATH = "/fixtures/xeovo-sub.txt"
    private const val LOCAL_SUB_PATH = "/tmp/xeovo-sub.txt"

    fun isLiveNetworkEnabled(): Boolean =
        System.getenv(LIVE_NETWORK_ENV)?.equals("1", ignoreCase = true) == true

    fun loadSub(): String {
        if (isLiveNetworkEnabled()) {
            val local = File(LOCAL_SUB_PATH)
            if (local.isFile && local.length() > 100) return local.readText()
            val url = System.getenv(SUB_URL_ENV)?.trim().orEmpty()
            if (url.isNotBlank()) {
                return URL(url).openStream().bufferedReader().readText()
            }
        }
        return fixtureSub()
    }

    private fun fixtureSub(): String =
        checkNotNull(XeovoTestSupport::class.java.getResourceAsStream(FIXTURE_PATH)) {
            "Missing test fixture $FIXTURE_PATH"
        }.bufferedReader().use { it.readText() }
}
