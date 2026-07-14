package com.v2rayez.app

import com.v2rayez.app.data.core.NativeBinaryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

/** Covers sha256 + ABI/ELF helpers shared by [com.v2rayez.app.data.core.CoreBinaryManager] and [AddonPackManager]. */
class NativeBinaryStoreTest {

    private fun elfHeader(machine: Int): ByteArray {
        val hdr = ByteArray(20)
        hdr[0] = 0x7f; hdr[1] = 'E'.code.toByte(); hdr[2] = 'L'.code.toByte(); hdr[3] = 'F'.code.toByte()
        hdr[18] = (machine and 0xff).toByte()
        hdr[19] = ((machine shr 8) and 0xff).toByte()
        return hdr
    }

    @Test
    fun sha256HexMatchesKnownVector() {
        val f = kotlin.io.path.createTempFile("sha", ".bin").toFile()
        f.writeBytes("hello world".toByteArray())
        // sha256("hello world")
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            NativeBinaryStore.sha256Hex(f)
        )
        f.delete()
    }

    @Test
    fun verifySha256AcceptsBlankExpectation() {
        val f = kotlin.io.path.createTempFile("sha", ".bin").toFile()
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(NativeBinaryStore.verifySha256(f, null))
        assertTrue(NativeBinaryStore.verifySha256(f, ""))
        assertTrue(NativeBinaryStore.verifySha256(f, "  "))
        f.delete()
    }

    @Test
    fun verifySha256RejectsMismatch() {
        val f = kotlin.io.path.createTempFile("sha", ".bin").toFile()
        f.writeBytes("hello world".toByteArray())
        assertTrue(NativeBinaryStore.verifySha256(f, "B94D27B9934D3E08A52E52D7DA7DABFAC484EFE37A5380EE9088F7ACE2EFCDE9"))
        assertFalse(NativeBinaryStore.verifySha256(f, "0000000000000000000000000000000000000000000000000000000000000"))
        f.delete()
    }

    @Test
    fun readElfMachineRejectsNonElf() {
        val f = kotlin.io.path.createTempFile("not-elf", ".bin").toFile()
        f.writeBytes(ByteArray(20) { it.toByte() })
        assertNull(NativeBinaryStore.readElfMachine(f))
        f.delete()
    }

    @Test
    fun readElfMachineParsesArm64Header() {
        val f = kotlin.io.path.createTempFile("fake-elf", ".bin").toFile()
        f.writeBytes(elfHeader(183))
        assertEquals(183, NativeBinaryStore.readElfMachine(f))
        assertTrue(NativeBinaryStore.elfMatches(f, 183))
        assertFalse(NativeBinaryStore.elfMatches(f, 62))
        f.delete()
    }

    @Test
    fun extractArchiveFromZipFindsExecutableByHint() {
        val dir = createTempDirectory("nbs-zip").toFile()
        val archive = File(dir, "pack.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("nested/tor"))
            zip.write(elfHeader(183))
            zip.closeEntry()
        }
        val work = File(dir, "work")
        val extracted = NativeBinaryStore.extractArchive(archive, work, listOf("tor"))
        assertNotNull(extracted)
        assertEquals("tor", extracted!!.name)
        assertTrue(NativeBinaryStore.elfMatches(extracted, 183))
        dir.deleteRecursively()
    }

    @Test
    fun extractArchiveFromLoneGzUnpacksSingleFile() {
        val dir = createTempDirectory("nbs-gz").toFile()
        val archive = File(dir, "snowflake.gz")
        GZIPOutputStream(archive.outputStream()).use { gz -> gz.write(elfHeader(62)) }
        val work = File(dir, "work")
        val extracted = NativeBinaryStore.extractArchive(archive, work, listOf("snowflake"))
        assertNotNull(extracted)
        assertTrue(extracted!!.canExecute())
        assertTrue(NativeBinaryStore.elfMatches(extracted, 62))
        dir.deleteRecursively()
    }

    @Test
    fun extractArchiveFlattensTraversalEntriesInsteadOfEscaping() {
        val dir = createTempDirectory("nbs-slip").toFile()
        val archive = File(dir, "evil.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../../evil"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val work = File(dir, "work")
        val extracted = NativeBinaryStore.extractArchive(archive, work, listOf("evil"))
        // Entries are flattened to their basename before writing, so "../../evil" lands safely
        // inside `work/`, never escaping into `dir` (the zip's own directory) or above it.
        assertNotNull(extracted)
        assertEquals(work, extracted!!.parentFile)
        assertFalse(File(dir, "evil").exists())
        dir.deleteRecursively()
    }

    @Test
    fun extractArchiveSkipsBareDotDotEntry() {
        val dir = createTempDirectory("nbs-dotdot").toFile()
        val archive = File(dir, "dotdot.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(".."))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val work = File(dir, "work")
        val extracted = NativeBinaryStore.extractArchive(archive, work, listOf(".."))
        assertNull(extracted)
        dir.deleteRecursively()
    }

}
