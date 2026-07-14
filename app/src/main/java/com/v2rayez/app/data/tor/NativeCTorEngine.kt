package com.v2rayez.app.data.tor

import android.content.Context
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.PackAvailability
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorEngineType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded classic C `tor` daemon. The `tor` binary is resolved through [AddonPackManager]
 * — a user-downloaded pack under `filesDir/addons/tor/<ver>/` wins, falling back to the
 * (optional) bundled `libtor.so` in `nativeLibraryDir`. A generated `torrc` wires the SOCKS
 * port and pluggable transports (also resolved via [AddonPackManager], see W3).
 *
 * CONNECTED is reported only from `Bootstrapped 100%` log lines — never from a bare
 * SOCKS TCP accept (Tor opens SocksPort before circuits exist).
 */
@Singleton
class NativeCTorEngine @Inject constructor(
    private val addonPacks: AddonPackManager
) : TorEngine {

    override val type: TorEngineType = TorEngineType.NATIVE_C

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var readerJob: Job? = null
    private val running = AtomicBoolean(false)

    /** Downloaded `tor` pack first, then the bundled jniLibs PIE; null when neither exists. */
    private fun binary(context: Context): File? = addonPacks.resolveBinary(PackAvailability.TOR)

    override fun isAvailable(context: Context): Boolean = binary(context) != null

    override fun isAlive(): Boolean = running.get() && process?.isAlive == true

    override suspend fun start(
        context: Context,
        config: TorConfig,
        bridges: List<String>,
        onStatus: (TorStatus) -> Unit
    ) = withContext(Dispatchers.IO) {
        stopInternal()
        val bin = binary(context)
        if (bin == null || !bin.exists()) {
            onStatus(TorStatus(TorState.ERROR, 0, "Tor binary not installed — download the Tor pack in Core manager", type))
            return@withContext
        }
        onStatus(TorStatus(TorState.STARTING, 0, "Starting native Tor…", type))

        val dataDir = File(context.filesDir, "tor").apply { mkdirs() }

        runCatching {
            // Inside runCatching: torrcLines throws IllegalStateException when a selected PT's
            // binary is missing, so a race between the isAvailable() gate and here surfaces as
            // an ERROR status instead of an unhandled crash in the caller's launch{}.
            val torrc = writeTorrc(context, config, bridges, dataDir)
            val argv = com.v2rayez.app.data.core.NativeBinaryStore.processArgv(bin, listOf("-f", torrc.absolutePath))
            val proc = ProcessBuilder(argv)
                .directory(dataDir)
                .redirectErrorStream(true)
                .start()
            process = proc
            running.set(true)
            onStatus(TorStatus(TorState.BOOTSTRAPPING, 0, "Bootstrapping…", type))
            readerJob = scope.launch {
                readBootstrap(proc) { st ->
                    if (running.get()) onStatus(st)
                }
            }
        }.onFailure {
            running.set(false)
            onStatus(TorStatus(TorState.ERROR, 0, it.message ?: "Failed to start Tor", type))
        }
        Unit
    }

    /** Parse `Bootstrapped NN%` progress lines from the daemon's stdout. */
    private fun readBootstrap(proc: Process, onStatus: (TorStatus) -> Unit) {
        runCatching {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!running.get()) break
                    val match = BOOTSTRAP_REGEX.find(line) ?: continue
                    val pct = match.groupValues[1].toIntOrNull() ?: continue
                    val state = if (pct >= 100) TorState.CONNECTED else TorState.BOOTSTRAPPING
                    onStatus(TorStatus(state, pct, line.trim().take(120), type))
                }
            }
        }
        if (running.get() && process == proc) {
            // Process exited unexpectedly before CONNECTED.
            onStatus(TorStatus(TorState.ERROR, 0, "Tor process exited", type))
            running.set(false)
        }
    }

    private fun writeTorrc(context: Context, config: TorConfig, bridges: List<String>, dataDir: File): File {
        val lines = buildList {
            add("SocksPort ${config.socksHost}:${config.socksPort}")
            add("DataDirectory ${dataDir.absolutePath}")
            add("ClientOnly 1")
            add("AvoidDiskWrites 1")
            // Required so [readBootstrap] can parse Bootstrapped NN% from stdout.
            add("Log notice stdout")
            add("SafeLogging 0")
            addAll(PluggableTransports.torrcLines(addonPacks, config.transport))
            bridges.forEach { raw ->
                val line = raw.trim().removePrefix("Bridge ").trim()
                if (line.isNotEmpty()) add("Bridge $line")
            }
        }
        return File(dataDir, "torrc").apply { writeText(lines.joinToString("\n")) }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) { stopInternal() }

    private suspend fun stopInternal() {
        running.set(false)
        readerJob?.cancel()
        readerJob = null
        val proc = process
        process = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(1500, TimeUnit.MILLISECONDS)
                }
            }
            // Let SOCKS port release before the next start.
            kotlinx.coroutines.delay(400)
        }
    }

    private companion object {
        val BOOTSTRAP_REGEX = Regex("Bootstrapped (\\d+)%")
    }
}
