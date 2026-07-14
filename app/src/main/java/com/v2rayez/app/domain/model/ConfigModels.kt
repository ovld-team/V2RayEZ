package com.v2rayez.app.domain.model

import kotlinx.serialization.Serializable

/** Which proxy engine runs the tunnel. */
@Serializable
enum class ProxyCoreType(val label: String, val binaryName: String) {
    XRAY("Xray", "libv2ray"),
    SING_BOX("sing-box", "libsingbox.so"),
    CLASH("Clash Meta", "libmihomo.so")
}

/**
 * Per-server core override. [SYSTEM] follows [AppSettings.defaultCore].
 * Other values pin an explicit [ProxyCoreType].
 */
@Serializable
enum class CorePreference(val label: String) {
    SYSTEM("App default"),
    XRAY("Xray"),
    SING_BOX("sing-box"),
    CLASH("Clash Meta");

    fun toCoreTypeOrNull(): ProxyCoreType? = when (this) {
        SYSTEM -> null
        XRAY -> ProxyCoreType.XRAY
        SING_BOX -> ProxyCoreType.SING_BOX
        CLASH -> ProxyCoreType.CLASH
    }

    companion object {
        fun fromCoreType(type: ProxyCoreType): CorePreference = when (type) {
            ProxyCoreType.XRAY -> XRAY
            ProxyCoreType.SING_BOX -> SING_BOX
            ProxyCoreType.CLASH -> CLASH
        }
    }
}

/** Sentinel version key meaning "use the APK-bundled binary / AAR". */
const val CORE_VERSION_BUNDLED = "bundled"

/** A remote subscription that yields a list of servers. */
@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val serverCount: Int = 0
)

/** How outbound traffic is routed. */
@Serializable
enum class RoutingMode(val label: String) {
    /** Everything through the proxy. */
    GLOBAL("Global"),
    /** Route by rules; unmatched goes through proxy. */
    RULE("Rule-based"),
    /** Everything direct (proxy only matched rules). */
    DIRECT("Direct")
}

/** Target outbound for a routing rule. */
@Serializable
enum class RuleOutbound(val tag: String, val label: String) {
    PROXY("proxy", "Proxy"),
    DIRECT("direct", "Direct"),
    BLOCK("block", "Block"),
    TOR("tor", "Tor")
}

/** A single routing rule. */
@Serializable
data class RoutingRule(
    val id: String,
    val enabled: Boolean = true,
    val remark: String = "",
    val outbound: RuleOutbound = RuleOutbound.DIRECT,
    /** Domain matchers: "geosite:cn", "domain:example.com", "keyword:ads". */
    val domains: List<String> = emptyList(),
    /** IP matchers: "geoip:cn", "geoip:private", "10.0.0.0/8". */
    val ips: List<String> = emptyList(),
    /** Port range e.g. "443" or "1000-2000". */
    val port: String = "",
    /** Protocol matchers e.g. "bittorrent". */
    val protocol: List<String> = emptyList()
)

/** A remote ruleset provider (GitHub / URL) that can be imported and auto-updated. */
@Serializable
data class RuleProvider(
    val id: String,
    val name: String,
    val url: String,
    /** Outbound applied to every domain/ip parsed from this provider. */
    val outbound: RuleOutbound = RuleOutbound.BLOCK,
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val entryCount: Int = 0,
    /** Auto-update interval in hours (0 = manual only). */
    val updateIntervalHours: Int = 24
)

/** Global routing configuration. */
@Serializable
data class RoutingConfig(
    val mode: RoutingMode = RoutingMode.RULE,
    val domainStrategy: String = "IPIfNonMatch",
    val bypassLan: Boolean = true,
    val bypassMainland: Boolean = false,
    /**
     * Route Iranian domains/IPs (`geosite:ir` / `geoip:ir`) directly instead of through the
     * proxy. Auto-enabled once when the device is detected in Iran and the full geo pack is
     * installed (see [com.v2rayez.app.data.core.IranRouting]); requires the full geo databases.
     */
    val bypassIran: Boolean = false,
    val blockAds: Boolean = false,
    val rules: List<RoutingRule> = emptyList(),
    val providers: List<RuleProvider> = emptyList()
)

/** Custom hosts file entry (domain -> ip / domain). */
@Serializable
data class HostMapping(
    val domain: String,
    val value: String
)

/** DNS configuration. */
@Serializable
data class DnsConfig(
    /** Prefer plain UDP DNS IPs so TUN/VPN DNS does not depend on a live DoH-through-proxy path. */
    val remoteDns: String = "1.1.1.1",
    val domesticDns: String = "223.5.5.5",
    val enableFakeDns: Boolean = false,
    val hosts: List<HostMapping> = emptyList()
)

/** Per-app proxy configuration. */
@Serializable
data class AppProxyConfig(
    val enabled: Boolean = false,
    /** true = bypass the selected apps; false = only proxy selected apps. */
    val bypassMode: Boolean = true,
    val packages: Set<String> = emptySet()
)

/** How addon/core pack downloads reach the network. */
@Serializable
enum class DownloadMode {
    /** Prefer direct; if blocked, retry through the active Xray/VPN path. */
    AUTO,
    /** Clearnet only (no tunnel). */
    DIRECT,
    /** Force download via the active proxy/VPN when available. */
    THROUGH
}

/**
 * Feature packs the user asked for in the welcome wizard.
 * Each flag maps via [com.v2rayez.app.data.core.OnboardingFeatureMapping] to real settings
 * and/or [AppSettings.pendingAddonInstall] pack ids — keep that map honest when adding fields.
 */
@Serializable
data class OnboardingWants(
    /** Queue Tor pack if missing; Tor stays off until the user enables it in Tools. */
    val tor: Boolean = false,
    /** Marks interest in MITM Domain Fronting tools (no download; CA install required later). */
    val mitm: Boolean = false,
    /**
     * Legacy decode field — Browser tab is always present; no longer offered in the wizard.
     * Ignored by [com.v2rayez.app.data.core.OnboardingFeatureMapping].
     */
    val browser: Boolean = false,
    /** Queue ByeDPI pack for DPI desync bypass. */
    val dpiBypass: Boolean = false,
    /** Enables LAN / hotspot SOCKS+HTTP sharing ([AppSettings.enableLanSharing]/ [AppSettings.allowLan]). */
    val hotspot: Boolean = false,
    /** Queue sing-box + Mihomo/Clash Meta core downloads. */
    val processCores: Boolean = false,
    /** Opt-in anonymous analytics ([AppSettings.analyticsConsent]). */
    val analytics: Boolean = false
)

/** DPI-bypass / SNI-tunnel fragmentation ("SNI Tunnel" tool). */
@Serializable
data class FragmentConfig(
    val enabled: Boolean = false,
    /** "tlshello" or e.g. "1-3". */
    val packets: String = "tlshello",
    /** Byte length range e.g. "100-200". */
    val length: String = "100-200",
    /** Delay range in ms e.g. "10-20". */
    val interval: String = "10-20"
)

/**
 * SNI DPI-bypass tuning profile, modeled on UAC-SNI-Spoofer's Advanced/Tuning modes.
 * Each mode maps to a preset of fragmentation + SNI-manipulation parameters.
 */
@Serializable
enum class SniTuningMode(val label: String) {
    /** Fastest, lightest bypass; may not defeat aggressive DPI. */
    FAST("Fast"),
    /** Default balance of speed and stability. */
    BALANCED("Balanced"),
    /** Strongest, stealthiest bypass; slower. */
    STEALTH("Stealth"),
    /** User controls every parameter manually. */
    CUSTOM("Custom")
}

/**
 * SNI manipulation + scanning configuration for the reworked SNI tab.
 * Supports spoofing (fake SNI), splitting (ClientHello fragmentation) and omitting the
 * SNI, plus an SNI scanner that finds the best-performing domain for the active server.
 */
@Serializable
data class SniConfig(
    val mode: SniTuningMode = SniTuningMode.BALANCED,
    /** Replace the TLS SNI with [spoofDomain] (implies allow-insecure). */
    val spoofEnabled: Boolean = false,
    val spoofDomain: String = "",
    /** Fragment the TLS ClientHello so DPI can't read the SNI (drives [FragmentConfig]). */
    val splitEnabled: Boolean = true,
    /** Send an empty SNI. */
    val omitEnabled: Boolean = false,
    /**
     * Domains the SNI scanner probes. Empty means "use the bundled default list"
     * (`assets/sni-spoof/domains.txt`, loaded by the ViewModel); a non-empty list is a
     * user override edited on the SNI screen.
     */
    val candidateDomains: List<String> = emptyList(),
    /** Best domain found by the last scan (auto-applied as the spoof SNI). */
    val bestSni: String = ""
) {
    companion object {
        /** Small offline fallback used only if the bundled domain asset can't be read. */
        val DEFAULT_CANDIDATES = listOf(
            "www.google.com", "www.cloudflare.com", "www.microsoft.com",
            "www.apple.com", "www.amazon.com", "www.bing.com",
            "www.wikipedia.org", "speedtest.net"
        )
    }
}

/**
 * TCP/TLS desync method applied by the native byedpi (`ciadpi`) local SOCKS5 engine.
 * Each mode maps to a real ciadpi CLI flag (see [DesyncConfig.toCiadpiArgs]).
 */
@Serializable
enum class DesyncMode(val label: String) {
    /** No desync (byedpi passes traffic through untouched). */
    NONE("Off"),
    /** Split the ClientHello at [DesyncConfig.splitPos]. */
    SPLIT("Split"),
    /** Split + reorder the segments so DPI reassembly fails. */
    DISORDER("Disorder"),
    /** Send a fake decoy packet with a low TTL before the real one. */
    FAKE("Fake"),
    /** Split using out-of-band (URG) data. */
    OOB("OOB"),
    /** Disorder using out-of-band (URG) data. */
    DISOOB("Disorder OOB"),
    /** Split the TLS record itself around the SNI. */
    TLSREC("TLS record")
}

/**
 * Native anti-DPI desync configuration driving the byedpi (`ciadpi`) local SOCKS5 proxy.
 * When [enabled] with a non-[DesyncMode.NONE] mode, the primary Xray proxy outbound dials
 * through the byedpi SOCKS outbound so the ClientHello is desynced on the wire.
 */
@Serializable
data class DesyncConfig(
    val enabled: Boolean = false,
    val mode: DesyncMode = DesyncMode.NONE,
    /** Byte position at which to split/fake (1-based, relative to the ClientHello). */
    val splitPos: Int = 1,
    /** Append the ciadpi `+s` SNI offset to the split position. */
    val useSniOffset: Boolean = true,
    /** TTL of the fake decoy packet used by [DesyncMode.FAKE]. */
    val fakeTtl: Int = 8,
    /** Prepend `--auto=torst` so byedpi only desyncs when the connection stalls/resets. */
    val autoTrigger: Boolean = false,
    val socksHost: String = "127.0.0.1",
    val socksPort: Int = 1081
) {
    /**
     * Maps this config to the ciadpi (byedpi) CLI arguments that select the desync method.
     * Returns an empty list for [DesyncMode.NONE].
     */
    fun toCiadpiArgs(): List<String> {
        if (mode == DesyncMode.NONE) return emptyList()
        // TLSREC always carries the +s SNI offset; the others honor useSniOffset.
        val pos = if (useSniOffset || mode == DesyncMode.TLSREC) "$splitPos+s" else "$splitPos"
        val args = mutableListOf<String>()
        if (autoTrigger) args += "--auto=torst"
        when (mode) {
            DesyncMode.SPLIT -> { args += "--split"; args += pos }
            DesyncMode.DISORDER -> { args += "--disorder"; args += pos }
            DesyncMode.OOB -> { args += "--oob"; args += pos }
            DesyncMode.DISOOB -> { args += "--disoob"; args += pos }
            DesyncMode.FAKE -> { args += "--fake"; args += pos; args += "--ttl"; args += fakeTtl.toString() }
            DesyncMode.TLSREC -> { args += "--tlsrec"; args += pos }
            DesyncMode.NONE -> {}
        }
        return args
    }

    companion object {
        /**
         * Preset desync config for an [SniTuningMode]. [SniTuningMode.CUSTOM] leaves the
         * user's [current] values untouched.
         */
        fun forTuningMode(mode: SniTuningMode, current: DesyncConfig = DesyncConfig()): DesyncConfig = when (mode) {
            SniTuningMode.FAST -> DesyncConfig(enabled = true, mode = DesyncMode.SPLIT, splitPos = 1, useSniOffset = true)
            SniTuningMode.BALANCED -> DesyncConfig(enabled = true, mode = DesyncMode.DISORDER, splitPos = 1, useSniOffset = true)
            SniTuningMode.STEALTH -> DesyncConfig(enabled = true, mode = DesyncMode.FAKE, splitPos = 1, useSniOffset = true, fakeTtl = 8, autoTrigger = true)
            SniTuningMode.CUSTOM -> current
        }
    }
}

/** Which Tor core powers the tunnel. */
@Serializable
enum class TorEngineType(val label: String) {
    /** Embedded classic C `tor` daemon (full pluggable-transport support). */
    NATIVE_C("Tor")
}

/**
 * Anti-censorship pluggable transport / bypass method used to reach the Tor network.
 *
 * [ptName] is the transport name the C `tor` daemon expects in `ClientTransportPlugin`
 * / `Bridge` lines. [binary] is the `jniLibs` `.so` that provides the transport's `exec`
 * process (empty for the transports the bundled `tor` handles natively). The transport is
 * only offered to the user when its [binary] is actually present on the device
 * (see `PluggableTransports.isAvailable`).
 */
@Serializable
enum class TorTransport(val label: String, val ptName: String, val binary: String) {
    /** Connect directly to guard relays (no bridge). */
    DIRECT("Direct", "", ""),
    /** Vanilla (unobfuscated) bridges — no PT process. */
    VANILLA("Vanilla bridges", "", ""),
    /** obfs4 obfuscation, provided by lyrebird. */
    OBFS4("obfs4", "obfs4", "liblyrebird.so"),
    /** meek (domain-fronted), provided by lyrebird. */
    MEEK("meek", "meek_lite", "liblyrebird.so"),
    /** Snowflake (WebRTC), provided by the snowflake client. */
    SNOWFLAKE("Snowflake", "snowflake", "libsnowflake.so"),
    /** WebTunnel (HTTPS camouflage), provided by the webtunnel client. */
    WEBTUNNEL("WebTunnel", "webtunnel", "libwebtunnel.so")
}

/**
 * Tor routing configuration. Supports an embedded native core (C tor / Arti) with any
 * pluggable transport, or delegation to an external Orbot SOCKS provider.
 */
@Serializable
data class TorConfig(
    val enabled: Boolean = false,
    val engine: TorEngineType = TorEngineType.NATIVE_C,
    val transport: TorTransport = TorTransport.DIRECT,
    /** Bridge lines (one per line) used for VANILLA/obfs4/webtunnel/etc. */
    val bridges: List<String> = emptyList(),
    /** Automatically fetch/rotate bridges from the Tor network when bootstrap fails. */
    val autoRotateBridges: Boolean = true,
    val socksHost: String = "127.0.0.1",
    val socksPort: Int = 9050,
    /**
     * Local Tor DNSPort (UDP). Used by full-device Tor sessions so app DNS does not rely on
     * UDP-over-SOCKS (unsupported). Default pairs with [socksPort] on loopback.
     */
    val dnsPort: Int = 9053,
    /**
     * When true with [enabled], start a Tor-only VpnService session that routes all apps
     * through the Tor SOCKS outbound (Xray catch-all). Requires VPN permission.
     */
    val routeAllDevice: Boolean = false
)

/** TLS certificate handling ("Certificates" tool). */
@Serializable
data class TlsCertConfig(
    val allowInsecure: Boolean = false,
    /** Extra pinned peer certificate SHA-256 fingerprints. */
    val pinnedSha256: List<String> = emptyList()
)

/**
 * UAC-style domain fronting: a local TCP dialer accepts Xray's outbound connection and
 * forwards it to a Cloudflare front IP with fake SNI probes + ClientHello fragmentation.
 * The real TLS SNI stays on the server URI / stream settings — it is NOT swapped here.
 */
@Serializable
data class DomainFrontConfig(
    val enabled: Boolean = false,
    /** Cloudflare (or other) front IP the dialer TCP-connects to. */
    val frontAddress: String = "104.19.229.21",
    /** Fallback front IP after consecutive dial failures. */
    val fallbackAddress: String = "104.19.230.21",
    val frontPort: Int = 443,
    /** Fake SNI used only for short-lived probe ClientHellos (not Xray's real SNI). */
    val fakeSni: String = "www.hcaptcha.com",
    /** Where Xray dials when fronting is enabled. */
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 40443,
    /** Strategy method tag: combined / raw / etc. */
    val method: String = "combined",
    /** Tuning preset: fast / balanced / stealth / custom. */
    val tuningMode: String = "balanced",
    val fakeProbeEnabled: Boolean = true,
    val fakeProbeCount: Int = 1,
    val fakeProbeDelayMs: Int = 50,
    val multiFragmentSize: Int = 96,
    val sniSplitDelayMs: Int = 45,
    val tlsRecordDelayMs: Int = 35,
    val multiDelayMs: Int = 3,
    val halfDelayMs: Int = 35,
    val routeProbeTimeoutMs: Int = 2800,
    val strategyCacheEnabled: Boolean = true,
    val strategyCacheTtlMs: Int = 10 * 60 * 1000,
    val logLevel: String = "normal",
    /**
     * Legacy field from the old SNI-swap fronting approach. Migrated into [fakeSni]
     * when non-blank and [fakeSni] is still default.
     */
    val frontDomain: String = ""
) {
    /** Effective fake SNI after applying the legacy [frontDomain] migration. */
    val effectiveFakeSni: String
        get() = fakeSni.ifBlank { frontDomain }.ifBlank { "www.hcaptcha.com" }
}

/**
 * MITM ("man-in-the-middle") domain fronting: a local HTTPS-intercepting proxy that terminates
 * TLS with the on-device [MitmCaGenerator][com.v2rayez.app.data.cert.MitmCaGenerator] CA, then
 * re-establishes the outbound TLS leg with a fronted SNI per [rulesText]. Unlike
 * [DomainFrontConfig] (TCP-level SNI/fragmentation fronting only), this decrypts and can rewrite
 * the Host header too — it requires the user to install the local CA (see `data/cert/`).
 */
@Serializable
data class MitmDomainFrontConfig(
    val enabled: Boolean = false,
    /** Local SOCKS port the intercepting proxy listens on (Browser / clients). */
    val proxyPort: Int = 10808,
    /** Local HTTP proxy port (WebView ProxyController / HTTP clients). */
    val httpPort: Int = 10809,
    /**
     * When true with [enabled], device-wide VpnService capture (`startMitmTunnel`) is used.
     * When false, standalone [MitmProxyService][com.v2rayez.app.data.service.MitmProxyService]
     * (SOCKS/HTTP only) is the Start path.
     */
    val captureAllApps: Boolean = false,
    /** One rule per line: `<domain-pattern> -> <front-domain>`; `#`-prefixed/blank lines ignored. */
    val rulesText: String = DEFAULT_RULES,
    /** Front SNI field retained for UI/legacy; unmatched hosts always exit direct (not MITM'd). */
    val defaultFront: String = "",
    /** DNS-over-HTTPS provider preset backing the intercept proxy's own resolution. */
    val dohPreset: String = "cloudflare",
    /** DoH endpoint IP (dialed directly so the DoH lookup itself isn't blocked by DPI). */
    val dohIp: String = "1.1.1.1",
    /** Fronted SNI used for the DoH TLS connection itself. */
    val dohFrontSni: String = "www.microsoft.com",
    /** Real Host/SNI of the DoH endpoint behind [dohFrontSni]. */
    val dohHost: String = "cloudflare-dns.com",
    /** True once the user has seen and acknowledged the CA-install / MITM-risk warning. */
    val caInstallAcknowledged: Boolean = false
) {
    companion object {
        /**
         * Built-in fronting map (google → www.google.com, x/twitter → creators.spotify.com,
         * meta → www.microsoft.com / githubassets, fastly/reddit/python → github.githubassets.com).
         * No comment lines — shown as-is in the rules editor.
         * Optional third `= host` token is ignored by [FrontRuleParser] (front SNI only).
         */
        const val DEFAULT_RULES = """google.com = www.google.com
x.com = creators.spotify.com
api.x.com = creators.spotify.com
youtube.com = www.google.com
youtu.be = www.google.com
youtube-nocookie.com = www.google.com
youtubekids.com = www.google.com
googlevideo.com = www.google.com
ytimg.com = www.google.com
ggpht.com = www.google.com
gstatic.com = www.google.com
googleusercontent.com = www.google.com
googleapis.com = www.google.com
withgoogle.com = www.google.com
google.dev = www.google.com
gvt1.com = www.google.com
android.com = www.google.com
dns.google = www.google.com
g.co = www.google.com
goo.gl = www.google.com
python.org = github.githubassets.com = github.githubassets.com
pypi.org = github.githubassets.com = github.githubassets.com
pythonhosted.org = github.githubassets.com = github.githubassets.com
fastly.com = github.githubassets.com = github.githubassets.com
fastly.net = github.githubassets.com = github.githubassets.com
developer.fastly.com = github.githubassets.com = github.githubassets.com
reddit.com = github.githubassets.com = github.githubassets.com
redd.it = github.githubassets.com = github.githubassets.com
redditstatic.com = github.githubassets.com = github.githubassets.com
redditmedia.com = github.githubassets.com = github.githubassets.com
githubassets.com = github.githubassets.com = github.githubassets.com
githubusercontent.com = github.githubassets.com = github.githubassets.com
cnn.com = github.githubassets.com = github.githubassets.com
buzzfeed.com = github.githubassets.com = github.githubassets.com
facebook.com = www.microsoft.com
fb.com = www.microsoft.com
fbcdn.net = www.microsoft.com
fbsbx.com = www.microsoft.com
instagram.com = github.githubassets.com
cdninstagram.com = www.microsoft.com
whatsapp.com = www.microsoft.com
whatsapp.net = www.microsoft.com
messenger.com = www.microsoft.com
meta.com = www.microsoft.com
oculus.com = www.microsoft.com
internet.org = www.microsoft.com
wit.ai = www.microsoft.com
akamai.net = www.microsoft.com
akamaiedge.net = www.microsoft.com
akamaihd.net = www.microsoft.com
akamaized.net = www.microsoft.com
edgesuite.net = www.microsoft.com
edgekey.net = www.microsoft.com
torproject.org = creators.spotify.com
bridges.torproject.org = github.githubassets.com = theatlantic.com
pbs.twimg.com = creators.spotify.com
twimg.com = creators.spotify.com
abs.twimg.com = creators.spotify.com
assets.msn.com = creators.spotify.com
steampowered.com = www.microsoft.com
steamstatic.com = www.microsoft.com
lencr.org = www.microsoft.com
steamcommunity.com = www.microsoft.com
dota2.com = www.microsoft.com
developer.android.com = google.com
"""
    }
}

/** How a configured Cloudflare WARP (WireGuard) endpoint is used in the tunnel. */
@Serializable
enum class WarpMode(val label: String) {
    /** All proxy traffic exits directly through WARP. */
    OUTBOUND("WARP as outbound"),
    /** The selected server dials through WARP (server -> WARP -> internet). */
    FRONT("Chain server through WARP")
}

/** Cloudflare WARP / WireGuard configuration (auto-registered or entered manually). */
@Serializable
data class WarpConfig(
    val enabled: Boolean = false,
    val mode: WarpMode = WarpMode.OUTBOUND,
    /** Client WireGuard private key (base64). */
    val privateKey: String = "",
    /** Cloudflare peer public key (base64). */
    val peerPublicKey: String = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
    /** Assigned interface addresses, e.g. "172.16.0.2/32", "2606:4700:...". */
    val addresses: List<String> = listOf("172.16.0.2/32"),
    /** 3-byte "reserved" client id from Cloudflare registration. */
    val reserved: List<Int> = emptyList(),
    val endpoint: String = "engage.cloudflareclient.com:2408",
    val mtu: Int = 1280,
    /** Cloudflare device/account id from registration (for reference). */
    val deviceId: String = ""
) {
    val configured: Boolean get() = privateKey.isNotBlank() && addresses.isNotEmpty()
}

/** Aggregate global app settings that drive config generation and the VPN tunnel. */
@Serializable
data class AppSettings(
    // Connection
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    /** Mobile-safe default; 1500 often blackholes TCP on cellular. */
    val mtu: Int = 1280,
    val enableIpv6: Boolean = false,
    val allowLan: Boolean = false,
    /** Expose the SOCKS/HTTP proxy on 0.0.0.0 so LAN/hotspot clients can use it. */
    val enableLanSharing: Boolean = false,
    /**
     * When true, port 53 is hijacked to Xray DNS (often DoH via proxy). Default false so a
     * weak outbound does not blackhole the whole phone's DNS.
     */
    val enableLocalDns: Boolean = false,
    val enableSniffing: Boolean = true,
    /** Multiplex several logical streams over one connection (Xray mux.cool). */
    val enableMux: Boolean = false,
    val muxConcurrency: Int = 8,
    // Behaviour
    /**
     * Home / in-app auto-reconnect (e.g. after disconnect). Independent of [bootAutoConnect].
     */
    val autoConnect: Boolean = false,
    /**
     * When true, [com.v2rayez.app.data.service.BootReceiver] reconnects [lastServerId] after
     * boot (subject to [android.net.VpnService.prepare] consent already having been granted).
     * Deliberately independent of [autoConnect] — that flag never promised a boot reconnect.
     */
    val bootAutoConnect: Boolean = false,
    /**
     * One-shot guard, flipped by [com.v2rayez.app.data.service.BootReceiver] on its first run
     * after this flag existed. Installs from before [bootAutoConnect] shipped relied on the
     * (now removed) `bootAutoConnect || autoConnect` fallback for boot reconnect; on the first
     * boot post-upgrade this migrates that implicit behavior into an explicit [bootAutoConnect]
     * `= true` exactly once, so those users don't silently lose boot-reconnect. Never touched
     * again afterwards — later toggles of either setting are fully independent from then on.
     */
    val legacyAutoConnectBootMigrated: Boolean = false,
    /** Strategy for downloading on-demand core/addon packs. */
    val downloadMode: DownloadMode = DownloadMode.AUTO,
    /** Pack ids queued for install after onboarding or Components UI (e.g. `tor`, `sing-box`). */
    val pendingAddonInstall: List<String> = emptyList(),
    /** Feature interests captured in the welcome wizard. */
    val onboardingWants: OnboardingWants = OnboardingWants(),
    /**
     * One-shot guard so Iran geo bypass is auto-enabled at most once; the user can still
     * toggle [RoutingConfig.bypassIran] freely afterwards without it being re-applied.
     */
    val iranBypassAutoApplied: Boolean = false,
    /** Opt-in Firebase Analytics. Crashlytics fatals are always-on (v0.9.71). */
    val analyticsConsent: Boolean = false,
    /** Retained for settings decode compatibility; Crashlytics fatals ignore this flag. */
    val crashlyticsConsent: Boolean = true,
    val vpnAlwaysOn: Boolean = true,
    val blockWithoutVpn: Boolean = false,
    /** Route 100% of device traffic through the tunnel, ignoring per-app proxy rules. */
    val fullDeviceTunnel: Boolean = false,
    val reduceData: Boolean = false,
    val batterySaver: Boolean = false,
    val notifications: Boolean = true,
    // Appearance
    val theme: String = "System",
    val language: String = "English (US)",
    val accentColor: String = "Purple",
    /** When true, the first-launch welcome wizard has been completed. */
    val onboardingComplete: Boolean = false,
    /** When true, the user accepted the in-app terms during onboarding. */
    val termsAccepted: Boolean = false,
    /** When true, the first-launch "join our channels" promo is never shown again. */
    val promoDismissed: Boolean = false,
    // Feature configs
    val routing: RoutingConfig = RoutingConfig(),
    val dns: DnsConfig = DnsConfig(),
    val appProxy: AppProxyConfig = AppProxyConfig(),
    val fragment: FragmentConfig = FragmentConfig(),
    val desync: DesyncConfig = DesyncConfig(),
    val sni: SniConfig = SniConfig(),
    val tor: TorConfig = TorConfig(),
    val tls: TlsCertConfig = TlsCertConfig(),
    val domainFront: DomainFrontConfig = DomainFrontConfig(),
    val mitm: MitmDomainFrontConfig = MitmDomainFrontConfig(),
    val warp: WarpConfig = WarpConfig(),
    /** Global default proxy core when a server uses [CorePreference.SYSTEM]. */
    val defaultCore: ProxyCoreType = ProxyCoreType.XRAY,
    /**
     * Selected installed version per core (`bundled` or a downloaded tag like `v1.13.14`).
     * Missing keys mean bundled.
     */
    val selectedCoreVersions: Map<ProxyCoreType, String> = emptyMap(),
    /** id of the last connected server for quick reconnect. */
    val lastServerId: String? = null,
    /** User-pinned default server the Home power button always connects to (falls back to lastServerId). */
    val defaultServerId: String? = null
)

/**
 * Runtime-only DNS hardening for every Tor exit, including Tor used with a selected server.
 *
 * Tor SOCKS cannot carry ordinary UDP DNS. Intercept port 53 in Xray and send resolver queries
 * to the embedded Tor DNSPort. FakeDNS is deliberately disabled: putting it first in Xray's DNS
 * server list answers from the synthetic pool without ever consulting Tor DNSPort, and older
 * AndroidLibXrayLite TUN builds can then lose the fake-IP/domain mapping and blackhole HTTPS.
 */
fun AppSettings.torEffectiveSettings(): AppSettings {
    if (!tor.enabled) return this
    val effectiveDnsPort = tor.dnsPort.takeIf { it in 1..65535 } ?: 9053
    return copy(
        enableLocalDns = true,
        enableSniffing = true,
        dns = dns.copy(
            remoteDns = "127.0.0.1:$effectiveDnsPort",
            enableFakeDns = false
        ),
        tor = tor.copy(dnsPort = effectiveDnsPort)
    )
}

/**
 * Runtime-only TUN DNS hardening for ordinary (non-Tor) Xray sessions.
 *
 * Device apps resolve through VpnService DNS peers. With LocalDNS/FakeDNS both off (defaults),
 * UDP/53 is marked `direct` while TCP goes `proxy` — that mismatch blackholes many apps on
 * older Android stacks even when socks/outbound probes still succeed (Connected, no tunnel).
 *
 * Always force LocalDNS + sniffing for full-device Xray tunnels. FakeDNS stays user-controlled
 * except when Tor already forced it off via [torEffectiveSettings].
 */
fun AppSettings.tunDnsEffectiveSettings(): AppSettings {
    if (tor.enabled) return torEffectiveSettings()
    if (enableLocalDns && enableSniffing) return this
    return copy(enableLocalDns = true, enableSniffing = true)
}

/** Portable, intentionally unencrypted backup payload. UI must warn before export or restore. */
@Serializable
data class BackupData(
    val version: Int = 2,
    val settings: AppSettings,
    /** Legacy v1 list and v2 manual/unowned servers. */
    val servers: List<String> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** Subscription id to share URIs, preserving ownership across a portable restore. */
    val subscriptionServers: Map<String, List<String>> = emptyMap()
) {
    /** True when the payload contains credentials or private subscription endpoints. */
    fun containsSensitiveCredentials(): Boolean =
        servers.isNotEmpty() ||
            subscriptionServers.values.any { it.isNotEmpty() } ||
            subscriptions.any { it.url.isNotBlank() } ||
            settings.warp.privateKey.isNotBlank() ||
            settings.tor.bridges.any { it.isNotBlank() }
}

/** Consistent Room snapshot used to build a portable backup. */
data class BackupSnapshot(
    val servers: List<Server>,
    val subscriptions: List<Subscription>
)

/** Result of a latency / connectivity test. */
data class TestResult(
    val serverId: String,
    val pingMs: Int,
    val success: Boolean,
    val message: String = ""
)

/** Outcome of importing a server / subscription. */
data class ImportResult(
    val success: Boolean,
    val importedCount: Int = 0,
    val message: String = ""
)
