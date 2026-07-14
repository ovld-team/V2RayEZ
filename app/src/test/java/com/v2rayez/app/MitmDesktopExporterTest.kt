package com.v2rayez.app

import com.v2rayez.app.data.mitm.MitmConfigBuilder
import com.v2rayez.app.data.mitm.MitmDesktopExporter
import com.v2rayez.app.domain.model.MitmDomainFrontConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmDesktopExporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun config() = MitmDomainFrontConfig(
        enabled = true,
        rulesText = "google.com = www.google.com",
        defaultFront = "www.microsoft.com"
    )

    @Test
    fun outputIsValidJson() {
        val obj = json.decodeFromString(JsonObject.serializer(), MitmDesktopExporter.export(config()))
        assertNotNull(obj["inbounds"])
        assertNotNull(obj["outbounds"])
    }

    @Test
    fun usesRelativeCertNames() {
        val out = MitmDesktopExporter.export(config())
        assertTrue(out.contains("\"certificateFile\":\"${MitmDesktopExporter.CERT_FILE}\""))
        assertTrue(out.contains("\"keyFile\":\"${MitmDesktopExporter.KEY_FILE}\""))
    }

    @Test
    fun doesNotContainAbsolutePaths() {
        val out = MitmDesktopExporter.export(config())
        assertFalse(out.contains("/data/data/"))
        assertFalse(out.contains("/files/mycert"))
    }

    @Test
    fun containsFromMitM() {
        assertTrue(MitmDesktopExporter.export(config()).contains(MitmConfigBuilder.ALPN_FROM_MITM))
    }

    @Test
    fun matchesBuilderShapeWithRelativeCerts() {
        val exported = MitmDesktopExporter.export(config())
        val direct = MitmConfigBuilder.build(
            config(),
            certFile = MitmDesktopExporter.CERT_FILE,
            keyFile = MitmDesktopExporter.KEY_FILE,
            includeTun = false,
            geositeAvailable = true // desktop export assumes full geo dats next to the binary
        )
        assertTrue(exported == direct)
    }
}
