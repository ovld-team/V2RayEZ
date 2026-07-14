package com.v2rayez.app.data.cert

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android ships a truncated provider registered as `"BC"` that lacks many algorithms
 * (notably `SHA256withRSA` / `SHA256WithRSAEncryption`). Calling
 * `Security.addProvider(BouncyCastleProvider())` only when `"BC"` is absent therefore
 * silently keeps the stub and CA generation fails with "no such algorithm: SHA…".
 *
 * Always [Security.removeProvider] the existing `"BC"` entry and
 * [Security.insertProviderAt] the full JAR provider at priority 1.
 */
internal object BouncyCastleInstaller {

    @Volatile
    private var installed = false

    @Synchronized
    fun ensureInstalled() {
        if (installed &&
            Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass ==
            BouncyCastleProvider::class.java
        ) {
            return
        }
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        installed = true
    }
}
