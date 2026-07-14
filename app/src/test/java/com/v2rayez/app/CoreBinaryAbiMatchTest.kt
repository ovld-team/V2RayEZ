package com.v2rayez.app

import com.v2rayez.app.data.core.CoreBinaryManager
import com.v2rayez.app.data.core.DeviceAbi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreBinaryAbiMatchTest {

    @Test
    fun singBoxArmDoesNotMatchArm64() {
        assertTrue(
            CoreBinaryManager.matchesSingBoxAndroid(
                "sing-box-1.13.14-android-arm64.tar.gz",
                DeviceAbi.ARM64
            )
        )
        assertFalse(
            CoreBinaryManager.matchesSingBoxAndroid(
                "sing-box-1.13.14-android-arm64.tar.gz",
                DeviceAbi.ARM32
            )
        )
        assertTrue(
            CoreBinaryManager.matchesSingBoxAndroid(
                "sing-box-1.13.14-android-arm.tar.gz",
                DeviceAbi.ARM32
            )
        )
        assertFalse(
            CoreBinaryManager.matchesSingBoxAndroid(
                "sing-box-1.13.14-android-arm.tar.gz",
                DeviceAbi.ARM64
            )
        )
    }

    @Test
    fun mihomoMatchesExactArchPrefix() {
        assertTrue(
            CoreBinaryManager.matchesMihomoAndroid(
                "mihomo-android-arm64-v8-v1.19.28.gz",
                DeviceAbi.ARM64
            )
        )
        assertFalse(
            CoreBinaryManager.matchesMihomoAndroid(
                "mihomo-android-armv7-v1.19.28.gz",
                DeviceAbi.ARM64
            )
        )
        assertTrue(
            CoreBinaryManager.matchesMihomoAndroid(
                "mihomo-android-armv7-v1.19.28.gz",
                DeviceAbi.ARM32
            )
        )
    }

    @Test
    fun xrayMatchesDeviceAndroidZipOnly() {
        assertTrue(
            CoreBinaryManager.matchesXrayAndroid(
                "Xray-android-arm64-v8a.zip",
                DeviceAbi.ARM64
            )
        )
        assertFalse(
            CoreBinaryManager.matchesXrayAndroid(
                "Xray-android-arm64-v8a.zip.dgst",
                DeviceAbi.ARM64
            )
        )
        assertFalse(
            CoreBinaryManager.matchesXrayAndroid(
                "Xray-linux-arm64-v8a.zip",
                DeviceAbi.ARM64
            )
        )
        assertTrue(
            CoreBinaryManager.matchesXrayAndroid(
                "Xray-android-amd64.zip",
                DeviceAbi.X86_64
            )
        )
        assertFalse(
            CoreBinaryManager.matchesXrayAndroid(
                "Xray-android-amd64.zip",
                DeviceAbi.ARM64
            )
        )
    }

    @Test
    fun readElfMachineRejectsNonElf() {
        val f = kotlin.io.path.createTempFile("not-elf", ".bin").toFile()
        f.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19))
        assertTrue(CoreBinaryManager.readElfMachine(f) == null)
        f.delete()
    }

    @Test
    fun readElfMachineParsesArm64Header() {
        // Minimal little-endian ELF64 header with e_machine = 183 (EM_AARCH64)
        val hdr = ByteArray(20)
        hdr[0] = 0x7f; hdr[1] = 'E'.code.toByte(); hdr[2] = 'L'.code.toByte(); hdr[3] = 'F'.code.toByte()
        hdr[18] = (183 and 0xff).toByte()
        hdr[19] = ((183 shr 8) and 0xff).toByte()
        val f = kotlin.io.path.createTempFile("fake-elf", ".bin").toFile()
        f.writeBytes(hdr)
        assertTrue(CoreBinaryManager.readElfMachine(f) == 183)
        f.delete()
    }
}
