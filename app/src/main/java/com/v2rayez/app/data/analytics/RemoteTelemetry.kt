package com.v2rayez.app.data.analytics

import android.content.Context
import android.util.Log
import com.v2rayez.app.BuildConfig
import com.v2rayez.app.domain.model.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.SentryId
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

sealed class SentryBugReportStatus {
    data class Sent(val eventId: String) : SentryBugReportStatus()
    data object DsnMissing : SentryBugReportStatus()
    data class Failed(val reason: String) : SentryBugReportStatus()
}

/**
 * Primary error/crash telemetry via Sentry. Crashlytics remains a fatal-only backup
 * ([FirebaseTelemetry]). Reports VPN/Tor/MITM/download failures and sampled free-test timeouts.
 *
 * No consent gate. Strings are scrubbed by [PiiScrubber] (hosts, URIs, bridges, IPs, PEMs).
 * Session Replay and performance tracing are disabled. DSN comes from BuildConfig
 * (`sentry.dsn` in local.properties or `-Psentry.dsn`); blank DSN keeps this a no-op.
 *
 * Sentry Logs (`options.logs`) + Gradle Logcat instrumentation forward WARN+/errors; call sites
 * also emit [Sentry.logger] alongside captureMessage for searchable Logs UI entries.
 */
@Singleton
class RemoteTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var initialized = false
    @Volatile private var initFailure: String? = null

    /** Call once early in [com.v2rayez.app.V2RayApplication.onCreate] after Hilt inject. */
    fun init() {
        if (initialized) return
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isBlank()) {
            initFailure = DSN_MISSING
            Log.i(TAG, "No Sentry DSN configured — telemetry stays a no-op (set sentry.dsn in local.properties)")
            return
        }
        runCatching {
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                options.release = "com.v2rayez.app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"

                // Keep performance/replay off for the free-plan quota. Debug SDK diagnostics and
                // automatic sessions still provide an explicit local signal that the SDK is live.
                options.isDebug = BuildConfig.DEBUG
                options.setDiagnosticLevel(if (BuildConfig.DEBUG) SentryLevel.DEBUG else SentryLevel.ERROR)
                options.tracesSampleRate = 0.0
                options.profilesSampleRate = 0.0
                options.sessionReplay.sessionSampleRate = 0.0
                options.sessionReplay.onErrorSampleRate = 0.0
                options.isEnableAutoSessionTracking = true
                options.isEnableUncaughtExceptionHandler = true

                // Structured Logs + Logcat integration (Gradle plugin) → Sentry Logs UI.
                options.logs.isEnabled = true
                options.logs.setBeforeSend { logEvent ->
                    runCatching {
                        logEvent.body = PiiScrubber.scrubOrNull(logEvent.body) ?: logEvent.body
                    }
                    logEvent
                }

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
            initFailure = null
            Log.i(TAG, "Sentry initialized (PRIMARY telemetry, logs enabled)")
        }.onFailure {
            initFailure = PiiScrubber.scrub(it.message ?: it.javaClass.simpleName)
            Log.w(TAG, "Sentry init failed — continuing without it", it)
        }
    }

    /** Forward fatals from the process uncaught handler (after Sentry's own hook). */
    fun captureFatal(t: Throwable) {
        if (!initialized) return
        runCatching {
            Sentry.captureException(t)
            Sentry.logger().fatal(PiiScrubber.scrub(t.message ?: t.javaClass.simpleName))
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

    /** Non-PII breadcrumb (e.g. Tor bootstrap milestones). No-op when DSN blank. */
    fun addBreadcrumb(category: String, message: String, level: SentryLevel = SentryLevel.INFO) {
        if (!initialized) return
        runCatching {
            val scrubbed = PiiScrubber.scrub(message)
            val crumb = Breadcrumb().apply {
                this.category = category.take(64)
                this.message = scrubbed
                this.level = level
            }
            Sentry.addBreadcrumb(crumb)
            when (level) {
                SentryLevel.FATAL -> Sentry.logger().fatal("[%s] %s", category, scrubbed)
                SentryLevel.ERROR -> Sentry.logger().error("[%s] %s", category, scrubbed)
                else -> Unit
            }
        }
    }

    fun addLogBreadcrumb(category: String, level: LogLevel, message: String) {
        val sentryLevel = when (level) {
            LogLevel.ERROR -> SentryLevel.ERROR
            LogLevel.WARNING -> SentryLevel.WARNING
            LogLevel.DEBUG -> SentryLevel.DEBUG
            LogLevel.INFO -> SentryLevel.INFO
        }
        addBreadcrumb(category, message, sentryLevel)
    }

    /**
     * User-initiated bug report: scrubbed diagnostics+logs body as an attachment + INFO message.
     * No-op when DSN blank. Caller must scrub before calling (defense-in-depth also runs beforeSend).
     */
    fun captureBugReport(scrubbedBody: String): SentryBugReportStatus {
        if (!initialized) {
            return if (initFailure == DSN_MISSING || BuildConfig.SENTRY_DSN.isBlank()) {
                SentryBugReportStatus.DsnMissing
            } else {
                SentryBugReportStatus.Failed(initFailure ?: "Sentry SDK not initialized")
            }
        }
        if (!Sentry.isEnabled()) return SentryBugReportStatus.Failed("Sentry SDK is disabled")
        return runCatching {
            val body = PiiScrubber.scrub(scrubbedBody)
            val attachment = io.sentry.Attachment(
                body.toByteArray(Charsets.UTF_8),
                "bug-report.txt",
                "text/plain"
            )
            var eventId = SentryId.EMPTY_ID
            Sentry.withScope { scope ->
                scope.setTag("failure_category", "bug_report")
                scope.setLevel(SentryLevel.INFO)
                scope.addAttachment(attachment)
                eventId = Sentry.captureMessage("User bug report", SentryLevel.INFO)
            }
            if (eventId == SentryId.EMPTY_ID) {
                throw IllegalStateException("Sentry rejected the event")
            }
            Sentry.logger().info("User bug report (%d chars)", body.length)
            addBreadcrumb("bug_report", "User submitted bug report")
            // Sentry Java 8.x flush() is void (older SDKs returned Boolean). Fail closed when
            // scopes report unhealthy after the timed flush — same UX as flush==false.
            Sentry.flush(BUG_REPORT_FLUSH_TIMEOUT_MS)
            if (!Sentry.getCurrentScopes().isHealthy) {
                throw IllegalStateException("Sentry flush failed")
            }
            SentryBugReportStatus.Sent(eventId.toString())
        }.getOrElse {
            val reason = PiiScrubber.scrub(it.message ?: it.javaClass.simpleName)
            Log.w(TAG, "Sentry bug report failed", it)
            SentryBugReportStatus.Failed(reason)
        }
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
            Sentry.logger().info("Free-server test timed out")
        }
    }

    private fun capture(category: FailureCategory, reason: String, extraTags: Map<String, String?> = emptyMap()) {
        if (!initialized) return
        val scrubbed = PiiScrubber.scrub(reason)
        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("failure_category", category.tag)
                extraTags.forEach { (k, v) -> if (v != null) scope.setTag(k, v) }
                Sentry.captureMessage(scrubbed, SentryLevel.ERROR)
            }
            // Also land in Sentry Logs (searchable) — Logcat WARN+ is auto-forwarded separately.
            Sentry.logger().error("[%s] %s", category.tag, scrubbed)
            Log.e(TAG, "[${category.tag}] $scrubbed")
        }
    }

    private companion object {
        const val TAG = "RemoteTelemetry"
        const val DSN_MISSING = "dsn_missing"
        const val BUG_REPORT_FLUSH_TIMEOUT_MS = 5_000L

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
