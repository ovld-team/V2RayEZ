package com.v2rayez.app.data.cert

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Installs a user CA certificate into the system trust store.
 *
 * On modern Android (7+, and tightened further on 11/14+) apps cannot silently write to
 * the system CA store. The supported path is [KeyChain.createInstallIntent], which shows
 * the OS-controlled consent screen and lets the user add the CA. This helper parses the
 * PEM/DER bytes, validates them as an X.509 certificate, and returns that intent.
 */
object CertInstaller {

    /** Parse [bytes] (PEM or DER) into an X.509 certificate, or null if invalid. */
    fun parse(bytes: ByteArray): X509Certificate? = runCatching {
        val factory = CertificateFactory.getInstance("X.509")
        factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }.getOrNull()

    /**
     * Build the system CA-install intent for [certBytes]. Returns null if the bytes are
     * not a valid certificate. Launch the result with `startActivity` (it shows the OS
     * consent + PIN/biometric prompt).
     */
    fun installIntent(certBytes: ByteArray): Intent? {
        val cert = parse(certBytes) ?: return null
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
            putExtra(KeyChain.EXTRA_NAME, cert.subjectX500Principal.name.take(40).ifBlank { "V2RayEz CA" })
        }
    }

    /** Read a certificate file (content Uri) and return the install intent, or null. */
    fun installIntentFromUri(context: Context, uri: android.net.Uri): Intent? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return installIntent(bytes)
    }
}
