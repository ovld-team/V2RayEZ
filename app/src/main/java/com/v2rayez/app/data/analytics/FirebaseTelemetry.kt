package com.v2rayez.app.data.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase is the primary remote DevOps telemetry surface: Crashlytics, Performance,
 * and Analytics are always on. Never logs hosts, URIs, bridges, subscription text, or
 * other PII.
 *
 * All Firebase calls are soft-fail — a missing/broken google-services config must never
 * take down process start on older devices (API 26+).
 */
@Singleton
class FirebaseTelemetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localRing: LocalAnalyticsRing
) {
    private val analytics by lazy { runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull() }
    private val crashlytics by lazy { runCatching { FirebaseCrashlytics.getInstance() }.getOrNull() }
    private val performance by lazy { runCatching { FirebasePerformance.getInstance() }.getOrNull() }

    /** Call once at process start so telemetry is collected even before settings load. */
    fun enableCrashReporting() = enableTelemetry()

    fun enableTelemetry() {
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.w(TAG, "FirebaseApp not initialized — Firebase telemetry stays off")
                return
            }
            crashlytics?.setCrashlyticsCollectionEnabled(true)
            analytics?.setAnalyticsCollectionEnabled(true)
            performance?.setPerformanceCollectionEnabled(true)
        }.onFailure { Log.w(TAG, "Firebase telemetry enable failed", it) }
    }

    /** Policy is always-on. Settings are still decoded for compatibility but do not gate Firebase. */
    fun applyConsent(settings: AppSettings) {
        if (!settings.analyticsConsent) {
            localRing.record("analytics_consent_ignored_always_on")
        }
        enableTelemetry()
    }

    fun recordFatal(t: Throwable) {
        runCatching {
            val sanitized = sanitizedForCrashlytics(t)
            crashlytics?.log("fatal:${sanitized.message ?: sanitized.javaClass.simpleName}")
            crashlytics?.recordException(sanitized)
            crashlytics?.sendUnsentReports()
        }.onFailure { Log.w(TAG, "Firebase fatal record failed", it) }
    }

    fun logScreen(name: String) {
        val safe = safeName(name, MAX_SCREEN_NAME)
        if (safe.isBlank()) return
        logAnalytics(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, safe) }
        )
    }

    fun logVpnState(connected: Boolean) {
        logAnalytics(
            "vpn_state",
            Bundle().apply { putString("state", if (connected) "connected" else "disconnected") }
        )
    }

    fun logFeatureToggle(feature: String, enabled: Boolean) {
        val safe = safeName(feature, MAX_PARAM_VALUE)
        if (safe.isEmpty()) return
        logAnalytics(
            "feature_toggle",
            Bundle().apply {
                putString("feature", safe)
                putString("enabled", if (enabled) "1" else "0")
            }
        )
    }

    /** VPN connect / Tor bootstrap / MITM start failure — see [FailureCategory]. */
    fun captureVpnFailure(category: FailureCategory, reason: String) {
        captureFailure(category, reason)
    }

    /** On-demand pack/asset download failure (addon binaries, geo databases, subscriptions). */
    fun captureDownloadFailure(subject: String, reason: String) {
        captureFailure(
            FailureCategory.DOWNLOAD,
            reason,
            attrs = mapOf("subject" to safeName(subject, MAX_PARAM_VALUE))
        )
    }

    /** Non-PII breadcrumb (e.g. Tor bootstrap milestones). */
    fun addBreadcrumb(category: String, message: String, level: TelemetryLevel = TelemetryLevel.INFO) {
        val cat = safeName(category, MAX_CATEGORY)
        val scrubbed = PiiScrubber.scrub(message)
        runCatching {
            crashlytics?.log("[${level.name.lowercase()}][$cat] ${scrubbed.take(MAX_LOG_LINE)}")
            localRing.record("$cat:${scrubbed.take(MAX_LOG_LINE)}")
            if (level == TelemetryLevel.ERROR || level == TelemetryLevel.FATAL) {
                Log.e(TAG, "[$cat] $scrubbed")
            }
        }
    }

    fun addLogBreadcrumb(category: String, level: LogLevel, message: String) {
        addBreadcrumb(
            category,
            message,
            when (level) {
                LogLevel.ERROR -> TelemetryLevel.ERROR
                LogLevel.WARNING -> TelemetryLevel.WARNING
                LogLevel.DEBUG -> TelemetryLevel.DEBUG
                LogLevel.INFO -> TelemetryLevel.INFO
            }
        )
    }

    fun captureBugReport(scrubbedBody: String): BugReportStatus {
        return runCatching {
            val body = PiiScrubber.scrub(scrubbedBody)
            val ok = recordBugReport(body)
            if (ok) {
                recordTrace("bug_report_send", mapOf("result" to "sent")) {
                    putMetric("chars", body.length.toLong())
                }
                BugReportStatus.Sent
            } else {
                BugReportStatus.Failed("Firebase Crashlytics unavailable")
            }
        }.getOrElse {
            BugReportStatus.Failed(PiiScrubber.scrub(it.message ?: it.javaClass.simpleName))
        }
    }

    /**
     * Free-server accurate test timeouts are expected noise. Sample them so Firebase remains useful
     * for real product signals.
     */
    fun captureFreeTestTimeout(serverId: String) {
        if (Random.nextDouble() >= FALSE_POSITIVE_SAMPLE_RATE) return
        val serverRef = safeName(PiiScrubber.scrub(serverId), MAX_PARAM_VALUE)
        runCatching {
            crashlytics?.setCustomKey("false_positive_candidate", true)
            crashlytics?.setCustomKey("server_ref", serverRef)
            recordNonFatal("free_test_timeout", RuntimeException("Free-server test timed out"))
            logAnalytics(
                "free_test_timeout",
                Bundle().apply {
                    putString("false_positive_candidate", "1")
                    putString("server_ref", serverRef)
                }
            )
            recordTrace("free_latency_test", mapOf("result" to "timeout"))
        }
    }

    fun recordNonFatal(tag: String, t: Throwable) {
        val safeTag = safeName(tag, MAX_CATEGORY).ifBlank { "unknown" }
        runCatching {
            val sanitized = sanitizedForCrashlytics(t)
            crashlytics?.log("[$safeTag] ${sanitized.message ?: sanitized.javaClass.simpleName}")
            crashlytics?.setCustomKey("failure_category", safeTag)
            crashlytics?.recordException(sanitized)
            localRing.record("nonfatal:$safeTag")
            logAnalytics("${safeTag}_failure", Bundle().apply { putString("category", safeTag) })
        }
    }

    fun startTrace(name: String, attrs: Map<String, String?> = emptyMap()): TelemetryTrace {
        val safe = safeName(name, MAX_TRACE_NAME)
        if (safe.isBlank()) return TelemetryTrace(null)
        return runCatching {
            performance?.newTrace(safe)?.also { trace ->
                attrs.forEach { (key, value) ->
                    val safeKey = safeName(key, MAX_ATTRIBUTE_KEY)
                    val safeValue = value?.let { safeAttributeValue(it) }
                    if (safeKey.isNotBlank() && !safeValue.isNullOrBlank()) {
                        trace.putAttribute(safeKey, safeValue)
                    }
                }
                trace.start()
            }
        }.getOrNull().let(::TelemetryTrace)
    }

    fun recordTrace(
        name: String,
        attrs: Map<String, String?> = emptyMap(),
        configure: TelemetryTrace.() -> Unit = {}
    ) {
        val trace = startTrace(name, attrs)
        runCatching {
            trace.configure()
        }.onFailure {
            trace.putAttribute("trace_error", it.javaClass.simpleName)
        }.also {
            trace.stop()
        }
    }

    suspend fun <T> traceSuspend(
        name: String,
        attrs: Map<String, String?> = emptyMap(),
        block: suspend TelemetryTrace.() -> T
    ): T {
        val trace = startTrace(name, attrs)
        return try {
            trace.block()
        } catch (t: Throwable) {
            trace.putAttribute("result", "failed")
            trace.putAttribute("error_type", t.javaClass.simpleName)
            throw t
        } finally {
            trace.stop()
        }
    }

    /**
     * User bug report: breadcrumb-sized logs + one non-fatal marker. [scrubbedBody] must already
     * be PII-scrubbed (Crashlytics has no beforeSend hook).
     */
    fun recordBugReport(scrubbedBody: String): Boolean {
        return runCatching {
            val c = crashlytics ?: return false
            val body = PiiScrubber.scrub(scrubbedBody)
            c.log("bug_report_start")
            body.lineSequence().take(60).forEach { line ->
                c.log(line.take(MAX_LOG_LINE))
            }
            c.setCustomKey("bug_report", true)
            c.setCustomKey("bug_report_chars", body.length)
            c.recordException(RuntimeException("User bug report"))
            c.sendUnsentReports()
            logAnalytics("bug_report", Bundle().apply { putString("result", "sent") })
            true
        }.getOrDefault(false)
    }

    private fun captureFailure(
        category: FailureCategory,
        reason: String,
        attrs: Map<String, String?> = emptyMap()
    ) {
        val scrubbed = PiiScrubber.scrub(reason)
        val safeCategory = category.tag
        addBreadcrumb(safeCategory, scrubbed, TelemetryLevel.ERROR)
        runCatching {
            attrs.forEach { (key, value) ->
                val safeKey = safeName(key, MAX_ATTRIBUTE_KEY)
                val safeValue = value?.let { safeAttributeValue(it) }
                if (safeKey.isNotBlank() && !safeValue.isNullOrBlank()) {
                    crashlytics?.setCustomKey(safeKey, safeValue)
                }
            }
            recordNonFatal(safeCategory, IllegalStateException("[$safeCategory] $scrubbed"))
            logAnalytics(
                "${safeCategory}_failure",
                Bundle().apply {
                    putString("category", safeCategory)
                    attrs.forEach { (key, value) ->
                        val safeKey = safeName(key, MAX_ATTRIBUTE_KEY)
                        val safeValue = value?.let { safeAttributeValue(it) }
                        if (safeKey.isNotBlank() && !safeValue.isNullOrBlank()) putString(safeKey, safeValue)
                    }
                }
            )
            recordTrace(traceNameFor(category), attrs + ("result" to "failure"))
            Log.e(TAG, "[$safeCategory] $scrubbed")
        }
    }

    private fun logAnalytics(event: String, params: Bundle) {
        val safeEvent = safeName(event, MAX_EVENT_NAME)
        if (safeEvent.isBlank()) return
        runCatching {
            analytics?.logEvent(safeEvent, params)
            localRing.record("event:$safeEvent")
        }
    }

    private companion object {
        const val TAG = "FirebaseTelemetry"
        const val MAX_CATEGORY = 40
        const val MAX_EVENT_NAME = 40
        const val MAX_SCREEN_NAME = 36
        const val MAX_TRACE_NAME = 100
        const val MAX_ATTRIBUTE_KEY = 40
        const val MAX_PARAM_VALUE = 64
        const val MAX_ATTRIBUTE_VALUE = 100
        const val MAX_LOG_LINE = 100

        /** ~10% sampling for flaky free-server timeout noise. */
        const val FALSE_POSITIVE_SAMPLE_RATE = 0.10

        fun safeName(raw: String, max: Int): String =
            PiiScrubber.scrub(raw)
                .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
                .joinToString("")
                .trim('_')
                .take(max)

        fun safeAttributeValue(raw: String): String =
            PiiScrubber.scrub(raw)
                .replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
                .trim('_')
                .take(MAX_ATTRIBUTE_VALUE)

        fun traceNameFor(category: FailureCategory): String = when (category) {
            FailureCategory.VPN_CONNECT -> "vpn_connect"
            FailureCategory.TOR -> "tor_bootstrap"
            FailureCategory.MITM -> "mitm_start"
            FailureCategory.DOWNLOAD -> "download_failure"
        }
    }
}

enum class FailureCategory(val tag: String) {
    VPN_CONNECT("vpn_connect"),
    TOR("tor"),
    MITM("mitm"),
    DOWNLOAD("download")
}

enum class TelemetryLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL
}

sealed class BugReportStatus {
    data object Sent : BugReportStatus()
    data class Failed(val reason: String) : BugReportStatus()
}

class TelemetryTrace internal constructor(private val trace: Trace?) {
    fun putMetric(name: String, value: Long) {
        val safeName = name
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(40)
        if (safeName.isBlank()) return
        runCatching { trace?.putMetric(safeName, value) }
    }

    fun putAttribute(name: String, value: String) {
        val safeName = name
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(40)
        val safeValue = PiiScrubber.scrub(value)
            .replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
            .trim('_')
            .take(100)
        if (safeName.isBlank() || safeValue.isBlank()) return
        runCatching { trace?.putAttribute(safeName, safeValue) }
    }

    fun stop() {
        runCatching { trace?.stop() }
    }
}

private const val MAX_CAUSE_DEPTH = 5

/**
 * Crashlytics has no `beforeSend` hook, so scrub [Throwable.message] and its cause chain
 * at the call site before it ever reaches Crashlytics. Preserves the original exception type
 * and stack trace for crash grouping when a `(String)` constructor exists; falls back to a
 * generic [RuntimeException] otherwise. Returns [t] unchanged when nothing needed scrubbing.
 *
 * Cause chains deeper than [MAX_CAUSE_DEPTH] are truncated (dropped), never attached unscrubbed
 * — this level's own message is still scrubbed either way, so no raw PII survives at any depth.
 *
 * Pure (no Android/Firebase deps) so it's directly unit-testable, like [PiiScrubber] itself.
 */
internal fun sanitizedForCrashlytics(t: Throwable, depth: Int = 0): Throwable {
    val originalCause = t.cause?.takeIf { it !== t }
    val sanitizedCause = when {
        originalCause == null -> null
        depth >= MAX_CAUSE_DEPTH -> null // truncate rather than attach an unscrubbed deep cause
        else -> sanitizedForCrashlytics(originalCause, depth + 1)
    }
    val causeChanged = sanitizedCause !== originalCause
    val originalMessage = t.message
    val scrubbedMessage = PiiScrubber.scrubOrNull(originalMessage)
    if (scrubbedMessage == originalMessage && !causeChanged) return t
    val rebuilt = runCatching {
        t.javaClass.getConstructor(String::class.java).newInstance(scrubbedMessage) as Throwable
    }.getOrElse { RuntimeException(scrubbedMessage) }
    rebuilt.stackTrace = t.stackTrace
    if (sanitizedCause != null) runCatching { rebuilt.initCause(sanitizedCause) }
    return rebuilt
}

/** Local ring buffer for Device Lab / offline (no network). */
@Singleton
class LocalAnalyticsRing @Inject constructor() {
    private val lock = Any()
    private val ring = ArrayDeque<String>(64)

    fun record(event: String) {
        synchronized(lock) {
            if (ring.size >= 64) ring.removeFirst()
            ring.addLast(event.take(120))
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }
}
