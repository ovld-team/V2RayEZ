package com.v2rayez.app

import com.v2rayez.app.data.mitm.FrontRule
import com.v2rayez.app.data.mitm.FrontRuleParser
import com.v2rayez.app.domain.model.MitmDomainFrontConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontRuleParserTest {

    @Test
    fun parsesEqualsStyle() {
        val rules = FrontRuleParser.parse("google.com = www.google.com")
        assertEquals(listOf(FrontRule("google.com", "www.google.com")), rules)
    }

    @Test
    fun parsesArrowStyle() {
        val rules = FrontRuleParser.parse("x.com -> creators.spotify.com")
        assertEquals(listOf(FrontRule("x.com", "creators.spotify.com")), rules)
    }

    @Test
    fun ignoresBlankAndCommentLines() {
        val text = """
            # a comment
            google.com = www.google.com

            # another
        """.trimIndent()
        val rules = FrontRuleParser.parse(text)
        assertEquals(1, rules.size)
        assertEquals("google.com", rules[0].domainPattern)
    }

    @Test
    fun supportsWildcardPatterns() {
        val rules = FrontRuleParser.parse("*.example.com = www.microsoft.com")
        assertEquals(listOf(FrontRule("*.example.com", "www.microsoft.com")), rules)
    }

    @Test
    fun trimsWhitespaceAroundSeparator() {
        val rules = FrontRuleParser.parse("   youtube.com   ->    www.google.com   ")
        assertEquals(listOf(FrontRule("youtube.com", "www.google.com")), rules)
    }

    @Test
    fun skipsInvalidLinesAndReportsThem() {
        val invalid = ArrayList<String>()
        val text = """
            google.com = www.google.com
            this-line-has-no-separator
            = www.google.com
            bad domain = www.google.com
            *.wild = *.notallowed.com
        """.trimIndent()
        val rules = FrontRuleParser.parse(text) { invalid.add(it.trim()) }
        assertEquals(1, rules.size)
        assertEquals("google.com", rules[0].domainPattern)
        assertEquals(4, invalid.size)
    }

    @Test
    fun defaultRulesParseCurrentMap() {
        val rules = FrontRuleParser.parse(MitmDomainFrontConfig.DEFAULT_RULES)
        assertTrue(rules.none { it.domainPattern.startsWith("#") })
        assertTrue(rules.any { it.domainPattern == "google.com" && it.frontSni == "www.google.com" })
        assertTrue(rules.any { it.domainPattern == "x.com" && it.frontSni == "creators.spotify.com" })
        assertTrue(rules.any { it.domainPattern == "youtube.com" && it.frontSni == "www.google.com" })
        assertTrue(rules.any { it.domainPattern == "youtube-nocookie.com" && it.frontSni == "www.google.com" })
        assertTrue(rules.any { it.domainPattern == "youtubekids.com" && it.frontSni == "www.google.com" })
        assertTrue(rules.any { it.domainPattern == "instagram.com" && it.frontSni == "github.githubassets.com" })
        assertTrue(rules.any { it.domainPattern == "reddit.com" && it.frontSni == "github.githubassets.com" })
        assertTrue(rules.any { it.domainPattern == "facebook.com" && it.frontSni == "www.microsoft.com" })
        assertTrue(rules.any { it.domainPattern == "bridges.torproject.org" && it.frontSni == "github.githubassets.com" })
        assertTrue(rules.any { it.domainPattern == "developer.android.com" && it.frontSni == "google.com" })
        assertTrue(rules.isNotEmpty())
        // Defaults ship without comment lines.
        assertTrue(!MitmDomainFrontConfig.DEFAULT_RULES.lineSequence().any { it.trimStart().startsWith("#") })
    }

    @Test
    fun capturesOptionalThirdDialHostColumn() {
        val rules = FrontRuleParser.parse(
            "python.org = github.githubassets.com = github.githubassets.com"
        )
        assertEquals(
            listOf(FrontRule("python.org", "github.githubassets.com", "github.githubassets.com")),
            rules
        )
    }

    @Test
    fun capturesDistinctDialHost() {
        val rules = FrontRuleParser.parse(
            "bridges.torproject.org = github.githubassets.com = theatlantic.com"
        )
        assertEquals(
            listOf(FrontRule("bridges.torproject.org", "github.githubassets.com", "theatlantic.com")),
            rules
        )
    }

    @Test
    fun googleFamilyRulesHaveNoDialHost() {
        // Google/YouTube use pure SNI swap (no dial redirect) — dialHost must stay empty so the
        // media path is unaffected by the parity column.
        val rules = FrontRuleParser.parse(MitmDomainFrontConfig.DEFAULT_RULES)
        val youtube = rules.first { it.domainPattern == "youtube.com" }
        assertEquals("www.google.com", youtube.frontSni)
        assertEquals("", youtube.dialHost)
        val googlevideo = rules.first { it.domainPattern == "googlevideo.com" }
        assertEquals("www.google.com", googlevideo.frontSni)
        assertEquals("", googlevideo.dialHost)
    }
}