package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.TorTransport

/**
 * Pure mapping between the app's runtime subsystems (Tor daemon, pluggable transports, ByeDPI
 * desync) and the downloadable [AddonPackId] that backs each of them. Keeping this in one place
 * means [AddonPackManager]'s "downloaded first, then bundled jniLibs, else MISSING" resolution is
 * the single source of truth for whether Tor / a PT / ByeDPI can actually run — no engine hard-codes
 * a `nativeLibraryDir` path anymore (see W3).
 */
object PackAvailability {

    /** The Tor daemon itself (`tor` / `libtor.so`). */
    val TOR: AddonPackId = AddonPackId.TOR

    /** The ByeDPI desync proxy (`byedpi` / `libbyedpi.so`). */
    val BYEDPI: AddonPackId = AddonPackId.BYEDPI

    /** Psiphon console client pack. */
    val PSIPHON: AddonPackId = AddonPackId.PSIPHON

    /** dnstt DNS-tunnel pack. */
    val DNSTUNNEL: AddonPackId = AddonPackId.DNSTUNNEL

    /**
     * The addon pack a standalone-engine [protocol] needs, or null when the protocol runs on a
     * proxy core (WireGuard/SSH → sing-box) rather than a dedicated pack.
     */
    fun packForProtocol(protocol: Protocol): AddonPackId? = when (protocol) {
        Protocol.PSIPHON -> AddonPackId.PSIPHON
        Protocol.DNSTUNNEL -> AddonPackId.DNSTUNNEL
        else -> null
    }

    /**
     * The pluggable-transport pack a [transport] needs, or null for the transports the bundled
     * `tor` handles on its own (DIRECT / VANILLA). obfs4 and meek both ship in lyrebird.
     */
    fun packForTransport(transport: TorTransport): AddonPackId? = when (transport) {
        TorTransport.DIRECT, TorTransport.VANILLA -> null
        TorTransport.OBFS4, TorTransport.MEEK -> AddonPackId.LYREBIRD
        TorTransport.SNOWFLAKE -> AddonPackId.SNOWFLAKE
        TorTransport.WEBTUNNEL -> AddonPackId.WEBTUNNEL
    }
}
