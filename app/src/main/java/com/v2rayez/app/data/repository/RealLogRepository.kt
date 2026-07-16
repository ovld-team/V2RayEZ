package com.v2rayez.app.data.repository

import android.content.Context
import com.v2rayez.app.data.analytics.PiiScrubber
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.repository.LogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealLogRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LogRepository {

    private companion object {
        const val MAX = 800
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())

    override fun logs(): Flow<List<LogEntry>> = _logs.asStateFlow()

    override fun append(entry: LogEntry) {
        _logs.update { (it + entry).takeLast(MAX) }
    }

    override fun clear() {
        _logs.value = emptyList()
    }

    /**
     * Exported files leave the device via [android.content.Intent.ACTION_SEND] /
     * [androidx.core.content.FileProvider] share, unlike the in-app Logs screen (on-device
     * only) — scrub hosts/URIs/bridges/IPs/keys with [PiiScrubber] here so a shared log can't
     * carry server identity or secrets off the device.
     */
    override suspend fun exportToFile(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "v2rayez_log_$stamp.txt")
            file.bufferedWriter().use { w ->
                _logs.value.forEach { e ->
                    val message = PiiScrubber.scrub(e.message)
                    val detail = PiiScrubber.scrubOrNull(e.detail)
                    w.appendLine("${e.timestamp} [${e.level.label}] $message${detail?.let { " — $it" } ?: ""}")
                }
            }
            file
        }.getOrNull()
    }
}
