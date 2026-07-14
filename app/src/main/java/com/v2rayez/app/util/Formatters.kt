package com.v2rayez.app.util

import java.util.Locale
import kotlin.math.abs

/** Byte / speed / duration formatting helpers shared across repositories and UI. */
object Formatters {

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024.0
        var i = 0
        while (size >= 1024.0 && i < units.size - 1) {
            size /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.2f %s", size, units[i])
    }

    /** Format bytes-per-second as a human-readable rate. */
    fun speed(bytesPerSec: Long): String = "${bytes(abs(bytesPerSec))}/s"

    /** Convert bytes-per-second to a Mbps label. */
    fun mbps(bytesPerSec: Long): String {
        val mbps = bytesPerSec * 8.0 / 1_000_000.0
        return String.format(Locale.US, "%.1f Mbps", mbps)
    }

    fun uptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
