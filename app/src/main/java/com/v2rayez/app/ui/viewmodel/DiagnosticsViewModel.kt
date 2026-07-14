package com.v2rayez.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.PackAvailability
import com.v2rayez.app.data.core.PackSource
import com.v2rayez.app.data.core.ProcessProxyCore
import com.v2rayez.app.data.core.V2RayCore
import com.v2rayez.app.data.sni.SniScanner
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.data.tor.TorState
import com.v2rayez.app.data.core.CoreBinaryManager
import com.v2rayez.app.data.vpn.PerAppTunnelPolicy
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.model.TorTransport
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class CheckStatus { RUNNING, PASS, WARN, FAIL, SKIPPED }

/**
 * One diagnostic probe. [id] is a stable key the UI maps to a localized label;
 * [result] is a short raw value (IP, ms, version), [detail] optional expandable info.
 */
data class DiagnosticCheck(
    val id: String,
    val status: CheckStatus = CheckStatus.RUNNING,
    val result: String = "",
    val detail: String? = null,
    val durationMs: Long = -1
)

data class DiagnosticSection(val id: String, val checks: List<DiagnosticCheck>)

data class DiagnosticsUiState(
    val running: Boolean = false,
    val sections: List<DiagnosticSection> = emptyList()
)

/**
 * Feature-aware diagnostics: runs grouped checks concurrently and reports
 * status + value + timing per row. Sections appear only when the related
 * feature (VPN, Tor, SNI bypass) is active, so the report reflects the
 * user's actual setup.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val vpn: VpnController,
    private val tor: TorController,
    private val sniScanner: SniScanner,
    private val core: V2RayCore,
    private val processCore: ProcessProxyCore,
    private val settings: SettingsRepository,
    private val servers: ServerRepository,
    private val addonPacks: AddonPackManager,
    private val binaryManager: CoreBinaryManager,
    baseHttp: OkHttpClient
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private val http = baseHttp.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun run() {
        if (_state.value.running) return
        viewModelScope.launch {
            val cfg = settings.current()
            val conn = vpn.connectionState.value
            val connected = conn.status == ConnectionStatus.CONNECTED
            val torEnabled = cfg.tor.enabled
            val sniDomain = cfg.sni.spoofDomain.ifBlank { cfg.sni.bestSni }
            val sniActive = cfg.sni.spoofEnabled && sniDomain.isNotBlank()
            val offlineServerId = if (!connected) cfg.defaultServerId ?: cfg.lastServerId else null
            // Independent of connection state: the pinned/last server's protocol may need a
            // downloadable core (SSH → sing-box) or addon pack (Psiphon/DNS tunnel) that isn't
            // installed — the honesty row must say so even while happily connected on Xray.
            val selectedServerId = cfg.defaultServerId ?: cfg.lastServerId

            // Build the section skeleton up front so every row shows a spinner immediately.
            val sections = buildList {
                add(DiagnosticSection(SEC_CONNECTIVITY, listOf(check(ID_INTERNET), check(ID_DNS), check(ID_PUBLIC_IP))))
                add(
                    DiagnosticSection(
                        SEC_TUNNEL,
                        buildList {
                            add(check(ID_VPN_ACTIVE))
                            add(check(ID_CORE_RUNNING))
                            add(check(ID_LOCAL_DNS))
                            add(check(ID_TUNNEL_GENERATE_204))
                            add(check(ID_PER_APP_POLICY))
                            if (connected) {
                                add(check(ID_TUNNEL_LATENCY))
                                add(check(ID_TUNNEL_IP))
                                add(check(ID_SESSION_TRAFFIC))
                            }
                        }
                    )
                )
                if (offlineServerId != null) add(DiagnosticSection(SEC_SERVER, listOf(check(ID_SERVER_RTT))))
                if (torEnabled) add(
                    DiagnosticSection(
                        SEC_TOR,
                        listOf(check(ID_TOR_BOOTSTRAP), check(ID_TOR_SOCKS), check(ID_TOR_DNS), check(ID_TOR_EXIT_IP))
                    )
                )
                if (sniActive) add(DiagnosticSection(SEC_SNI, listOf(check(ID_SNI_PROBE))))
                add(DiagnosticSection(SEC_CORE, listOf(check(ID_CORE_VERSION))))
                // Honesty rows: always shown so the report never silently omits a subsystem the
                // user configured — each check reports SKIPPED (not hidden) when not applicable.
                add(
                    DiagnosticSection(
                        SEC_SYSTEM,
                        listOf(
                            check(ID_PROTOCOL_PACK),
                            check(ID_TOR_PACKS),
                            check(ID_HOTSPOT),
                            check(ID_ALWAYS_ON),
                            check(ID_PENDING_ADDONS)
                        )
                    )
                )
            }
            _state.value = DiagnosticsUiState(running = true, sections = sections)

            coroutineScope {
                val jobs = mutableListOf(
                    async { runInternet() },
                    async { runDns() },
                    async { runPublicIp() },
                    async { runCoreVersion() },
                    async { runVpnActive(connected) },
                    async { runCoreRunning(connected) },
                    async { runLocalDns(connected, cfg.enableLocalDns, cfg.dns.remoteDns) },
                    async { runTunnelGenerate204(connected) },
                    async { runPerAppPolicy(cfg) }
                )
                if (connected) {
                    jobs += async { runTunnelLatency() }
                    jobs += async { runTunnelIp(cfg.httpPort, cfg.socksPort) }
                    jobs += async { runSessionTraffic() }
                }
                if (offlineServerId != null) jobs += async { runServerRtt(offlineServerId) }
                if (torEnabled) {
                    jobs += async { runTorBootstrap() }
                    jobs += async { runTorSocks(cfg.tor.socksHost, cfg.tor.socksPort) }
                    jobs += async { runTorDns(cfg.tor.socksHost, cfg.tor.dnsPort) }
                    jobs += async { runTorExitIp(cfg.tor.socksHost, cfg.tor.socksPort) }
                }
                if (sniActive) jobs += async { runSniProbe(sniDomain) }
                jobs += async { runProtocolPack(selectedServerId) }
                jobs += async { runTorPacks(cfg.tor.enabled, cfg.tor.transport) }
                jobs += async { runHotspot(cfg.allowLan, cfg.enableLanSharing, cfg.socksPort, cfg.httpPort) }
                jobs += async { runAlwaysOn(connected, cfg.vpnAlwaysOn, conn.alwaysOn) }
                jobs += async { runPendingAddons(cfg.pendingAddonInstall) }
                jobs.awaitAll()
            }
            _state.update { it.copy(running = false) }
        }
    }

    // ------------------------------------------------------------ connectivity
    private suspend fun runInternet() = timed(ID_INTERNET) {
        val code = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build())
                .execute().use { it.code }
        }
        if (code == 204 || code in 200..299) pass("HTTP $code") else fail("HTTP $code")
    }

    private suspend fun runDns() = timed(ID_DNS) {
        val ip = withContext(Dispatchers.IO) { InetAddress.getByName("cloudflare.com").hostAddress }
        if (ip != null) pass(ip) else fail("no address")
    }

    private suspend fun runPublicIp() = timed(ID_PUBLIC_IP) {
        val ip = fetchText(http, "https://api.ipify.org")
        if (ip != null) pass(ip) else fail("unreachable")
    }

    // ------------------------------------------------------------ tunnel
    private suspend fun runVpnActive(expectedConnected: Boolean) = timed(ID_VPN_ACTIVE) {
        val state = vpn.connectionState.value.status
        if (expectedConnected && state == ConnectionStatus.CONNECTED) pass("active")
        else fail(state.name.lowercase(), "Android VPN state is not connected")
    }

    private suspend fun runCoreRunning(expectedConnected: Boolean) = timed(ID_CORE_RUNNING) {
        val xray = core.isRunning
        val process = processCore.isRunning
        when {
            xray -> pass("Xray running")
            process -> pass("Process core running")
            expectedConnected -> fail("stopped", "VPN state says connected but no proxy core is running")
            else -> fail("stopped", "No proxy core is running")
        }
    }

    private suspend fun runLocalDns(connected: Boolean, enabled: Boolean, rawServer: String) =
        timed(ID_LOCAL_DNS) {
            if (!connected) return@timed fail("VPN inactive", "Connect before testing tunnel DNS")
            if (!enabled) return@timed skip("disabled")
            val endpoint = parseDnsEndpoint(rawServer)
                ?: return@timed fail("invalid resolver", "Configured resolver is not a usable host and port")
            val ok = probeDnsUdp(endpoint.first, endpoint.second)
            if (ok && (core.isRunning || processCore.isRunning)) {
                pass("${endpoint.first}:${endpoint.second}", "Resolver answered a real DNS query while the tunnel core was running")
            } else {
                fail("${endpoint.first}:${endpoint.second} unreachable", "Local DNS/upstream resolver did not answer")
            }
        }

    private suspend fun runTunnelGenerate204(connected: Boolean) = timed(ID_TUNNEL_GENERATE_204) {
        if (!connected) return@timed fail("VPN inactive")
        val ms = withContext(Dispatchers.IO) {
            if (core.isRunning) core.measureConnectedDelay("https://www.gstatic.com/generate_204")
            else if (processCore.isRunning) processCore.measureViaSocks(
                processCore.localSocksPort(),
                "https://www.gstatic.com/generate_204",
                timeoutMs = 10_000
            ) else -1L
        }
        if (ms > 0) pass("$ms ms") else fail("unreachable", "generate_204 failed through the active tunnel")
    }

    private suspend fun runPerAppPolicy(cfg: com.v2rayez.app.domain.model.AppSettings) =
        timed(ID_PER_APP_POLICY) {
            val decision = PerAppTunnelPolicy.decide(cfg, "com.v2rayez.app")
            val conflict = PerAppTunnelPolicy.conflictMessage(cfg)
            when {
                conflict != null -> fail("conflict", conflict)
                !cfg.appProxy.enabled -> skip("disabled")
                else -> pass(
                    "${decision.mode.name.lowercase()} · ${decision.packages.size}",
                    "Policy is valid; reconnect is required after changing the list"
                )
            }
        }

    private suspend fun runTunnelLatency() = timed(ID_TUNNEL_LATENCY) {
        val ms = withContext(Dispatchers.IO) { core.measureConnectedDelay() }
        when {
            ms in 0..1500 -> pass("$ms ms")
            ms > 1500 -> warn("$ms ms", "High latency through the tunnel")
            else -> fail("no response", "The tunnel did not answer a test request")
        }
    }

    /** Leak check: egress IP through the local proxy must differ from the direct IP. */
    private suspend fun runTunnelIp(httpPort: Int, socksPort: Int) = timed(ID_TUNNEL_IP) {
        fun client(proxy: Proxy) = http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .proxy(proxy)
            .build()
        val tunnelIp = fetchText(client(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))), "https://api.ipify.org")
            ?: fetchText(client(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))), "https://api.ipify.org")
            ?: return@timed fail("unreachable", "Could not reach the internet through the tunnel proxy")
        val directIp = fetchText(http, "https://api.ipify.org")
        when {
            directIp == null -> pass(tunnelIp, "Direct network unavailable for comparison")
            directIp == tunnelIp -> warn(tunnelIp, "Tunnel egress IP equals the direct IP — traffic may be leaking outside the VPN")
            else -> pass(tunnelIp, "Direct IP $directIp, tunnel IP $tunnelIp — no leak detected")
        }
    }

    private suspend fun runSessionTraffic() = timed(ID_SESSION_TRAFFIC) {
        val conn = vpn.connectionState.value
        pass("↓ ${conn.sessionDownLabel} · ↑ ${conn.sessionUpLabel}", "Bytes moved through the tunnel this session")
    }

    // ------------------------------------------------------------ server (offline)
    private suspend fun runServerRtt(serverId: String) = timed(ID_SERVER_RTT) {
        val server = servers.getServer(serverId) ?: return@timed skip("server not found")
        val result = vpn.testLatency(server)
        if (result.success) pass("${result.pingMs} ms", server.name)
        else fail(result.message.ifBlank { "unreachable" }, server.name)
    }

    // ------------------------------------------------------------ tor
    private suspend fun runTorBootstrap() = timed(ID_TOR_BOOTSTRAP) {
        val status = tor.status.value
        when (status.state) {
            TorState.CONNECTED -> pass("100%")
            TorState.BOOTSTRAPPING, TorState.STARTING -> warn("${status.bootstrapPercent}%", status.message.ifBlank { null })
            TorState.ERROR -> fail(status.message.ifBlank { "bootstrap error" })
            TorState.OFF -> fail("not running", "Tor is enabled in settings but the process is not running")
        }
    }

    private suspend fun runTorSocks(host: String, port: Int) = timed(ID_TOR_SOCKS) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 2000); true }
            }.getOrDefault(false)
        }
        if (ok) pass("$host:$port") else fail("$host:$port closed", "The Tor SOCKS port is not accepting connections")
    }

    private suspend fun runTorDns(host: String, port: Int) = timed(ID_TOR_DNS) {
        val ok = probeDnsUdp(host, port)
        if (ok) pass("$host:$port") else fail("$host:$port unreachable", "Tor DNSPort did not answer a real DNS query")
    }

    private suspend fun runTorExitIp(host: String, port: Int) = timed(ID_TOR_EXIT_IP) {
        val torClient = http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
            .build()
        val ip = fetchText(torClient, "https://api.ipify.org")
        if (ip != null) pass(ip, "Exit node IP as seen by the internet") else fail("unreachable", "Could not fetch an IP through the Tor SOCKS proxy")
    }

    // ------------------------------------------------------------ sni
    private suspend fun runSniProbe(domain: String) = timed(ID_SNI_PROBE) {
        val results = sniScanner.scan(domains = listOf(domain), tries = 2, timeoutMs = 5000, concurrency = 1)
        val r = results.firstOrNull()
        when {
            r == null || !r.success -> fail(domain, "The configured SNI no longer answers — rescan from the SNI tool")
            r.stability < 100 -> warn("${r.pingMs} ms · ${r.stability}%", "Domain $domain (colo ${r.colo.ifBlank { "?" }}) is flaky")
            else -> pass("${r.pingMs} ms", "Domain $domain via ${r.colo.ifBlank { "?" }}")
        }
    }

    // ------------------------------------------------------------ core
    private suspend fun runCoreVersion() = timed(ID_CORE_VERSION) {
        val v = core.coreVersion()
        if (v != "unknown") pass(v) else warn("unknown", "Could not read the Xray core version")
    }

    // ------------------------------------------------------------ system honesty rows
    /**
     * Confirms the pinned/last-used server's protocol actually has a runnable engine on this
     * device — SSH needs a downloaded sing-box binary (Xray has no SSH outbound), Psiphon/DNS
     * tunnel need their dedicated addon pack. Never claims "Connected" is possible for these
     * without saying so here first; matches the same gate `V2RayVpnService` enforces at connect.
     */
    private suspend fun runProtocolPack(serverId: String?) = timed(ID_PROTOCOL_PACK) {
        val server = serverId?.let { servers.getServer(it) } ?: return@timed skip("no server selected")
        when (server.protocol) {
            Protocol.SSH -> {
                val version = settings.current().selectedCoreVersions[ProxyCoreType.SING_BOX] ?: CORE_VERSION_BUNDLED
                if (binaryManager.resolveBinary(ProxyCoreType.SING_BOX, version) != null) {
                    pass("sing-box ready", "SSH runs on sing-box")
                } else {
                    fail("sing-box missing", "SSH requires the sing-box core (Xray has no SSH outbound) — install it in Core manager")
                }
            }
            Protocol.WIREGUARD -> {
                val version = settings.current().selectedCoreVersions[ProxyCoreType.SING_BOX] ?: CORE_VERSION_BUNDLED
                if (binaryManager.resolveBinary(ProxyCoreType.SING_BOX, version) != null) {
                    pass("sing-box ready", "WireGuard runs on sing-box")
                } else {
                    warn("sing-box missing", "WireGuard falls back to the built-in Xray outbound — install sing-box in Core manager for the reference client")
                }
            }
            Protocol.PSIPHON -> {
                val src = addonPacks.packSource(PackAvailability.PSIPHON)
                if (src != PackSource.MISSING) pass(src.label(), "Psiphon pack: ${src.label()}")
                else fail("Psiphon pack missing", "Psiphon requires its addon pack — install it in Core manager")
            }
            Protocol.DNSTUNNEL -> {
                val src = addonPacks.packSource(PackAvailability.DNSTUNNEL)
                if (src != PackSource.MISSING) pass(src.label(), "DNS tunnel pack: ${src.label()}")
                else fail("DNS tunnel pack missing", "DNS tunnel requires its addon pack — install it in Core manager")
            }
            else -> skip("not pack-gated")
        }
    }

    /** Confirms the Tor daemon (and any pluggable-transport pack the configured transport needs)
     * actually has a runnable binary, instead of letting the UI claim "Tor enabled" while the
     * daemon has nothing to execute. */
    private suspend fun runTorPacks(torEnabled: Boolean, transport: TorTransport) = timed(ID_TOR_PACKS) {
        if (!torEnabled) return@timed skip("Tor disabled")
        val torSource = addonPacks.packSource(PackAvailability.TOR)
        if (torSource == PackSource.MISSING) {
            return@timed fail("tor missing", "Install the Tor pack in Core manager")
        }
        val ptPack = PackAvailability.packForTransport(transport)
        if (ptPack == null) return@timed pass(torSource.label(), "tor: ${torSource.label()}")
        val ptSource = addonPacks.packSource(ptPack)
        if (ptSource == PackSource.MISSING) {
            return@timed warn("${ptPack.label} missing", "tor: ${torSource.label()} · ${ptPack.label}: install in Core manager")
        }
        pass("${torSource.label()} · ${ptSource.label()}", "tor: ${torSource.label()} · ${ptPack.label}: ${ptSource.label()}")
    }

    /** Reports whether LAN/hotspot proxy sharing is actually on, instead of assuming the toggle
     * alone means clients can reach it. */
    private suspend fun runHotspot(allowLan: Boolean, enableLanSharing: Boolean, socksPort: Int, httpPort: Int) =
        timed(ID_HOTSPOT) {
            if (!allowLan && !enableLanSharing) return@timed skip("disabled")
            pass("0.0.0.0:$socksPort / :$httpPort", "LAN sharing is enabled — proxy bound on all interfaces")
        }

    /** Cross-checks the user's Always-on *request* against the OS-reported state
     * ([com.v2rayez.app.domain.model.ConnectionState.alwaysOn], only meaningful while connected)
     * so this never claims Always-on is active when Android hasn't actually granted it. */
    private suspend fun runAlwaysOn(connected: Boolean, requested: Boolean, osReportsAlwaysOn: Boolean) =
        timed(ID_ALWAYS_ON) {
            if (!requested) return@timed skip("not requested")
            if (!connected) return@timed skip("connect to verify")
            if (osReportsAlwaysOn) pass("granted", "Android reports this app as the Always-on VPN")
            else warn("not granted", "Requested in Settings, but Android does not report Always-on — grant it in system VPN settings")
        }

    private suspend fun runPendingAddons(pending: List<String>) = timed(ID_PENDING_ADDONS) {
        if (pending.isEmpty()) pass("none")
        else warn(pending.joinToString(", "), "Queued in Core manager — install to finish setup")
    }

    private fun PackSource.label(): String = when (this) {
        PackSource.DOWNLOADED -> "downloaded"
        PackSource.BUNDLED -> "bundled"
        PackSource.MISSING -> "missing"
    }

    // ------------------------------------------------------------ plumbing
    private data class Outcome(val status: CheckStatus, val result: String, val detail: String?)

    private fun pass(result: String, detail: String? = null) = Outcome(CheckStatus.PASS, result, detail)
    private fun warn(result: String, detail: String? = null) = Outcome(CheckStatus.WARN, result, detail)
    private fun fail(result: String, detail: String? = null) = Outcome(CheckStatus.FAIL, result, detail)
    private fun skip(result: String) = Outcome(CheckStatus.SKIPPED, result, null)

    private suspend fun timed(id: String, block: suspend () -> Outcome) {
        val start = System.nanoTime()
        val outcome = runCatching { block() }
            .getOrElse { Outcome(CheckStatus.FAIL, it.message ?: "failed", null) }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        updateCheck(id) { it.copy(status = outcome.status, result = outcome.result, detail = outcome.detail, durationMs = elapsed) }
    }

    private fun updateCheck(id: String, transform: (DiagnosticCheck) -> DiagnosticCheck) {
        _state.update { st ->
            st.copy(sections = st.sections.map { sec ->
                sec.copy(checks = sec.checks.map { if (it.id == id) transform(it) else it })
            })
        }
    }

    private suspend fun fetchText(client: OkHttpClient, url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.string()?.trim()?.takeIf { s -> s.isNotBlank() } }
        }.getOrNull()
    }

    private fun parseDnsEndpoint(raw: String): Pair<String, Int>? {
        val value = raw.trim().substringAfter("://", raw.trim()).substringBefore('/')
        val host = value.substringBeforeLast(':', value).ifBlank { return null }
        val port = value.substringAfterLast(':', "").toIntOrNull() ?: 53
        if (port !in 1..65535) return null
        return host to port
    }

    private suspend fun probeDnsUdp(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val query = byteArrayOf(
                0x56, 0x32, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x0a, 'c'.code.toByte(), 'l'.code.toByte(),
                'o'.code.toByte(), 'u'.code.toByte(), 'd'.code.toByte(), 'f'.code.toByte(),
                'l'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(),
                0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00,
                0x00, 0x01, 0x00, 0x01
            )
            DatagramSocket().use { socket ->
                socket.soTimeout = 3_000
                val address = InetAddress.getByName(host)
                socket.send(DatagramPacket(query, query.size, address, port))
                val response = ByteArray(512)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                packet.length >= 12 && response[0] == query[0] && response[1] == query[1]
            }
        }.getOrDefault(false)
    }

    private fun check(id: String) = DiagnosticCheck(id)

    companion object {
        const val SEC_CONNECTIVITY = "connectivity"
        const val SEC_TUNNEL = "tunnel"
        const val SEC_SERVER = "server"
        const val SEC_TOR = "tor"
        const val SEC_SNI = "sni"
        const val SEC_CORE = "core"
        const val SEC_SYSTEM = "system"

        const val ID_INTERNET = "internet"
        const val ID_DNS = "dns"
        const val ID_PUBLIC_IP = "public_ip"
        const val ID_TUNNEL_LATENCY = "tunnel_latency"
        const val ID_VPN_ACTIVE = "vpn_active"
        const val ID_CORE_RUNNING = "core_running"
        const val ID_LOCAL_DNS = "local_dns"
        const val ID_TUNNEL_GENERATE_204 = "tunnel_generate_204"
        const val ID_PER_APP_POLICY = "per_app_policy"
        const val ID_TUNNEL_IP = "tunnel_ip"
        const val ID_SESSION_TRAFFIC = "session_traffic"
        const val ID_SERVER_RTT = "server_rtt"
        const val ID_TOR_BOOTSTRAP = "tor_bootstrap"
        const val ID_TOR_SOCKS = "tor_socks"
        const val ID_TOR_DNS = "tor_dns"
        const val ID_TOR_EXIT_IP = "tor_exit_ip"
        const val ID_SNI_PROBE = "sni_probe"
        const val ID_CORE_VERSION = "core_version"
        const val ID_PROTOCOL_PACK = "protocol_pack"
        const val ID_TOR_PACKS = "tor_packs"
        const val ID_HOTSPOT = "hotspot"
        const val ID_ALWAYS_ON = "always_on"
        const val ID_PENDING_ADDONS = "pending_addons"
    }
}
