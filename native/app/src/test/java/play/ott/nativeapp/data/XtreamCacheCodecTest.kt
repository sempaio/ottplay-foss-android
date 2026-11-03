package play.ott.nativeapp.data

import org.junit.Assert.*
import org.junit.Test

class XtreamCacheCodecTest {
    @Test fun chunksRoundTripOverOneMegabyte() {
        val sample = ByteArray(1024 * 1024 + 19) { (it % 247).toByte() }
        val blocks = XtreamCacheCodec.chunks(sample)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.size <= 128 * 1024 })
        assertArrayEquals(sample, blocks.reduce { a, b -> a + b })
    }

    @Test fun oversizedPayloadNeverGetsTruncated() {
        try {
            XtreamCacheCodec.chunks(ByteArray(XtreamCacheCodec.MAX_PAYLOAD_BYTES + 1))
            fail("must reject oversized cache")
        } catch (_: IllegalArgumentException) { }
    }
}
