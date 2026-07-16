package com.v2rayez.app.data.analytics

import android.content.Context
import android.os.Build
import com.v2rayez.app.BuildConfig
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.ConnectionState
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed class BugReportResult {
    data class Completed(
        val firebase: BugReportStatus
    ) : BugReportResult()
    data class Failed(val reason: String) : BugReportResult()
}

/** Collect + scrub + send a user bug report. */
interface BugReporter {
    suspend fun send(userNote: String? = null): BugReportResult
}

/**
 * Collects recent in-app logs + a short diagnostics snapshot, scrubs via [PiiScrubber], and
 * sends a non-fatal report to Firebase Crashlytics.
 */
@Singleton
class BugReportSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logs: LogRepository,
    private val settings: SettingsRepository,
    private val vpn: VpnController,
    private val firebaseTelemetry: FirebaseTelemetry
) : BugReporter {
    override suspend fun send(userNote: String?): BugReportResult {
        return runCatching {
            val entries = logs.logs().first().takeLast(120)
            val snap = buildSnapshot(settings.current(), vpn.connectionState.value, entries, userNote)
            val scrubbed = PiiScrubber.scrub(snap)
            val firebase = firebaseTelemetry.captureBugReport(scrubbed)
            context.packageName // keep ApplicationContext used (lint-safe)
            BugReportResult.Completed(firebase)
        }.getOrElse {
            BugReportResult.Failed(it.message ?: "unknown")
        }
    }

    private fun buildSnapshot(
        cfg: AppSettings,
        conn: ConnectionState,
        entries: List<LogEntry>,
        userNote: String?
    ): String = buildString {
        appendLine("V2RayEz bug report")
        appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("vpn=${conn.status.name} alwaysOn=${conn.alwaysOn}")
        appendLine("core=${cfg.defaultCore.name}")
        appendLine("tor=${cfg.tor.enabled} mitm=${cfg.mitm.enabled} capture=${cfg.mitm.captureAllApps}")
        appendLine("routing=${cfg.routing.mode.name} localDns=${cfg.enableLocalDns}")
        if (!userNote.isNullOrBlank()) appendLine("note=${userNote.take(240)}")
        appendLine("--- logs (${entries.size}) ---")
        entries.forEach { e ->
            append(e.timestamp).append(' ')
            append(e.level.label).append(' ')
            if (!e.tag.isNullOrBlank()) append('[').append(e.tag).append("] ")
            append(e.message)
            if (!e.detail.isNullOrBlank()) append(" | ").append(e.detail)
            appendLine()
        }
    }
}

/** Preview / no-arg ViewModel stub. */
class MockBugReporter : BugReporter {
    override suspend fun send(userNote: String?): BugReportResult =
        BugReportResult.Completed(BugReportStatus.Sent)
}

