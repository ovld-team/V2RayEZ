package com.v2rayez.app.data.tor

import android.content.Context
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorEngineType

/** High-level lifecycle state of a Tor engine. */
enum class TorState { OFF, STARTING, BOOTSTRAPPING, CONNECTED, ERROR }

/** Observable status snapshot emitted by an engine / the [TorController]. */
data class TorStatus(
    val state: TorState = TorState.OFF,
    val bootstrapPercent: Int = 0,
    val message: String = "",
    val engine: TorEngineType? = null
)

/**
 * A pluggable Tor core. The only implementation wraps the embedded native classic
 * C `tor` binary; it exposes a start/stop contract and reports progress through
 * [onStatus].
 */
interface TorEngine {

    val type: TorEngineType

    /** True when the backing binary / provider is present on this device. */
    fun isAvailable(context: Context): Boolean

    /**
     * True while the underlying daemon / PT process is running. Used by the
     * [TorController] post-connect watchdog to detect a Tor/PT process that dies
     * after bootstrap so the session can be restarted or surfaced as ERROR.
     */
    fun isAlive(): Boolean

    /**
     * Start the engine, opening a local SOCKS proxy on [TorConfig.socksPort] and using
     * [bridges] (already resolved) for the configured pluggable transport.
     */
    suspend fun start(context: Context, config: TorConfig, bridges: List<String>, onStatus: (TorStatus) -> Unit)

    /** Stop the engine and release any process / resources. */
    suspend fun stop()
}
