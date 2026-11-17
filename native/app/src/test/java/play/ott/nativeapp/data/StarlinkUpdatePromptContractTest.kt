package play.ott.nativeapp.data

import kotlin.test.*

/** Regression: the panel controls the update prompt; installation is never silent. */
class StarlinkUpdatePromptContractTest {
    private fun payload(force: String) =
        """{"code":200,"result":{"version":11,"package_name":"br.starlink.iptv.layout","url":"https://example.invalid/release.apk","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version_name":"0.3.3","text":"Melhorias do player","force_upgrade":$force}}"""

    @Test fun optionalUpdateRemainsDismissible() {
        val update = parseUpdate(payload("false"), "br.starlink.iptv.layout", 10)
        assertNotNull(update)
        assertFalse(update.mandatory)
        assertTrue(shouldShowUpdatePrompt(update.version, null, update.mandatory))
        assertFalse(shouldShowUpdatePrompt(update.version, 11, update.mandatory))
    }

    @Test fun requiredUpdateDoesNotDisappearWhenDismissed() {
        val update = parseUpdate(payload("1"), "br.starlink.iptv.layout", 10)
        assertNotNull(update)
        assertTrue(update.mandatory)
        assertTrue(shouldShowUpdatePrompt(update.version, 11, update.mandatory))
    }

    @Test fun booleanRequiredUpdateIsRecognized() {
        val update = parseUpdate(payload("true"), "br.starlink.iptv.layout", 10)
        assertNotNull(update)
        assertTrue(update.mandatory)
    }

    @Test fun officialCanRequireUpdateButQaRemainsOptional() {
        assertTrue(effectiveMandatoryUpdate(true, "br.starlink.iptv"))
        assertFalse(effectiveMandatoryUpdate(true, "br.starlink.iptv.layout"))
        assertFalse(effectiveMandatoryUpdate(true, "br.starlink.iptv.paravoce"))
        assertFalse(effectiveMandatoryUpdate(false, "br.starlink.iptv"))
    }

    @Test fun absentOrOlderReleaseNeverShowsPrompt() {
        assertFalse(shouldShowUpdatePrompt(null, null, false))
        assertNull(parseUpdate(payload("1"), "br.starlink.iptv.layout", 11))
    }

    @Test fun metadataRemainsBoundToTheInstalledPackage() {
        assertNull(parseUpdate(payload("1"), "br.starlink.iptv.paravoce", 10))
    }
}
