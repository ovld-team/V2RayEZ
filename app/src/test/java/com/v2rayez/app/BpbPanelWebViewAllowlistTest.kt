package com.v2rayez.app

import com.v2rayez.app.ui.screens.tools.isAllowedPanelScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-05: the BPB dashboard WebView must only navigate within http(s) — a hostile or
 * compromised panel URL must not be able to pivot into file/content/intent/javascript schemes.
 */
class BpbPanelWebViewAllowlistTest {

    @Test
    fun allowsOnlyHttpAndHttps() {
        assertTrue(isAllowedPanelScheme("http"))
        assertTrue(isAllowedPanelScheme("https"))
        assertTrue(isAllowedPanelScheme("HTTP"))
        assertFalse(isAllowedPanelScheme("file"))
        assertFalse(isAllowedPanelScheme("content"))
        assertFalse(isAllowedPanelScheme("intent"))
        assertFalse(isAllowedPanelScheme("javascript"))
        assertFalse(isAllowedPanelScheme(null))
    }
}
