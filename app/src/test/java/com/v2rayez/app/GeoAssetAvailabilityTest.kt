package com.v2rayez.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mirrors [com.v2rayez.app.data.core.GeoAssetManager] full-pack health rules without Android.
 * Truncated geosite.dat must NOT gate Iran/geosite rules on (Crashlytics geosite:ir EOF).
 */
class GeoAssetAvailabilityTest {

    @Test
    fun fullPackHealthy_requiresMarkerAndMinSizes() {
        val dir = createTempDir(prefix = "geo-pack-")
        try {
            val geosite = File(dir, "geosite.dat")
            val geoip = File(dir, "geoip.dat")
            val marker = File(dir, "geo-full.txt")

            // Truncated geosite (old buggy gate: length > 0) must fail.
            geosite.writeBytes(ByteArray(1024))
            geoip.writeBytes(ByteArray(2 * 1024 * 1024))
            marker.writeText("2026-07-16")
            assertFalse(isFullPackHealthy(dir))

            // Healthy sizes + marker.
            geosite.writeBytes(ByteArray(512 * 1024))
            assertTrue(isFullPackHealthy(dir))

            // Missing marker → fail even with large files.
            marker.delete()
            assertFalse(isFullPackHealthy(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyGeositeFile_isUnhealthy() {
        val dir = createTempDir(prefix = "geo-empty-")
        try {
            File(dir, "geosite.dat").writeBytes(ByteArray(0))
            File(dir, "geoip.dat").writeBytes(ByteArray(2 * 1024 * 1024))
            File(dir, "geo-full.txt").writeText("x")
            assertFalse(isFullPackHealthy(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        private const val MIN_GEOIP_BYTES = 1L * 1024 * 1024
        private const val MIN_GEOSITE_BYTES = 512L * 1024

        /** Keep in sync with GeoAssetManager.isFullPackHealthy. */
        fun isFullPackHealthy(assetDir: File): Boolean {
            val marker = File(assetDir, "geo-full.txt")
            if (!marker.exists()) return false
            val geosite = File(assetDir, "geosite.dat")
            val geoip = File(assetDir, "geoip.dat")
            return geosite.isFile && geosite.length() >= MIN_GEOSITE_BYTES &&
                geoip.isFile && geoip.length() >= MIN_GEOIP_BYTES
        }
    }
}
