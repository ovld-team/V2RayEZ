package com.v2rayez.app.data.dnstunnel

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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the dnstt DNS-tunnel client ([AddonPackId.DNSTUNNEL]) as a PIE process, resolved through
 * [AddonPackManager] (`filesDir/addons/dnstunnel/<ver>/dnstt`). dnstt opens a local TCP listener
 * that forwards over the DNS tunnel to whatever the dnstt **server** forwards to.
 * **Requirement for HevTunBridge:** the remote dnstt `-final` target must speak **SOCKS5**,
 * so the local TCP pipe is a byte-transparent SOCKS endpoint. Non-SOCKS remotes will not
 * tunnel correctly through hev.
 *
 * CLI (verified against `_ref/dnstt/dnstt-client/main.go`):
 *   `dnstt-client [-doh URL | -dot ADDR | -udp ADDR] -pubkey <hex> DOMAIN 127.0.0.1:<port>`
 *
 * When the pack is missing [start] returns false with a Core-manager CTA on [onLog].
 */
@Singleton
class DnsTunnelEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonPacks: AddonPackManager
) {
    companion object {
        private const val TAG = "DnsTunnelEngine"

        /**
         * Pure argv builder (unit-testable) for the dnstt client. [resolverMode] is doh/dot/udp;
         * [resolver] the resolver URL/addr; local listener bound to 127.0.0.1:[listenPort].
         */
        fun buildArgs(server: Server, listenPort: Int): List<String> {
            val modeFlag = when (server.dnsTunnelMode.lowercase()) {
                "dot" -> "-dot"
                "udp" -> "-udp"
                else -> "-doh"
            }
            return buildList {
                add(modeFlag); add(server.dnsTunnelResolver)
                add("-pubkey"); add(server.dnsTunnelPubKey)
                add(server.dnsTunnelDomain)
                add("127.0.0.1:$listenPort")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processRef = AtomicReference<Process?>(null)
    @Volatile private var localPort: Int = 0

    var onLog: ((String) -> Unit)? = null

    /** True when a runnable dnstt binary is downloaded (or bundled) on this device. */
    fun isAvailable(): Boolean = addonPacks.resolveBinary(AddonPackId.DNSTUNNEL) != null

    val isRunning: Boolean get() = processRef.get()?.isAlive == true

    fun localTcpPort(): Int = localPort

    /**
     * Launch dnstt with [server]'s tunnel params, exposing the forwarder on [listenPort].
     * Returns false (with CTA on [onLog]) when the pack is missing or the process dies early.
     */
    suspend fun start(server: Server, listenPort: Int): Boolean = withContext(Dispatchers.IO) {
        stopInternal()
        val bin = addonPacks.resolveBinary(AddonPackId.DNSTUNNEL)
        if (bin == null) {
            onLog?.invoke("DNS tunnel: binary not installed — download the DNS Tunnel pack in Core manager")
            return@withContext false
        }
        if (server.dnsTunnelDomain.isBlank() || server.dnsTunnelPubKey.isBlank()) {
            onLog?.invoke("DNS tunnel: domain/public key missing — edit the server")
            return@withContext false
        }
        localPort = listenPort
        val cmd = NativeBinaryStore.processArgv(bin, buildArgs(server, listenPort))
        Log.i(TAG, "Starting dnstt: ${cmd.joinToString(" ")}")
        val proc = runCatching {
            ProcessBuilder(cmd).directory(context.filesDir).redirectErrorStream(true).start()
        }.onFailure {
            onLog?.invoke("DNS tunnel: failed to start — ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull() ?: return@withContext false
        processRef.set(proc)
        scope.launch { drainLog(proc) }

        Thread.sleep(500)
        if (!proc.isAlive) {
            onLog?.invoke("DNS tunnel: exited early (code ${runCatching { proc.exitValue() }.getOrDefault(-1)})")
            processRef.set(null)
            return@withContext false
        }
        true
    }

    private fun drainLog(proc: Process) {
        runCatching {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) onLog?.invoke("dnstt: $trimmed")
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
        localPort = 0
    }
}
