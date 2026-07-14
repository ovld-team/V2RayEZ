package com.v2rayez.app

import com.v2rayez.app.data.core.AddonPackId
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.DeviceAbi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * [AddonPackManager.validateInstalledBinary] is a pure (no [android.content.Context]) function
 * so its sha256/ABI/ELF-adjacent install-acceptance logic is directly unit-testable, mirroring
 * [CoreBinaryManager][com.v2rayez.app.data.core.CoreBinaryManager]'s own ABI-match tests.
 */
class AddonPackManagerTest {

    private fun elfHeader(machine: Int): ByteArray {
        val hdr = ByteArray(20)
        hdr[0] = 0x7f; hdr[1] = 'E'.code.toByte(); hdr[2] = 'L'.code.toByte(); hdr[3] = 'F'.code.toByte()
        hdr[18] = (machine and 0xff).toByte()
        hdr[19] = ((machine shr 8) and 0xff).toByte()
        return hdr
    }

    @Test
    fun validateInstalledBinaryRejectsMissingFile() {
        val dir = createTempDirectory("addon-missing").toFile()
        val reason = AddonPackManager.validateInstalledBinary(AddonPackId.TOR, dir, DeviceAbi.ARM64)
        assertEquals("missing binary", reason)
        dir.deleteRecursively()
    }

    @Test
    fun validateInstalledBinaryRejectsNonExecutable() {
        val dir = createTempDirectory("addon-noexec").toFile()
        val bin = File(dir, AddonPackId.TOR.binaryFileName)
        bin.writeBytes(elfHeader(183))
        bin.setExecutable(false, false)
        val reason = AddonPackManager.validateInstalledBinary(AddonPackId.TOR, dir, DeviceAbi.ARM64)
        assertEquals("not executable", reason)
        dir.deleteRecursively()
    }

    @Test
    fun validateInstalledBinaryRejectsElfMismatch() {
        val dir = createTempDirectory("addon-elf").toFile()
        val bin = File(dir, AddonPackId.TOR.binaryFileName)
        bin.writeBytes(elfHeader(62)) // amd64, not arm64
        bin.setExecutable(true, false)
        val reason = AddonPackManager.validateInstalledBinary(AddonPackId.TOR, dir, DeviceAbi.ARM64)
        assertEquals("ELF arch mismatch (need arm64-v8a)", reason)
        dir.deleteRecursively()
    }

    @Test
    fun validateInstalledBinaryRejectsAbiMetaMismatchEvenWithMatchingElf() {
        // Guards against copying an arm64 binary into an armeabi-v7a version dir by mistake:
        // the pinned abi.txt from install-time must also agree.
        val dir = createTempDirectory("addon-abimeta").toFile()
        val bin = File(dir, AddonPackId.TOR.binaryFileName)
        bin.writeBytes(elfHeader(DeviceAbi.ARM64.elfMachine))
        bin.setExecutable(true, false)
        File(dir, "abi.txt").writeText(DeviceAbi.ARM32.androidAbi)
        val reason = AddonPackManager.validateInstalledBinary(AddonPackId.TOR, dir, DeviceAbi.ARM64)
        assertEquals("abi mismatch (need arm64-v8a)", reason)
        dir.deleteRecursively()
    }

    @Test
    fun validateInstalledBinaryAcceptsMatchingElfAndAbi() {
        val dir = createTempDirectory("addon-ok").toFile()
        val bin = File(dir, AddonPackId.SNOWFLAKE.binaryFileName)
        bin.writeBytes(elfHeader(DeviceAbi.ARM64.elfMachine))
        bin.setExecutable(true, false)
        File(dir, "abi.txt").writeText(DeviceAbi.ARM64.androidAbi)
        val reason = AddonPackManager.validateInstalledBinary(AddonPackId.SNOWFLAKE, dir, DeviceAbi.ARM64)
        assertNull(reason)
        dir.deleteRecursively()
    }

    @Test
    fun parseReleaseJsonFindsMatchingAbiAsset() {
        val body = """
            [
              {
                "tag_name": "addons-v1",
                "assets": [
                  {
                    "name": "tor-armeabi-v7a.zip",
                    "browser_download_url": "https://example.com/tor-arm32.zip"
                  },
                  {
                    "name": "tor-arm64-v8a.zip",
                    "browser_download_url": "https://example.com/tor-arm64.zip"
                  }
                ]
              }
            ]
        """.trimIndent()
        val release = AddonPackManager.parseReleaseJson(
            body = body,
            packId = AddonPackId.TOR,
            wantAsset = "tor-arm64-v8a.zip",
            abi = "arm64-v8a",
            singleRelease = false
        )
        assertEquals("addons-v1", release?.version)
        assertEquals("tor-arm64-v8a.zip", release?.assetName)
        assertEquals("https://example.com/tor-arm64.zip", release?.downloadUrl)
        assertEquals("arm64-v8a", release?.abi)
    }

    @Test
    fun parseReleaseJsonAcceptsSingleReleaseObject() {
        val body = """
            {
              "tag_name": "v9",
              "assets": [
                {
                  "name": "snowflake-x86_64.zip",
                  "browser_download_url": "https://example.com/sf.zip"
                }
              ]
            }
        """.trimIndent()
        val release = AddonPackManager.parseReleaseJson(
            body = body,
            packId = AddonPackId.SNOWFLAKE,
            wantAsset = "snowflake-x86_64.zip",
            abi = "x86_64",
            singleRelease = true
        )
        assertEquals("v9", release?.version)
        assertNull(
            AddonPackManager.parseReleaseJson(
                body = body,
                packId = AddonPackId.SNOWFLAKE,
                wantAsset = "snowflake-arm64-v8a.zip",
                abi = "arm64-v8a",
                singleRelease = true
            )
        )
    }
}
