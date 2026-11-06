package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

class NetworkDiscoveryTest {
    private fun group(id: String, name: String, kind: MediaKind = MediaKind.LIVE) =
        XtreamGroup(id, name, kind)

    @Test fun globoSearchCanExpandToRegionalGroupsWithoutDownloadingAllCategories() {
        val categories = listOf(
            group("1", "Canais abertos"),
            group("2", "Globo Sudeste"),
            group("3", "GLOBOS | RJ e SP"),
            group("4", "Globo News"),
            group("5", "SBT News"),
            group("6", "Variedades"),
            group("7", "Globo RJ", MediaKind.MOVIE)
        )
        val result = LiveNetworkDiscovery.related(categories, "Globo")
        assertEquals(listOf("2", "3"), result.map { it.id })
        assertTrue(result.all { it.kind == MediaKind.LIVE })
    }

    @Test fun broadAllNetworkGroupsStayUntouchedAndCandidatesAreBounded() {
        val candidates = (0..35).map { group(it.toString(), "Globo $it") }
        assertEquals(8, LiveNetworkDiscovery.related(candidates, "Globo").size)
        assertTrue(LiveNetworkDiscovery.related(candidates, "Unknown").isEmpty())
    }

    @Test fun liveGroupsWithNewsOrSportsDontPolluteOpenChannels() {
        val rows = listOf(group("1", "RecordTV RJ"), group("2", "Record News"),
            group("3", "Band Sports"), group("4", "Band Rio"),
            group("5", "GloboNews"), group("6", "Globo RJ"))
        assertEquals(listOf("1"), LiveNetworkDiscovery.related(rows, "Record").map { it.id })
        assertEquals(listOf("4"), LiveNetworkDiscovery.related(rows, "Band").map { it.id })
        assertEquals(listOf("6"), LiveNetworkDiscovery.related(rows, "Globo").map { it.id })
    }
}
