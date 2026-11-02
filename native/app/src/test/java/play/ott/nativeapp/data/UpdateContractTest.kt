package play.ott.nativeapp.data

import kotlin.test.*

class UpdateContractTest {
    private fun document(version: Int = 9, pkg: String = "br.starlink.iptv.layout", url: String = "https://example.invalid/app.apk", hash: String = "a".repeat(64)) =
        """{"code":200,"result":{"version":$version,"package_name":"$pkg","url":"$url","sha256":"$hash","version_name":"0.3.1","force_upgrade":true}}"""
    @Test fun absentResultHidesBanner() { assertNull(parseUpdate("""{"code":200,"result":null}""", "br.starlink.iptv.layout", 8)) }
    @Test fun exactPackageAndNewerVersionRequired() {
        assertNull(parseUpdate(document(pkg = "br.starlink.iptv"), "br.starlink.iptv.layout", 8))
        assertNull(parseUpdate(document(version = 8), "br.starlink.iptv.layout", 8))
        assertEquals(9L, parseUpdate(document(), "br.starlink.iptv.layout", 8)?.version)
    }
    @Test fun insecureUrlAndMissingChecksumRejected() {
        assertFails { parseUpdate(document(url = "http://example.invalid/a.apk"), "br.starlink.iptv.layout", 8) }
        assertFails { parseUpdate(document(hash = ""), "br.starlink.iptv.layout", 8) }
        assertFails { parseUpdate(document(url = "https://user:password@example.invalid/a.apk"), "br.starlink.iptv.layout", 8) }
    }
    @Test fun failureAndMalformedResponsesRejected() {
        assertFails { parseUpdate("""{"code":500,"result":null}""", "br.starlink.iptv.layout", 8) }
        assertFails { parseUpdate("invalid", "br.starlink.iptv.layout", 8) }
    }
}
