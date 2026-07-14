package com.v2rayez.app.data.tor

import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.NativeBinaryStore
import com.v2rayez.app.data.core.PackAvailability
import com.v2rayez.app.domain.model.TorTransport
import java.io.File

/**
 * Builds the `torrc` lines that wire the selected [TorTransport] into the native C
 * `tor` daemon, and reports which transports can actually run on this device.
 *
 * [TorTransport.DIRECT] (no bridge) and [TorTransport.VANILLA] (unobfuscated bridges)
 * run on the `tor` binary alone. The obfuscating transports (obfs4/meek via lyrebird,
 * snowflake, webtunnel) each need their pluggable-transport `exec` binary. Resolution
 * goes through [AddonPackManager] so a user-downloaded pack under `filesDir/addons/`
 * wins over the (optional) bundled jniLibs PIE — and works even when the PT binaries
 * were stripped from the APK (W3/W6). When nothing runnable is found, [isAvailable]
 * returns false and the UI greys the transport out with a "download in Core manager" CTA.
 */
object PluggableTransports {

    /**
     * Resolve the runnable PT `exec` binary for [transport] (downloaded pack first, bundled
     * jniLibs fallback), or null if it needs none (DIRECT/VANILLA) or none is installed.
     */
    fun binaryFile(addonPacks: AddonPackManager, transport: TorTransport): File? =
        PackAvailability.packForTransport(transport)?.let { addonPacks.resolveBinary(it) }

    /**
     * Whether [transport] can run right now. DIRECT/VANILLA always work; a pluggable
     * transport is available only when its `exec` binary is downloaded or bundled.
     */
    fun isAvailable(addonPacks: AddonPackManager, transport: TorTransport): Boolean = when (transport) {
        TorTransport.DIRECT, TorTransport.VANILLA -> true
        else -> binaryFile(addonPacks, transport) != null
    }

    /**
     * `torrc` lines enabling [transport]. DIRECT needs none; VANILLA just turns on
     * bridges; PT transports add a `ClientTransportPlugin <name> exec <path>` line
     * pointing at the resolved binary.
     *
     * @throws IllegalStateException for a PT transport whose binary is missing —
     * emitting `UseBridges 1` without a `ClientTransportPlugin` line would stall
     * Tor on unusable bridges, so fail loudly instead (the binary can vanish
     * between the [isAvailable] gate and torrc build).
     */
    fun torrcLines(addonPacks: AddonPackManager, transport: TorTransport): List<String> = when (transport) {
        TorTransport.DIRECT -> emptyList()
        TorTransport.VANILLA -> listOf("UseBridges 1")
        else -> {
            val bin = binaryFile(addonPacks, transport)
                ?: throw IllegalStateException(
                    "${transport.label} transport binary missing — install the pack in Core manager"
                )
            // SELinux (API 29+): wrap filesDir PT binaries with linker64 so Tor's exec of the
            // ClientTransportPlugin path succeeds (kernel sees system linker, not app_data_file).
            listOf(
                "UseBridges 1",
                "ClientTransportPlugin ${transport.ptName} exec ${NativeBinaryStore.torExecSpec(bin)}"
            )
        }
    }
}
