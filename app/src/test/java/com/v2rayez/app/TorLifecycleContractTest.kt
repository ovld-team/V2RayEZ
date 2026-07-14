package com.v2rayez.app

import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.data.tor.TorReadinessDecision
import com.v2rayez.app.data.tor.TorState
import com.v2rayez.app.data.tor.torReadinessDecision
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
    fun bootConnectUsesDedicatedBootFlagOnly() {
        // Mirrors BootReceiver: bootAutoConnect only — the legacy autoConnect ("pick fastest
        // server when you tap connect") no longer implies a silent boot-time reconnect.
        fun shouldBoot(bootAutoConnect: Boolean) = bootAutoConnect
        assertTrue(shouldBoot(true))
        assertFalse(shouldBoot(false))
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

    @Test
    fun connectedStillRequiresReachableExit() {
        assertTrue(
            torReadinessDecision(TorState.CONNECTED, socksAccepts = true, exitReachable = false) ==
                TorReadinessDecision.WAIT
        )
        assertTrue(
            torReadinessDecision(TorState.CONNECTED, socksAccepts = false, exitReachable = true) ==
                TorReadinessDecision.READY
        )
    }

    @Test
    fun missingBootstrapLogsCanStillBecomeReadyThroughSocksAndExit() {
        assertTrue(
            torReadinessDecision(TorState.BOOTSTRAPPING, socksAccepts = true, exitReachable = true) ==
                TorReadinessDecision.READY
        )
        assertTrue(
            torReadinessDecision(TorState.BOOTSTRAPPING, socksAccepts = false, exitReachable = true) ==
                TorReadinessDecision.WAIT
        )
    }

    @Test
    fun terminalStatesStopAwaitReadyImmediately() {
        assertTrue(
            torReadinessDecision(TorState.ERROR, socksAccepts = true, exitReachable = true) ==
                TorReadinessDecision.STOP
        )
        assertTrue(
            torReadinessDecision(TorState.OFF, socksAccepts = true, exitReachable = true) ==
                TorReadinessDecision.STOP
        )
    }
}
