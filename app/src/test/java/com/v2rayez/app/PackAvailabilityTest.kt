package com.v2rayez.app

import com.v2rayez.app.data.core.AddonPackId
import com.v2rayez.app.data.core.PackAvailability
import com.v2rayez.app.domain.model.TorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PackAvailability] is the pure transport→pack map behind W3's "downloaded-first, bundled-fallback"
 * binary resolution. These tests pin the mapping and, crucially, guard that each pack's
 * [AddonPackId.binaryFileName] still lines up with the `lib<name>.so` the [TorTransport] expects —
 * so the same pack that gets downloaded is the one whose bundled jniLibs PIE would be resolved.
 */
class PackAvailabilityTest {

    @Test
    fun directAndVanillaNeedNoPack() {
        assertNull(PackAvailability.packForTransport(TorTransport.DIRECT))
        assertNull(PackAvailability.packForTransport(TorTransport.VANILLA))
    }

    @Test
    fun obfs4AndMeekBothMapToLyrebird() {
        assertEquals(AddonPackId.LYREBIRD, PackAvailability.packForTransport(TorTransport.OBFS4))
        assertEquals(AddonPackId.LYREBIRD, PackAvailability.packForTransport(TorTransport.MEEK))
    }

    @Test
    fun snowflakeAndWebtunnelMapToTheirOwnPacks() {
        assertEquals(AddonPackId.SNOWFLAKE, PackAvailability.packForTransport(TorTransport.SNOWFLAKE))
        assertEquals(AddonPackId.WEBTUNNEL, PackAvailability.packForTransport(TorTransport.WEBTUNNEL))
    }

    @Test
    fun torAndByedpiConstantsPointAtTheirPacks() {
        assertEquals(AddonPackId.TOR, PackAvailability.TOR)
        assertEquals(AddonPackId.BYEDPI, PackAvailability.BYEDPI)
    }

    @Test
    fun packBinaryNameMatchesTransportJniLibName() {
        // For every PT-backed transport, the pack's binaryFileName must equal the base of the
        // transport's jniLibs `.so` (lib<name>.so) so downloaded and bundled resolution agree.
        TorTransport.entries.forEach { transport ->
            val pack = PackAvailability.packForTransport(transport)
            if (transport.binary.isBlank()) {
                assertNull("$transport should need no pack", pack)
            } else {
                requireNotNull(pack)
                assertEquals(
                    "jniLibs name mismatch for $transport",
                    transport.binary,
                    "lib${pack.binaryFileName}.so"
                )
            }
        }
    }
}
