package com.v2rayez.app.data.cert

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Generates the local self-signed root CA used to MITM-intercept TLS for the domain-fronting
 * proxy (rewriting the SNI/Host while keeping the outer connection encrypted end-to-end from
 * the app's point of view). The private key is generated on-device and never leaves it in
 * plaintext form; only [MitmCaStore.exportCrtShareUri] (public cert only) is meant for sharing.
 *
 * Plain `android.*`/`javax.security` APIs cannot build custom X.509v3 extensions
 * (BasicConstraints, KeyUsage) without hidden/removed internal classes, so this uses
 * BouncyCastle (`bcpkix`/`bcprov`), the standard approach for on-device CA generation.
 */
object MitmCaGenerator {

    const val CA_SUBJECT_CN = "V2RayEz MITM CA"
    private const val KEY_SIZE = 2048
    private const val VALIDITY_YEARS = 10L
    /** Portable JCA name; Android's stub BC lacks this unless we replace the provider. */
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    /** Generates + writes the CA to [MitmCaStore]'s files only if not already present/valid. */
    fun ensureExists(context: Context) {
        if (MitmCaStore.isPresent(context)) return
        generate(context)
    }

    /** Force-(re)generates the CA, overwriting [MitmCaStore.crtFile]/[MitmCaStore.keyFile]. */
    fun generate(context: Context): Pair<X509Certificate, KeyPair> =
        generateAndWrite(MitmCaStore.crtFile(context), MitmCaStore.keyFile(context))

    /**
     * Builds a fresh CA key + certificate and writes PEM-encoded [crtFile] (cert) and
     * [keyFile] (PKCS8 private key). Exposed separately from [generate] so tests can target a
     * temp directory without needing an Android [Context].
     */
    fun generateAndWrite(crtFile: File, keyFile: File): Pair<X509Certificate, KeyPair> {
        val (cert, keyPair) = generateCaKeyAndCert()
        writePemFile(crtFile, "CERTIFICATE", cert.encoded)
        writePemFile(keyFile, "PRIVATE KEY", keyPair.private.encoded)
        return cert to keyPair
    }

    /** Builds a self-signed CA keypair + certificate: CA:true, keyUsage keyCertSign|cRLSign. */
    fun generateCaKeyAndCert(
        subjectCn: String = CA_SUBJECT_CN,
        validityYears: Long = VALIDITY_YEARS
    ): Pair<X509Certificate, KeyPair> {
        BouncyCastleInstaller.ensureInstalled()

        // Prefer the platform RSA generator (AndroidOpenSSL / Conscrypt). Specifying "BC"
        // is unnecessary for keygen and has historically failed on some OEM stubs.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_SIZE, SecureRandom())
        }.genKeyPair()

        val subject = X500Name("CN=$subjectCn")
        val now = System.currentTimeMillis()
        // Back-date slightly so on-device clock skew doesn't make a brand-new CA look "not yet valid".
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * validityYears))
        val serial = BigInteger(159, SecureRandom())

        val extUtils = JcaX509ExtensionUtils()
        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            .addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.public))

        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
        return cert to keyPair
    }
}

/** Writes [der] as a PEM block labeled [label] to [file], creating parent dirs as needed. */
internal fun writePemFile(file: File, label: String, der: ByteArray) {
    file.parentFile?.mkdirs()
    file.writeText(pemEncode(label, der))
}

/** PEM-encodes [der] (raw DER bytes) under the given [label], e.g. "CERTIFICATE". */
internal fun pemEncode(label: String, der: ByteArray): String {
    // android.util.Base64 is more reliable on-device; java.util.Base64 is fine for unit tests.
    val b64 = try {
        android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
            .replace("\n", "")
    } catch (_: Throwable) {
        java.util.Base64.getEncoder().encodeToString(der)
    }
    return buildString {
        append("-----BEGIN ").append(label).append("-----\n")
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 64, b64.length)
            append(b64, i, end).append('\n')
            i = end
        }
        append("-----END ").append(label).append("-----\n")
    }
}
