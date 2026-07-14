package com.v2rayez.app.data.psiphon

import android.content.Context
import android.util.Log
import com.v2rayez.app.data.core.AddonPackId
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.NativeBinaryStore
import com.v2rayez.app.domain.model.Server
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the Psiphon console client (`psiphon-tunnel-core`, [AddonPackId.PSIPHON]) as a PIE process
 * that opens a local SOCKS proxy. The binary is resolved through [AddonPackManager] — a downloaded
 * pack under `filesDir/addons/psiphon/<ver>/` wins over any bundled PIE — so the multi-MB Go binary
 * never bloats the APK. When the pack is missing [start] returns false with a Core-manager CTA.
 *
 * The console client emits notices on stderr; we watch for `ListeningSocksProxyPort` to confirm the
 * SOCKS inbound is up before [HevTunBridge] bridges the TUN to it (see V2RayVpnService).
 */
@Singleton
class PsiphonEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonPacks: AddonPackManager
) {
    companion object {
        private const val TAG = "PsiphonEngine"
        private val PORT_NOTICE = Regex("ListeningSocksProxyPort\"?[^0-9]*([0-9]+)")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processRef = AtomicReference<Process?>(null)
    @Volatile private var socksPort: Int = 0
    @Volatile private var socksNoticeSeen: Boolean = false

    var onLog: ((String) -> Unit)? = null

    /** True when a runnable Psiphon binary is downloaded (or bundled) on this device. */
    fun isAvailable(): Boolean = addonPacks.resolveBinary(AddonPackId.PSIPHON) != null

    val isRunning: Boolean get() = processRef.get()?.isAlive == true

    fun localSocksPort(): Int = socksPort

    /**
     * Launch Psiphon with [server]'s config blob, exposing SOCKS on [socksPort].
     * Returns false (with a CTA on [onLog]) when the pack is missing or the process dies early.
     */
    suspend fun start(server: Server, socksPort: Int): Boolean = withContext(Dispatchers.IO) {
        stopInternal()
        val bin = addonPacks.resolveBinary(AddonPackId.PSIPHON)
        if (bin == null) {
            onLog?.invoke("Psiphon: binary not installed — download the Psiphon pack in Core manager")
            return@withContext false
        }
        if (server.psiphonConfig.isBlank() || !PsiphonConfigBuilder.looksComplete(server.psiphonConfig)) {
            onLog?.invoke("Psiphon: incomplete config — import a full psiphon:// profile or paste Psiphon JSON")
            return@withContext false
        }
        this@PsiphonEngine.socksPort = socksPort
        socksNoticeSeen = false
        val workDir = File(context.filesDir, "psiphon").apply { mkdirs() }
        val dataDir = File(workDir, "data").apply { mkdirs() }
        val configFile = File(workDir, "psiphon.config")
        configFile.writeText(PsiphonConfigBuilder.build(server.psiphonConfig, socksPort, dataDir.absolutePath))

        val cmd = NativeBinaryStore.processArgv(
            bin,
            listOf("-config", configFile.absolutePath, "-dataRootDirectory", dataDir.absolutePath)
        )
        Log.i(TAG, "Starting Psiphon: ${cmd.joinToString(" ")}")
        val proc = runCatching {
            ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
        }.onFailure {
            onLog?.invoke("Psiphon: failed to start — ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull() ?: return@withContext false
        processRef.set(proc)
        scope.launch { drainLog(proc) }

        // Wait until notices report ListeningSocksProxyPort (or the process dies).
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                onLog?.invoke("Psiphon: exited early (code ${runCatching { proc.exitValue() }.getOrDefault(-1)})")
                processRef.set(null)
                return@withContext false
            }
            if (socksNoticeSeen) break
            Thread.sleep(200)
        }
        if (!proc.isAlive) {
            onLog?.invoke("Psiphon: exited before SOCKS was ready")
            processRef.set(null)
            return@withContext false
        }
        if (!socksNoticeSeen) {
            this@PsiphonEngine.socksPort = socksPort
            onLog?.invoke("Psiphon: SOCKS notice not seen within 15s — using configured port $socksPort")
        }
        true
    }

    private fun drainLog(proc: Process) {
        runCatching {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    PORT_NOTICE.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { port ->
                        socksPort = port
                        socksNoticeSeen = true
                    }
                    onLog?.invoke("psiphon: $trimmed")
                }
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) { stopInternal() }

    private fun stopInternal() {
        val p = processRef.getAndSet(null) ?: return
        runCatching {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        socksPort = 0
        socksNoticeSeen = false
    }
}
