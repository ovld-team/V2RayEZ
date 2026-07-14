package com.v2rayez.app

import com.v2rayez.app.data.core.VersionOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionOrderTest {

    @Test
    fun numericSegmentsBeatLexicographicOrder() {
        val sorted = listOf("v0.4.9", "v0.4.10", "v0.4.2").sortedWith(VersionOrder.descending)
        assertEquals(listOf("v0.4.10", "v0.4.9", "v0.4.2"), sorted)
    }

    @Test
    fun prereleaseSuffixAndPrefixVariantsOrder() {
        val sorted = listOf("1.19.2", "v1.19.10", "v1.8.14-rc3").sortedWith(VersionOrder.descending)
        assertEquals(listOf("v1.19.10", "1.19.2", "v1.8.14-rc3"), sorted)
    }

    @Test
    fun shorterVersionTreatedAsZeroPadded() {
        val sorted = listOf("v1.9", "v1.9.1").sortedWith(VersionOrder.descending)
        assertEquals(listOf("v1.9.1", "v1.9"), sorted)
    }
}
