package com.v2rayez.app.data.repository

import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.LogTags
import com.v2rayez.app.domain.repository.LogRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Minimal tagged-logging wrappers (L1) so every subsystem — VPN, Tor, MITM, Core, Download —
 * funnels through the same [LogEntry] shape with a stable [LogTags] value instead of hand-rolling
 * timestamp/id plumbing at each call site. Callers that don't have a [LogRepository] handy (e.g.
 * plain-JVM-tested classes) take it as a nullable constructor param and no-op when absent.
 */
fun LogRepository.logTagged(tag: String, level: LogLevel, message: String, detail: String? = null) {
    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    append(
        LogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = ts,
            level = level,
            message = message,
            detail = detail,
            tag = tag
        )
    )
}

fun LogRepository.logVpn(level: LogLevel, message: String, detail: String? = null) =
    logTagged(LogTags.VPN, level, message, detail)

fun LogRepository.logTor(level: LogLevel, message: String, detail: String? = null) =
    logTagged(LogTags.TOR, level, message, detail)

fun LogRepository.logMitm(level: LogLevel, message: String, detail: String? = null) =
    logTagged(LogTags.MITM, level, message, detail)

fun LogRepository.logCore(level: LogLevel, message: String, detail: String? = null) =
    logTagged(LogTags.CORE, level, message, detail)

fun LogRepository.logDownload(level: LogLevel, message: String, detail: String? = null) =
    logTagged(LogTags.DOWNLOAD, level, message, detail)
