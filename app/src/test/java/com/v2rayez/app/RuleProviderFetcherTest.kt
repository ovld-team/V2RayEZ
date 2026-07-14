package com.v2rayez.app

import com.v2rayez.app.data.net.UrlSafety
import com.v2rayez.app.data.routing.RuleProviderFetcher
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * [RuleProviderFetcher] shares [UrlSafety] with the subscription fetch path — these tests only
 * cover the SSRF gate at this call site; classification/parse coverage is exercised indirectly
 * through the existing routing tests.
 */
class RuleProviderFetcherTest {

    @Test
    fun fetchRejectsLoopbackProviderUrl() {
        assertThrows(IOException::class.java) {
            RuleProviderFetcher.fetch("http://127.0.0.1:9/rules.txt")
        }
    }

    @Test
    fun fetchRejectsPrivateLanProviderUrl() {
        assertThrows(IOException::class.java) {
            RuleProviderFetcher.fetch("http://192.168.0.1/rules.txt")
        }
    }

    @Test
    fun fetchRejectsCloudMetadataProviderUrl() {
        assertThrows(IOException::class.java) {
            RuleProviderFetcher.fetch("http://169.254.169.254/latest/meta-data/")
        }
    }

    @Test
    fun fetchRejectsNonHttpScheme() {
        assertThrows(IOException::class.java) {
            RuleProviderFetcher.fetch("file:///etc/hosts")
        }
    }
}
