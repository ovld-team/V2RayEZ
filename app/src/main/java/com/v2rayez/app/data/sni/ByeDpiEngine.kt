package com.v2rayez.app.data.sni

import android.content.Context
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.PackAvailability
import com.v2rayez.app.domain.model.DesyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native byedpi (`ciadpi`) local SOCKS5 desync proxy. The `byedpi` binary is resolved
 * through [AddonPackManager] — a user-downloaded pack under `filesDir/addons/byedpi/<ver>/`
 * wins over the (optional) bundled `libbyedpi.so` in `nativeLibraryDir` (W3), so desync keeps
 * working after the binary is stripped from the APK and downloaded on demand.
 *
 * ciadpi listens as a SOCKS5 proxy on `-i <host> -p <port>` and applies TCP/TLS
 * desync (split / disorder / fake+ttl / oob / disoob / tlsrec) to outgoing
 * connections. The Xray proxy outbound dials through it via a SOCKS `dialerProxy`
 * (see `ConfigBuilder`) so the ClientHello is desynced on the wire.
 *
 * NOTE: without a downloaded or bundled `byedpi` binary [isAvailable] returns false and
 * [start] reports a clear error via [onLog].
 */
@Singleton
class ByeDpiEngine @Inject constructor(
    private val addonPacks: AddonPackManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null

    /** Optional log sink; wired to the app log by the caller. */
    var onLog: ((String) -> Unit)? = null

    /** Downloaded `byedpi` pack first, then the bundled jniLibs PIE; null when neither exists. */
    private fun binary(context: Context): File? = addonPacks.resolveBinary(PackAvailability.BYEDPI)

    /** True when a runnable byedpi binary is downloaded or bundled on this device. */
    fun isAvailable(context: Context): Boolean = binary(context) != null

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Launch byedpi as a local SOCKS5 proxy on [DesyncConfig.socksHost]:[DesyncConfig.socksPort]
     * applying the configured desync method. No-op (returns false) if the binary is missing.
     */
    suspend fun start(context: Context, desync: DesyncConfig): Boolean = withContext(Dispatchers.IO) {
        stopInternal()
        val bin = binary(context)
        if (bin == null || !bin.exists()) {
            onLog?.invoke("byedpi: binary not installed — download the ByeDPI pack in Core manager")
            return@withContext false
        }
        val args = com.v2rayez.app.data.core.NativeBinaryStore.processArgv(
            bin,
            buildList {
                add("-i"); add(desync.socksHost)
                add("-p"); add(desync.socksPort.toString())
                addAll(desync.toCiadpiArgs())
            }
        )
        runCatching {
            val proc = ProcessBuilder(args)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = proc
            onLog?.invoke("byedpi: started on ${desync.socksHost}:${desync.socksPort} (${desync.mode.label})")
            scope.launch { drainLog(proc) }
        }.onFailure {
            onLog?.invoke("byedpi: failed to start — ${it.message ?: it.javaClass.simpleName}")
            return@withContext false
        }
        true
    }

    /** Drain and forward the byedpi process output to [onLog]. */
    private fun drainLog(proc: Process) {
        runCatching {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) onLog?.invoke("byedpi: $trimmed")
                }
            }
        }
        if (process === proc) process = null
    }

    suspend fun stop() = withContext(Dispatchers.IO) { stopInternal() }

    private fun stopInternal() {
        process?.destroy()
        process = null
    }
}
