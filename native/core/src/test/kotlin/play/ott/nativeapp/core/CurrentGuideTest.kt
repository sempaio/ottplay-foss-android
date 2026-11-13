package play.ott.nativeapp.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentGuideTest {
    private val historical = Programme("station", "Yesterday", 100L, 200L)
    private val live = Programme("station", "Live", 250L, 350L)
    private val upcoming = Programme("station", "Next", 350L, 450L)

    @Test fun `expired XMLTV cache does not block current Xtream listings`() = runBlocking {
        var requests = 0
        val result = currentGuide(listOf(historical), SourceKind.XTREAM, 300L) {
            requests++
            listOf(live, upcoming)
        }
        assertEquals(listOf(live, upcoming), result)
        assertEquals(1, requests)
    }

    @Test fun `live or upcoming XMLTV cache remains authoritative`() = runBlocking {
        val cached = listOf(historical, upcoming)
        val result = currentGuide(cached, SourceKind.XTREAM, 300L) {
            error("Xtream must not be queried while XMLTV has future data")
        }
        assertEquals(cached, result)
    }

    @Test fun `M3U history never triggers Xtream request`() = runBlocking {
        val result = currentGuide(listOf(historical), SourceKind.M3U, 300L) {
            error("M3U does not use the Xtream endpoint")
        }
        assertEquals(listOf(historical), result)
    }

    @Test fun `empty Xtream response preserves cached archive programmes`() = runBlocking {
        assertEquals(listOf(historical), currentGuide(listOf(historical), SourceKind.XTREAM, 300L) {
            emptyList()
        })
    }
}
