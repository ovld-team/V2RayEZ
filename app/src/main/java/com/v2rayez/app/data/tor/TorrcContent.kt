package com.v2rayez.app.data.tor

import com.v2rayez.app.domain.model.TorConfig

/**
 * Pure torrc line builder (unit-testable). Keeps SOCKS + optional DNSPort on loopback so
 * full-device Tor VPN sessions can resolve DNS without UDP-over-SOCKS.
 */
internal object TorrcContent {

    fun lines(
        config: TorConfig,
        dataDirPath: String,
        ptLines: List<String>,
        bridges: List<String>
    ): List<String> = buildList {
        val host = config.socksHost.ifBlank { "127.0.0.1" }
        add("SocksPort $host:${config.socksPort}")
        if (config.dnsPort in 1..65535) {
            add("DNSPort 127.0.0.1:${config.dnsPort}")
            add("AutomapHostsOnResolve 1")
        }
        add("DataDirectory $dataDirPath")
        add("ClientOnly 1")
        add("AvoidDiskWrites 1")
        // Required so NativeCTorEngine can parse Bootstrapped NN% from stdout.
        add("Log notice stdout")
        add("SafeLogging 0")
        addAll(ptLines)
        bridges.forEach { raw ->
            val line = raw.trim().removePrefix("Bridge ").trim()
            if (line.isNotEmpty()) add("Bridge $line")
        }
    }
}
