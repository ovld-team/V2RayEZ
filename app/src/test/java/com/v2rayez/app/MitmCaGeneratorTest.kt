package com.v2rayez.app

import com.v2rayez.app.data.cert.BouncyCastleInstaller
import com.v2rayez.app.data.cert.CertInstaller
import com.v2rayez.app.data.cert.MitmCaGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.Provider
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey

/**
 * Guards [MitmCaGenerator]: the generated CA must be a CA (BasicConstraints CA:true),
 * declare keyCertSign + cRLSign, be parseable by the same [CertInstaller.parse] used for
 * the system CA-install intent, and its written PEM key must actually match the cert.
 *
 * Also guards against the Android stub-"BC" regression: if a truncated provider is already
 * registered as `"BC"`, generation must still find `SHA256withRSA`.
 */
class MitmCaGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun installFullBouncyCastle() {
        BouncyCastleInstaller.ensureInstalled()
    }

    @Test
    fun generatedCaHasCaBasicConstraintsAndKeyUsage() {
        val crtFile = tempFolder.newFile("mycert.crt")
        val keyFile = tempFolder.newFile("mycert.key")

        val (generatedCert, _) = MitmCaGenerator.generateAndWrite(crtFile, keyFile)

        val parsed = CertInstaller.parse(crtFile.readBytes())
        assertNotNull("CertInstaller must be able to parse the generated PEM cert", parsed)
        val cert = parsed!!

        // BasicConstraints: -1 means "not a CA" per java.security.cert.X509Certificate.
        assertTrue("Expected BasicConstraints CA:true", cert.basicConstraints >= 0)

        val keyUsage = cert.keyUsage
        assertNotNull("Expected a KeyUsage extension", keyUsage)
        assertTrue("Expected keyCertSign bit set", keyUsage!![5])
        assertTrue("Expected cRLSign bit set", keyUsage[6])

        assertEquals("CN=${MitmCaGenerator.CA_SUBJECT_CN}", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(generatedCert.serialNumber, cert.serialNumber)
    }

    @Test
    fun writtenPrivateKeyMatchesCertPublicKey() {
        val crtFile = tempFolder.newFile("mycert.crt")
        val keyFile = tempFolder.newFile("mycert.key")

        MitmCaGenerator.generateAndWrite(crtFile, keyFile)

        val cert = CertInstaller.parse(crtFile.readBytes())!!
        val keyPem = keyFile.readText()
        assertTrue(keyPem.contains("-----BEGIN PRIVATE KEY-----"))

        val der = java.util.Base64.getDecoder().decode(
            keyPem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
        )
        val privateKey = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
        val certPublicKey = cert.publicKey as RSAPublicKey

        assertEquals(certPublicKey.modulus, privateKey.modulus)
    }

    @Test
    fun ensureExistsIsIdempotent() {
        val crtFile = tempFolder.newFile("mycert.crt")
        val keyFile = tempFolder.newFile("mycert.key")

        val (firstCert, _) = MitmCaGenerator.generateAndWrite(crtFile, keyFile)
        val firstCrtBytes = crtFile.readBytes()

        val reparsed = CertInstaller.parse(firstCrtBytes)
        assertNotNull(reparsed)
        assertEquals(firstCert.serialNumber, reparsed!!.serialNumber)
    }

    @Test
    fun replacesStubBcProviderSoSha256withRsaWorks() {
        // Simulate Android's truncated "BC" that cannot sign with SHA256withRSA.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(object : Provider("BC", 1.0, "stub") {}, 1)

        try {
            Signature.getInstance("SHA256withRSA", "BC")
            fail("Stub provider should not advertise SHA256withRSA")
        } catch (_: Exception) {
            // expected
        }

        BouncyCastleInstaller.ensureInstalled()
        assertNotNull(Signature.getInstance("SHA256withRSA", BouncyCastleProvider.PROVIDER_NAME))

        val (cert, _) = MitmCaGenerator.generateCaKeyAndCert()
        assertTrue(cert.basicConstraints >= 0)
        assertTrue(cert.sigAlgName.contains("SHA256", ignoreCase = true))
        assertTrue(cert.sigAlgName.contains("RSA", ignoreCase = true))
    }
}
