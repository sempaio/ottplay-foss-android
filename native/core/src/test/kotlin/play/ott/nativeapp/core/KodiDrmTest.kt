package play.ott.nativeapp.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class KodiDrmTest {
    private val source = SourceConfig("drm-source", "Playlist", SourceKind.M3U, "https://catalog.test/list.m3u",
        headers = mapOf("X-Catalog-Token" to "catalog-secret"))

    @Test fun `Kodi raw license POST preserves isolated stream and license credentials and resets per entry`() {
        val catalog = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1,Protected
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://license.test/get?token=license-secret|Authorization=Bearer+lic&content-type=application%2Foctet-stream|R{SSM}|R
            #KODIPROP:inputstream.adaptive.stream_headers=Authorization=Bearer+stream
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            https://stream.test/play?id=1
            #EXTINF:-1,Clear
            https://stream.test/two.mp4
        """.trimIndent(), source)
        val protected = catalog.entries[0]
        assertEquals(DrmScheme.WIDEVINE, protected.drm?.scheme)
        assertEquals("Bearer lic", protected.drm?.licenseHeaders?.get("Authorization"))
        assertEquals("application/octet-stream", protected.drm?.licenseHeaders?.get("Content-Type"))
        assertEquals("Bearer stream", protected.headers["Authorization"])
        assertFalse(protected.drm!!.licenseHeaders.containsKey("X-Catalog-Token"))
        assertEquals("application/dash+xml", protected.mimeType)
        assertNull(protected.playbackUnsupportedReason)
        assertNull(catalog.entries[1].drm)
        assertNull(catalog.entries[1].mimeType)
        assertNull(catalog.entries[1].headers["Authorization"])
        assertTrue(catalog.notes.isEmpty())
    }

    @Test fun `documented modern simple and JSON DRM contracts survive encrypted catalog serialization`() {
        for ((name, scheme) in listOf("com.widevine.alpha" to DrmScheme.WIDEVINE,
            "com.microsoft.playready" to DrmScheme.PLAYREADY, "org.w3.clearkey" to DrmScheme.CLEARKEY)) {
            val simple = parse("inputstream.adaptive.drm_legacy=$name|https://license.test/server|X-License=secret").entries.single()
            assertEquals(scheme, simple.drm?.scheme)
            assertTrue(simple.drm!!.multiSession)
            val json = parse("""inputstream.adaptive.drm={"$name":{"license":{"server_url":"https://license.test/server","req_headers":"X-License=secret"},"force_single_session":true}}""").entries.single()
            assertEquals(scheme, json.drm?.scheme)
            assertFalse(json.drm!!.multiSession)
            assertEquals(json, Json.decodeFromString<MediaEntry>(Json.encodeToString(json)))
            assertFalse(json.drm.toString().contains("secret"))
            assertFalse(json.drm.toString().contains("license.test"))
        }
    }

    @Test fun `unsupported DRM stays visibly blocked and never imports raw key or transform secrets`() {
        val unsupported = listOf(
            "inputstream.adaptive.license_type=com.unknown.drm\n#KODIPROP:inputstream.adaptive.license_key=https://license.test/secret",
            "inputstream.adaptive.license_type=com.widevine.alpha\n#KODIPROP:inputstream.adaptive.license_key=https://license.test/secret||B{SSM}|JBlicense",
            "inputstream.adaptive.license_type=com.widevine.alpha\n#KODIPROP:inputstream.adaptive.license_key=https://license.test/secret|X-Key=secret||",
            "inputstream.adaptive.drm_legacy=org.w3.clearkey|00112233445566778899aabbccddeeff:00112233445566778899aabbccddeeff",
            "inputstream.adaptive.drm_legacy=org.w3.clearkey|data:application/json;base64,c2VjcmV0",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/{CHA-B64U}",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/%7BCHA-B64U%7D",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://user:secret@license.test/",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/|Authorization=secret%0D%0AX-Injected%3Avalue",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/|Host=secret",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/|X-Key=secret&x-key=other",
            "inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/|X-Key=%invalid-secret",
            "inputstream.adaptive.license_type=com.widevine.alpha\n#KODIPROP:inputstream.adaptive.server_certificate=secret",
            "inputstream.adaptive.drm={\"com.widevine.alpha\":{\"license\":{\"server_url\":\"https://license.test/\",\"req_data\":\"secret\"}}}",
            "inputstream.adaptive.drm={\"com.widevine.alpha\":{\"license\":{\"server_url\":\"https://license.test/\",\"unwrapper\":\"secret\"}}}",
            "inputstream.adaptive.drm={secret",
        )
        unsupported.forEach { property ->
            val catalog = parse(property)
            val entry = catalog.entries.single()
            assertNull(entry.drm)
            assertNotNull(entry.playbackUnsupportedReason)
            assertEquals(listOf(entry.playbackUnsupportedReason), catalog.notes)
            assertTrue(catalog.notes.none { "secret" in it || "license.test" in it || "001122" in it })
        }
    }

    @Test fun `split legacy URL is joined only when the URL field is empty`() {
        val result = parse("""inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=|Authorization=secret|R%7BSSM%7D|R
            #KODIPROP:inputstream.adaptive.license_url=https://license.test/acquire?
            #KODIPROP:inputstream.adaptive.license_url_append=token=secret
        """.trimIndent()).entries.single()
        assertEquals("https://license.test/acquire?token=secret", result.drm?.licenseUrl)
        assertEquals("secret", result.drm?.licenseHeaders?.get("Authorization"))
    }

    @Test fun `invalid or repeated directives cannot silently downgrade a protected entry`() {
        val result = parse("""inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/a
            #KODIPROP:inputstream.adaptive.drm_legacy=com.widevine.alpha|https://license.test/b
        """.trimIndent()).entries.single()
        assertNotNull(result.playbackUnsupportedReason)
        assertNull(result.drm)
    }

    @Test fun `header policy snapshots input and rejects oversized or malformed input without secrets`() {
        val headers = mutableMapOf("X-License" to "secret")
        val config = DrmPolicy.validated(DrmConfig(DrmScheme.WIDEVINE, "https://license.test/", headers))
        headers["X-License"] = "changed"
        assertEquals("secret", config.licenseHeaders["X-License"])
        val failure = assertFailsWith<IllegalArgumentException> {
            DrmPolicy.validated(config.copy(licenseHeaders = mapOf("X-License" to "secret\nvalue")))
        }
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test fun `legacy catchup days are a fallback only and explicit none wins`() {
        val config = source.copy(catchupDaysFallback = 1.5)
        fun entry(attributes: String) = M3uParser.parse("#EXTM3U\n#EXTINF:-1 $attributes,Channel\nhttps://stream.test/live", config).entries.single()
        assertEquals(1.5, entry("").catchup?.days)
        assertEquals(3.0, entry("catchup-days=\"3\"").catchup?.days)
        assertNull(entry("catchup-days=\"0\"").catchup)
        assertNull(entry("catchup=\"none\"").catchup)
        assertNull(entry("catchup-days=\"invalid\"").catchup)
        val global = M3uParser.parse("#EXTM3U catchup-days=\"4\"\n#EXTINF:-1,Channel\nhttps://stream.test/live", config)
        assertEquals(4.0, global.entries.single().catchup?.days)
    }

    private fun parse(property: String): Catalog = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Protected\n#KODIPROP:$property\nhttps://stream.test/manifest.mpd", source)
}
