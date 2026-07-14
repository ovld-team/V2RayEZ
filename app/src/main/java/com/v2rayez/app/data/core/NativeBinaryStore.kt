package com.v2rayez.app.data.core

import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Shared, pure-JVM helpers for verifying and extracting downloaded native binaries — used by
 * both [CoreBinaryManager] (proxy cores under `filesDir/cores/`) and [AddonPackManager]
 * (Tor / pluggable-transport / desync addons under `filesDir/addons/`). Keeping this logic in
 * one place means every downloaded executable goes through the same sha256 + ABI/ELF gate
 * before it is ever marked executable or started.
 */
object NativeBinaryStore {

    /** Lowercase hex sha256 digest of [file]'s contents. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True when [expectedHex] is blank/null (no pinned hash supplied — caller decides whether
     * that's acceptable) or matches [file]'s sha256, case-insensitively.
     */
    fun verifySha256(file: File, expectedHex: String?): Boolean {
        if (expectedHex.isNullOrBlank()) return true
        return runCatching { sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true) }.getOrDefault(false)
    }

    /** ELF `e_machine` from a little-endian ELF binary, or null if not a recognizable ELF file. */
    fun readElfMachine(file: File): Int? = runCatching {
        file.inputStream().use { input ->
            val hdr = ByteArray(20)
            if (input.read(hdr) < 20) return@runCatching null
            if (hdr[0] != 0x7f.toByte() || hdr[1] != 'E'.code.toByte()) return@runCatching null
            (hdr[18].toInt() and 0xff) or ((hdr[19].toInt() and 0xff) shl 8)
        }
    }.getOrNull()

    /** True when [file] is an ELF binary whose `e_machine` equals [expectedMachine]. */
    fun elfMatches(file: File, expectedMachine: Int): Boolean = readElfMachine(file) == expectedMachine

    fun markExecutable(file: File): Boolean = file.setExecutable(true, false)

    /** True when [binary] lives under a writable app-data path that SELinux blocks for exec. */
    fun needsLinkerWrap(binary: File, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt < 29) return false
        val path = binary.absolutePath
        // nativeLibraryDir /apk libs are already executable app_lib_file — leave alone.
        if (path.contains("/lib/") && !path.contains("/files/") && !path.contains("/cache/")) return false
        return path.contains("/files/") ||
            path.contains("/cache/") ||
            path.contains("/no_backup/") ||
            path.contains("/code_cache/")
    }

    /**
     * Build a [ProcessBuilder]-ready argv for [binary] + [args].
     *
     * On API 29+ SELinux denies `execve` of files under the writable app data tree
     * (`filesDir` / `cacheDir`). Invoking the system dynamic linker instead —
     * `/system/bin/linker64 /abs/path/to/elf …` — makes the kernel see a system-trusted
     * executable while the linker loads and runs the downloaded ELF (Termux/Android-Docs
     * pattern). Bundled `nativeLibraryDir` PIEs need no wrap.
     */
    fun processArgv(
        binary: File,
        args: List<String> = emptyList(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): List<String> {
        val abs = binary.absoluteFile
        if (!needsLinkerWrap(abs, sdkInt)) return listOf(abs.absolutePath) + args
        val linker = systemLinkerFor(abs) ?: return listOf(abs.absolutePath) + args
        return listOf(linker.absolutePath, abs.absolutePath) + args
    }

    /** Space-joined argv for `ClientTransportPlugin … exec …` torrc lines. */
    fun torExecSpec(binary: File, sdkInt: Int = Build.VERSION.SDK_INT): String =
        processArgv(binary, sdkInt = sdkInt).joinToString(" ")

    /** System linker matching the ELF class of [binary], or null if missing. */
    fun systemLinkerFor(binary: File): File? {
        val elf64 = isElf64(binary) ?: true // prefer linker64 when ELF class unknown
        val primary = File(if (elf64) "/system/bin/linker64" else "/system/bin/linker")
        if (primary.exists()) return primary
        val fallback = File(if (elf64) "/system/bin/linker" else "/system/bin/linker64")
        return fallback.takeIf { it.exists() }
    }

    /** ELF EI_CLASS: 1 = 32-bit, 2 = 64-bit. */
    fun isElf64(file: File): Boolean? = runCatching {
        file.inputStream().use { input ->
            val hdr = ByteArray(5)
            if (input.read(hdr) < 5) return@runCatching null
            if (hdr[0] != 0x7f.toByte() || hdr[1] != 'E'.code.toByte()) return@runCatching null
            when (hdr[4].toInt() and 0xff) {
                2 -> true
                1 -> false
                else -> null
            }
        }
    }.getOrNull()

    /**
     * Extract a single executable out of [archive] into [workDir], picking the entry whose
     * name contains one of [nameHints] (case-insensitive). Supports `.tar.gz`/`.tgz`, a lone
     * `.gz` (single-file gzip, common for PT binaries), and `.zip`. Returns the extracted file,
     * or null if nothing matching was found.
     */
    fun extractArchive(archive: File, workDir: File, nameHints: List<String>): File? {
        workDir.mkdirs()
        when {
            archive.name.endsWith(".tar.gz") || archive.name.endsWith(".tgz") -> extractTarGz(archive, workDir)
            archive.name.endsWith(".gz") && !archive.name.endsWith(".tar.gz") -> {
                val out = File(workDir, nameHints.firstOrNull() ?: archive.nameWithoutExtension)
                GZIPInputStream(archive.inputStream()).use { gz ->
                    out.outputStream().use { gz.copyTo(it) }
                }
                markExecutable(out)
                return out.takeIf { it.exists() && it.length() > 0 }
            }
            archive.name.endsWith(".zip", ignoreCase = true) -> extractZipFlat(archive, workDir)
            else -> {
                // Not a recognized archive — assume it's already the raw executable.
                return archive.takeIf { it.exists() && it.length() > 0 }
            }
        }
        return workDir.walkTopDown().firstOrNull { f ->
            f.isFile && f.length() > 0 && nameHints.any { hint -> f.name.equals(hint, true) || f.name.contains(hint, true) }
        }
    }

    /** Flatten a zip archive's file entries directly into [workDir] (drops internal directories). */
    private fun extractZipFlat(archive: File, workDir: File) {
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val simple = e.name.substringAfterLast('/').ifBlank { e.name }
                if (simple.contains("..") || simple.contains('/') || simple.contains('\\')) return@forEach
                val target = File(workDir, simple)
                zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    /** Pure-JVM tar.gz extract (Android often has no usable `tar` for apps); flattens into [workDir]. */
    private fun extractTarGz(archive: File, workDir: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            val buffer = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = gzip.read(buffer, read, 512 - read)
                    if (n < 0) return
                    read += n
                }
                val nameBytes = buffer.copyOfRange(0, 100)
                val nameEnd = nameBytes.indexOf(0).let { if (it < 0) 100 else it }
                val name = String(nameBytes, 0, nameEnd, Charsets.US_ASCII).trim()
                if (name.isEmpty()) return
                val sizeOctal = String(buffer, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val size = sizeOctal.toLongOrNull(8) ?: 0L
                if (name.contains("..") || name.startsWith("/")) {
                    skipTarBody(gzip, size)
                    continue
                }
                val typeFlag = buffer[156].toInt().toChar()
                val dest = File(workDir, name.substringAfterLast('/').ifBlank { name })
                when (typeFlag) {
                    '5' -> { /* directory entry, no body */ }
                    '0', '\u0000' -> {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            var remaining = size
                            val chunk = ByteArray(8192)
                            while (remaining > 0) {
                                val n = gzip.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                out.write(chunk, 0, n)
                                remaining -= n
                            }
                        }
                        markExecutable(dest)
                    }
                    else -> skipTarBody(gzip, size)
                }
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0) gzip.skip(padding.toLong())
            }
        }
    }

    private fun skipTarBody(input: java.io.InputStream, size: Long) {
        var remaining = size
        val chunk = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
        val padding = ((512 - (size % 512)) % 512).toInt()
        if (padding > 0) input.skip(padding.toLong())
    }
}
