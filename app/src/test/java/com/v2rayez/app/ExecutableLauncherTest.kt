package com.v2rayez.app

import com.v2rayez.app.data.core.NativeBinaryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SELinux W^X (API 29+): downloaded binaries under filesDir must be launched via
 * `/system/bin/linker64` so the kernel does not `execve` an `app_data_file`.
 */
class ExecutableLauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun needsLinkerWrapDetectsFilesDirOnApi29() {
        val bin = File("/data/user/0/com.v2rayez.app/files/addons/lyrebird/1.0/lyrebird")
        assertTrue(NativeBinaryStore.needsLinkerWrap(bin, sdkInt = 29))
        assertFalse(NativeBinaryStore.needsLinkerWrap(bin, sdkInt = 28))
    }

    @Test
    fun nativeLibDirDoesNotWrap() {
        val bin = File("/data/app/~~x==/com.v2rayez.app-y==/lib/arm64/liblyrebird.so")
        assertFalse(NativeBinaryStore.needsLinkerWrap(bin, sdkInt = 29))
        assertEquals(listOf(bin.absolutePath), NativeBinaryStore.processArgv(bin, sdkInt = 29))
    }

    @Test
    fun processArgvFallsBackWhenLinkerMissing() {
        // Host JVMs usually lack /system/bin/linker64 — wrap is requested but falls back to direct path.
        val bin = File("/data/user/0/com.v2rayez.app/files/addons/tor/1.0/tor")
        val argv = NativeBinaryStore.processArgv(bin, listOf("-f", "torrc"), sdkInt = 29)
        assertTrue(argv.contains(bin.absolutePath))
        assertTrue(argv.contains("-f"))
    }

    @Test
    fun torExecSpecContainsBinaryPath() {
        val bin = File("/data/user/0/com.v2rayez.app/files/addons/lyrebird/1.0/lyrebird")
        val spec = NativeBinaryStore.torExecSpec(bin, sdkInt = 29)
        assertTrue(spec.contains(bin.absolutePath))
    }
}
