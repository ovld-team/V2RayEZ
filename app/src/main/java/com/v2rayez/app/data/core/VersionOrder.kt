package com.v2rayez.app.data.core

/**
 * Numeric-aware version ordering for release tags / installed-version directory names.
 * Plain [sortedDescending] is lexicographic and ranks "v0.4.9" above "v0.4.10", which made
 * "newest installed version" resolution pick the wrong binary.
 */
object VersionOrder {
    private val NUMBERS = Regex("""\d+""")

    /** Numeric segments of [v]: "v1.8.10-rc2" -> [1, 8, 10, 2]. Non-digit text is ignored. */
    private fun segments(v: String): List<Long> =
        NUMBERS.findAll(v).map { it.value.take(18).toLong() }.toList()

    val ascending: Comparator<String> = Comparator { a, b ->
        val sa = segments(a)
        val sb = segments(b)
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val c = sa.getOrElse(i) { 0L }.compareTo(sb.getOrElse(i) { 0L })
            if (c != 0) return@Comparator c
        }
        a.compareTo(b) // deterministic tiebreak for equal numeric content
    }

    val descending: Comparator<String> = ascending.reversed()
}
