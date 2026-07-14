package com.v2rayez.app.data.analytics

import android.content.Context
import android.util.Log
import com.v2rayez.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Where a wired failure path belongs, for Sentry issue grouping/search (`failure_category` tag).
 */
enum class FailureCategory(val tag: String) {
    VPN_CONNECT("vpn_connect"),
    TOR("tor"),
    MITM("mitm"),
    DOWNLOAD("download")
}

/**
 * Primary error/crash telemetry via Sentry. Crashlytics remains a fatal-only backup
 * ([FirebaseTelemetry]). Reports VPN/Tor/MITM/download failures and sampled free-test timeouts.
 *
 * No consent gate. Strings are scrubbed by [PiiScrubber] (hosts, URIs, bridges, IPs, PEMs).
 * Session Replay and performance tracing are disabled. DSN comes from BuildConfig
 * (`sentry.dsn` in local.properties or `-Psentry.dsn`); blank DSN keeps this a no-op.
 */
@Singleton
class RemoteTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var initialized = false

    /** Call once early in [com.v2rayez.app.V2RayApplication.onCreate] after Hilt inject. */
    fun init() {
        if (initialized) return
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isBlank()) {
            Log.i(TAG, "No Sentry DSN configured — telemetry stays a no-op (set sentry.dsn in local.properties)")
            return
        }
        runCatching {
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                options.release = "com.v2rayez.app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"

                options.tracesSampleRate = 0.0
                options.sessionReplay.sessionSampleRate = 0.0
                options.sessionReplay.onErrorSampleRate = 0.0
                options.isEnableAutoSessionTracking = true
                options.isEnableUncaughtExceptionHandler = true

                options.isSendDefaultPii = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.isEnableUserInteractionTracing = false

                options.setBeforeSend { event, _ ->
                    runCatching {
                        event.message?.message = PiiScrubber.scrubOrNull(event.message?.message)
                        event.exceptions?.forEach { exc -> exc.value = PiiScrubber.scrubOrNull(exc.value) }
                        event.request = null
                        event.serverName = null
                    }
                    event
                }
                options.setBeforeBreadcrumb { breadcrumb, _ ->
                    runCatching {
                        breadcrumb.message = PiiScrubber.scrubOrNull(breadcrumb.message)
                        val data = breadcrumb.data
                        data.keys.toList().forEach { k ->
                            (data[k] as? String)?.let { data[k] = PiiScrubber.scrub(it) }
                        }
                    }
                    breadcrumb
                }
            }
            initialized = true
            Log.i(TAG, "Sentry initialized (PRIMARY telemetry)")
        }.onFailure { Log.w(TAG, "Sentry init failed — continuing without it", it) }
    }

    /** Forward fatals from the process uncaught handler (after Sentry's own hook). */
    fun captureFatal(t: Throwable) {
        if (!initialized) return
        runCatching {
            Sentry.captureException(t)
            Sentry.flush(2_000)
        }
    }

    /** VPN connect / Tor bootstrap / MITM start failure — see [FailureCategory]. */
    fun captureVpnFailure(category: FailureCategory, reason: String) {
        capture(category, reason)
    }

    /** On-demand pack/asset download failure (addon binaries, geo databases, subscriptions). */
    fun captureDownloadFailure(subject: String, reason: String) {
        capture(FailureCategory.DOWNLOAD, reason, extraTags = mapOf("subject" to PiiScrubber.scrub(subject)))
    }

    /**
     * Free-server accurate test ("Timed out" from [com.v2rayez.app.domain.repository.VpnController.testLatency]).
     * Dead/slow public servers time out constantly and are expected noise, not a real product bug —
     * sample ~10% and tag `false_positive_candidate` so Sentry-side triage can filter/dedupe them
     * instead of them drowning out real signal on the free plan's event quota.
     */
    fun captureFreeTestTimeout(serverId: String) {
        if (Random.nextDouble() >= FALSE_POSITIVE_SAMPLE_RATE) return
        if (!initialized) return
        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("failure_category", "free_test_timeout")
                scope.setTag("false_positive_candidate", "true")
                scope.setExtra("server_ref", PiiScrubber.scrub(serverId))
                Sentry.captureMessage("Free-server test timed out", SentryLevel.INFO)
            }
        }
    }

    private fun capture(category: FailureCategory, reason: String, extraTags: Map<String, String?> = emptyMap()) {
        if (!initialized) return
        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("failure_category", category.tag)
                extraTags.forEach { (k, v) -> if (v != null) scope.setTag(k, v) }
                Sentry.captureMessage(PiiScrubber.scrub(reason), SentryLevel.ERROR)
            }
        }
    }

    private companion object {
        const val TAG = "RemoteTelemetry"

        /** ~10% sampling for flaky free-server timeout noise. */
        const val FALSE_POSITIVE_SAMPLE_RATE = 0.10
    }
}

/**
 * Strips anything that could identify a server, bridge, or user config before it reaches Sentry:
 * hosts/domains, full URIs (incl. proxy schemes like `vless://`/`trojan://`), IPv4/IPv6 literals,
 * PEM key/cert blocks, Tor bridge lines, and long base64 blobs (subscription/config bodies).
 *
 * Pure string → string; no Android/Sentry deps, so it's directly unit-testable.
 */
object PiiScrubber {

    fun scrub(input: String): String {
        var out = input
        out = PEM_BLOCK.replace(out, "[pem]")
        out = URI_SCHEME.replace(out, "[uri]")
        out = BRIDGE_LINE.replace(out, "[bridge]")
        out = HOSTNAME.replace(out, "[host]")
        out = IPV6_CANDIDATE.replace(out) { m -> if (looksLikeIpv6(m.value)) "[ip]" else m.value }
        out = IPV4.replace(out, "[ip]")
        out = HEX_FINGERPRINT.replace(out, "[fp]")
        out = BASE64_BLOB.replace(out, "[b64]")
        return out
    }

    fun scrubOrNull(input: String?): String? = if (input == null) null else scrub(input)

    // vless://, vmess://, trojan://, ss://, ssr://, obfs4://, http(s)://, ws(s)://, grpc://, tor://, …
    private val URI_SCHEME = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]{1,15}://\S+""")

    // Bare `host.example.com` or `host.example.com:443` with no scheme.
    private val HOSTNAME = Regex(
        """\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}(?::\d{1,5})?\b"""
    )

    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")

    // Candidate hex/colon runs; [looksLikeIpv6] disambiguates real IPv6 (incl. "::" compression)
    // from a plain HH:MM:SS timestamp ("14:32:10"), which must never be redacted.
    private val IPV6_CANDIDATE = Regex("""\b[0-9A-Fa-f:]{2,45}\b""")

    private fun looksLikeIpv6(token: String): Boolean {
        val colons = token.count { it == ':' }
        if (colons < 2) return false
        if ("::" in token) return true
        if (colons >= 3) return true
        return token.any { it in 'a'..'f' || it in 'A'..'F' }
    }

    private val PEM_BLOCK = Regex("""-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----""")

    // obfs4/snowflake/webtunnel/meek(_lite) bridge lines (torrc `Bridge …` format).
    private val BRIDGE_LINE = Regex(
        """\b(?:Bridge\s+)?(obfs4|snowflake|webtunnel|meek_lite|meek)\s+\S[^\n]*""",
        RegexOption.IGNORE_CASE
    )

    // Bridge fingerprints / cert hashes: bare 40-hex-char tokens.
    private val HEX_FINGERPRINT = Regex("""\b[A-Fa-f0-9]{40}\b""")

    // Subscription bodies / long encoded config blobs.
    private val BASE64_BLOB = Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b""")
}
