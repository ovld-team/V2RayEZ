package com.v2rayez.app

import com.v2rayez.app.data.vpn.PerAppTunnelPolicy
import com.v2rayez.app.domain.model.AppProxyConfig
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.TorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppTunnelPolicyTest {

    private val self = "com.v2rayez.app"

    @Test
    fun fullDeviceTunnelIgnoresProxySelection() {
        val s = AppSettings(
            fullDeviceTunnel = true,
            appProxy = AppProxyConfig(enabled = true, bypassMode = false, packages = setOf("a.b"))
        )
        val d = PerAppTunnelPolicy.decide(s, self)
        assertEquals(PerAppTunnelPolicy.Mode.FULL_DEVICE_EXCEPT_SELF, d.mode)
        assertTrue(d.conflictWithFullDeviceTunnel)
    }

    @Test
    fun emptyAllowListDegradesToFullDevice() {
        val s = AppSettings(
            appProxy = AppProxyConfig(enabled = true, bypassMode = false, packages = emptySet())
        )
        val d = PerAppTunnelPolicy.decide(s, self)
        assertEquals(PerAppTunnelPolicy.Mode.FULL_DEVICE_EXCEPT_SELF, d.mode)
        assertTrue(d.degradedToFullDevice)
    }

    @Test
    fun allowListExcludesSelf() {
        val s = AppSettings(
            appProxy = AppProxyConfig(enabled = true, bypassMode = false, packages = setOf(self, "com.chrome"))
        )
        val d = PerAppTunnelPolicy.decide(s, self)
        assertEquals(PerAppTunnelPolicy.Mode.ALLOW_LIST, d.mode)
        assertEquals(setOf("com.chrome"), d.packages)
        assertFalse(d.degradedToFullDevice)
    }

    @Test
    fun bypassIncludesSelf() {
        val s = AppSettings(
            appProxy = AppProxyConfig(enabled = true, bypassMode = true, packages = setOf("com.bank"))
        )
        val d = PerAppTunnelPolicy.decide(s, self)
        assertEquals(PerAppTunnelPolicy.Mode.BYPASS_LIST, d.mode)
        assertTrue(d.packages.contains(self))
        assertTrue(d.packages.contains("com.bank"))
    }

    @Test
    fun disabledProxyIsFullDevice() {
        val s = AppSettings(appProxy = AppProxyConfig(enabled = false, packages = setOf("x")))
        val d = PerAppTunnelPolicy.decide(s, self)
        assertEquals(PerAppTunnelPolicy.Mode.FULL_DEVICE_EXCEPT_SELF, d.mode)
    }

    @Test
    fun torServerPathStillExcludesVpnApp() {
        val settings = AppSettings(
            tor = TorConfig(enabled = true, routeAllDevice = false),
            appProxy = AppProxyConfig(enabled = false)
        )

        val decision = PerAppTunnelPolicy.decide(settings, self)

        assertEquals(PerAppTunnelPolicy.Mode.FULL_DEVICE_EXCEPT_SELF, decision.mode)
        assertEquals(setOf(self), decision.packages)
    }
}
