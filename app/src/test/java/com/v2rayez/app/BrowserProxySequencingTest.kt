package com.v2rayez.app

import com.v2rayez.app.ui.screens.browser.shouldDeferInitialLoad
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the in-app Browser proxy-then-navigate sequencing rule: YouTube/media must not load
 * until [android.webkit.ProxyController.setProxyOverride] has applied, or the first CONNECTs
 * bypass the MITM `http-in`.
 */
class BrowserProxySequencingTest {

    @Test
    fun deferWhileProxyRunningButOverrideNotYetApplied() {
        // The exact race the fix closes: proxy up, override callback not fired yet.
        assertTrue(
            shouldDeferInitialLoad(
                proxyApiSupported = true,
                proxyRunning = true,
                proxyApplied = false
            )
        )
    }

    @Test
    fun loadOnceOverrideApplied() {
        assertFalse(
            shouldDeferInitialLoad(
                proxyApiSupported = true,
                proxyRunning = true,
                proxyApplied = true
            )
        )
    }

    @Test
    fun neverDeferWhenNoProxyRunning() {
        // No MITM proxy -> normal direct browsing, load immediately.
        assertFalse(
            shouldDeferInitialLoad(
                proxyApiSupported = true,
                proxyRunning = false,
                proxyApplied = false
            )
        )
    }

    @Test
    fun neverDeferOnUnsupportedApi() {
        // API < 30 can't override the WebView proxy; loading is direct and must not be blocked.
        assertFalse(
            shouldDeferInitialLoad(
                proxyApiSupported = false,
                proxyRunning = true,
                proxyApplied = false
            )
        )
    }
}
