package com.v2rayez.app.data.mitm

import com.v2rayez.app.domain.model.MitmDomainFrontConfig

/**
 * Exports the same logical MITM domain-fronting config as [MitmConfigBuilder], but with
 * `v2rayN`-style relative certificate names (`mycert.crt` / `mycert.key`) resolved against the
 * desktop client's `bin/` directory. Use this to hand the user a config they can drop into a
 * desktop Xray/v2rayN install alongside a matching self-signed CA.
 *
 * Generate the CA on desktop with: `./xray tls cert -ca -file=mycert`
 */
object MitmDesktopExporter {

    /** Relative certificate name expected in the desktop client's working directory. */
    const val CERT_FILE = "mycert.crt"

    /** Relative private-key name expected in the desktop client's working directory. */
    const val KEY_FILE = "mycert.key"

    /** Build the shareable desktop config JSON string (no Android TUN inbound). */
    fun export(config: MitmDomainFrontConfig): String =
        MitmConfigBuilder.build(
            config,
            certFile = CERT_FILE,
            keyFile = KEY_FILE,
            includeTun = false,
            // Desktop Xray/v2rayN installs ship the full geosite.dat next to the binary.
            geositeAvailable = true
        )
}
