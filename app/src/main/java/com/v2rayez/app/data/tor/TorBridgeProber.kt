package com.v2rayez.app.data.tor

import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorTransport
import kotlinx.coroutines.delay

/**
 * Tries pluggable transports + bridge sets in order until Tor bootstraps with a
 * working exit (CONNECTED). Pluggable transports whose pack is not installed are
 * skipped with an explicit [ProbeEvent] (never silently). Constructed by
 * [TorController] (not Hilt) to avoid a controller↔prober cycle.
 */
class TorBridgeProber(
    private val controller: TorController,
    private val bridgeProvider: BridgeProvider
) {
    data class ProbeEvent(val message: String)

    suspend fun probe(
        base: TorConfig,
        perAttemptTimeoutMs: Long = 60_000,
        onEvent: (ProbeEvent) -> Unit = {}
    ): Pair<TorConfig, Boolean> {
        val available = controller.availableTransports().toSet()
        // obfs4 first (censorship); DIRECT next (open nets / emulators); then heavier PTs.
        val order = listOf(
            TorTransport.OBFS4,
            TorTransport.DIRECT,
            TorTransport.SNOWFLAKE,
            TorTransport.WEBTUNNEL,
            TorTransport.MEEK,
            TorTransport.VANILLA
        )

        var lastCfg = base.copy(enabled = true)
        for (transport in order) {
            if (transport !in available) {
                // Only PTs can be unavailable; DIRECT/VANILLA always pass isAvailable().
                onEvent(ProbeEvent("${transport.label} pack not installed — download it in Core manager"))
                continue
            }
            onEvent(ProbeEvent("Trying ${transport.label}…"))
            val bridgeRounds = if (transport == TorTransport.DIRECT) {
                listOf(emptyList())
            } else {
                val first = bridgeProvider.fetchBridges(transport)
                    .filter { TorController.isPlausibleBridgeLine(it) && TorController.bridgeMatchesTransport(it, transport) }
                    .ifEmpty {
                        bridgeProvider.defaultBridges(transport)
                            .filter { TorController.isPlausibleBridgeLine(it) && TorController.bridgeMatchesTransport(it, transport) }
                    }
                if (first.isEmpty()) {
                    onEvent(ProbeEvent("Skipping ${transport.label} — no bridges"))
                    continue
                }
                buildList {
                    add(first)
                    if (base.autoRotateBridges) {
                        val second = bridgeProvider.fetchBridges(transport)
                            .filter { TorController.isPlausibleBridgeLine(it) && TorController.bridgeMatchesTransport(it, transport) }
                        if (second.isNotEmpty() && second != first) add(second)
                    }
                }
            }

            val attemptTimeout = when (transport) {
                TorTransport.SNOWFLAKE -> perAttemptTimeoutMs.coerceAtMost(45_000L)
                TorTransport.DIRECT -> perAttemptTimeoutMs.coerceAtMost(40_000L)
                else -> perAttemptTimeoutMs
            }

            for ((idx, bridges) in bridgeRounds.withIndex()) {
                if (transport != TorTransport.DIRECT) {
                    onEvent(
                        ProbeEvent(
                            "Loading ${bridges.size} bridge(s) for ${transport.label}" +
                                if (bridgeRounds.size > 1) " (set ${idx + 1})" else ""
                        )
                    )
                }
                val cfg = base.copy(
                    enabled = true,
                    transport = transport,
                    bridges = bridges
                )
                lastCfg = cfg
                // start() already waits for bootstrap/exit; do not double-await.
                controller.start(cfg, readyTimeoutMs = attemptTimeout)
                val ok = controller.status.value.state == TorState.CONNECTED
                if (ok) {
                    onEvent(ProbeEvent("${transport.label} connected"))
                    return cfg to true
                }
                val pct = controller.status.value.bootstrapPercent
                val msg = controller.status.value.message
                onEvent(
                    ProbeEvent(
                        buildString {
                            append("${transport.label} failed")
                            if (pct > 0) append(" @ $pct%")
                            if (msg.isNotBlank()) append(" ($msg)")
                            append(" — trying next")
                        }
                    )
                )
                controller.stop()
                delay(500)
            }
        }
        onEvent(ProbeEvent("All transports failed"))
        return lastCfg to false
    }
}
