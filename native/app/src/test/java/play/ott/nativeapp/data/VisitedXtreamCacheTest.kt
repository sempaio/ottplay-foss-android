package play.ott.nativeapp.data

import kotlin.test.*
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class VisitedXtreamCacheTest {
    private fun entries(prefix: String, kind: MediaKind, count: Int): List<MediaEntry> =
        (1..count).map { MediaEntry(id = "$prefix-$it", sourceId = "m81", name = "$prefix $it", kind = kind) }

    @Test fun touchPromotesRevisitedCategoryToMostRecent() {
        val cache = linkedMapOf(
            "m81:MOVIE:1" to entries("m1", MediaKind.MOVIE, 5),
            "m81:MOVIE:2" to entries("m2", MediaKind.MOVIE, 5),
        )
        VisitedXtreamCache.touch(cache, "m81:MOVIE:1")
        assertEquals(listOf("m81:MOVIE:2", "m81:MOVIE:1"), cache.keys.toList())
    }

    @Test fun prunePreservesLastCategoryOfEachMediaKindWhenTrimmingLargeMovieCache() {
        val cache = linkedMapOf(
            "m81:SERIES:10" to entries("s1", MediaKind.SERIES, 200),
            "m81:LIVE:20" to entries("l1", MediaKind.LIVE, 100),
            "m81:MOVIE:1" to entries("m1", MediaKind.MOVIE, 18_000),
            "m81:MOVIE:2" to entries("m2", MediaKind.MOVIE, 18_000),
        )
        VisitedXtreamCache.prune(cache, protectedKey = "m81:MOVIE:2", maxGroups = 16, maxEntries = 35_000)

        assertTrue("m81:SERIES:10" in cache, "Series category must stay cached when multi-group MOVIE can be evicted")
        assertTrue("m81:LIVE:20" in cache, "Live category must stay cached")
        assertTrue("m81:MOVIE:2" in cache, "Protected MOVIE category must stay cached")
        assertFalse("m81:MOVIE:1" in cache, "Older duplicate-kind MOVIE category should be evicted first")
    }

    @Test fun pruneRespectsMaxGroupsAndProtectedKey() {
        val cache = LinkedHashMap<String, List<MediaEntry>>()
        for (i in 1..18) {
            cache["m81:SERIES:$i"] = entries("s$i", MediaKind.SERIES, 10)
        }
        VisitedXtreamCache.prune(cache, protectedKey = "m81:SERIES:1", maxGroups = 16, maxEntries = 35_000)

        assertEquals(16, cache.size)
        assertTrue("m81:SERIES:1" in cache)
        assertFalse("m81:SERIES:2" in cache)
        assertFalse("m81:SERIES:3" in cache)
    }
}
