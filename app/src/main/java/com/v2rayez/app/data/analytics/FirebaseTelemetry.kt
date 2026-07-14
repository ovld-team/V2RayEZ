package com.v2rayez.app.data.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.v2rayez.app.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Analytics (opt-in) + Crashlytics (always-on fatal backup behind Sentry).
 * Never logs hosts, URIs, bridges, subscription text, or other PII.
 *
 * All Firebase calls are soft-fail — a missing/broken google-services config must never
 * take down process start on older devices (API 26+).
 */
@Singleton
class FirebaseTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val analytics by lazy { runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull() }
    private val crashlytics by lazy { runCatching { FirebaseCrashlytics.getInstance() }.getOrNull() }

    /** Call once at process start so fatals are collected even before settings load. */
    fun enableCrashReporting() {
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.w(TAG, "FirebaseApp not initialized — Crashlytics stays off")
                return
            }
            crashlytics?.setCrashlyticsCollectionEnabled(true)
        }.onFailure { Log.w(TAG, "Crashlytics enable failed", it) }
    }

    fun applyConsent(settings: AppSettings) {
        runCatching {
            analytics?.setAnalyticsCollectionEnabled(settings.analyticsConsent)
            crashlytics?.setCrashlyticsCollectionEnabled(true)
        }.onFailure { Log.w(TAG, "Firebase consent apply failed", it) }
    }

    fun recordFatal(t: Throwable) {
        runCatching {
            crashlytics?.recordException(t)
            crashlytics?.sendUnsentReports()
        }
    }

    fun logScreen(name: String) {
        if (name.isBlank()) return
        runCatching {
            analytics?.logEvent(
                FirebaseAnalytics.Event.SCREEN_VIEW,
                Bundle().apply {
                    putString(FirebaseAnalytics.Param.SCREEN_NAME, name.take(36))
                }
            )
        }
    }

    fun logVpnState(connected: Boolean) {
        runCatching {
            analytics?.logEvent(
                "vpn_state",
                Bundle().apply { putString("state", if (connected) "connected" else "disconnected") }
            )
        }
    }

    fun logFeatureToggle(feature: String, enabled: Boolean) {
        val safe = feature.takeWhile { it.isLetterOrDigit() || it == '_' }.take(24)
        if (safe.isEmpty()) return
        runCatching {
            analytics?.logEvent(
                "feature_toggle",
                Bundle().apply {
                    putString("feature", safe)
                    putString("enabled", if (enabled) "1" else "0")
                }
            )
        }
    }

    fun recordNonFatal(tag: String, t: Throwable) {
        runCatching {
            crashlytics?.log(tag.take(64))
            crashlytics?.recordException(t)
        }
    }

    private companion object {
        const val TAG = "FirebaseTelemetry"
    }
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
