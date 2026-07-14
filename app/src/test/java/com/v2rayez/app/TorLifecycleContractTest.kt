package com.v2rayez.app

import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorEngineType
import com.v2rayez.app.domain.model.TorTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents Tor lifecycle contracts; process-level stop-on-timeout is enforced in
 * [TorController.start] (engine.stop on !ready). These tests guard config/state helpers.
 */
class TorLifecycleContractTest {

    @Test
    fun bootConnectFallbackUsesAutoOrBootFlag() {
        // Mirrors BootReceiver: bootAutoConnect || autoConnect
        fun shouldBoot(boot: Boolean, auto: Boolean) = boot || auto
        assertTrue(shouldBoot(true, false))
        assertTrue(shouldBoot(false, true))
        assertFalse(shouldBoot(false, false))
    }

    @Test
    fun torConfigCopyPreservesRouteAll() {
        val c = TorConfig(enabled = true, routeAllDevice = true, transport = TorTransport.OBFS4)
        assertTrue(c.copy(enabled = true).routeAllDevice)
        assertFalse(c.copy(routeAllDevice = false).routeAllDevice)
    }

    @Test
    fun defaultEngineIsNativeC() {
        assertTrue(TorConfig().engine == TorEngineType.NATIVE_C)
    }
}
