package play.ott.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.MediaEntry

class HomeCategoryShortcutsTest {
    @Test fun oneCategoryStripWithoutRepeatingExistingGroupNames() {
        val entries = listOf(
            MediaEntry(id = "1", sourceId = "qa", name = "Filme A", kind = MediaKind.MOVIE, group = "Ação"),
            MediaEntry(id = "2", sourceId = "qa", name = "Filme B", kind = MediaKind.MOVIE, group = "Ação"),
            MediaEntry(id = "3", sourceId = "qa", name = "Filme C", kind = MediaKind.MOVIE, group = "Drama"),
            MediaEntry(id = "4", sourceId = "qa", name = "Série A", kind = MediaKind.SERIES, group = "Drama"),
        )
        val categories = HomeCategoryShortcuts.fromEntries(entries)
        assertEquals(
            listOf(MediaKind.MOVIE to "Ação", MediaKind.MOVIE to "Drama", MediaKind.SERIES to "Drama"),
            categories
        )
        assertEquals(
            listOf("1", "2"),
            HomeCategoryShortcuts.preview(entries, MediaKind.MOVIE to "Ação").map { it.id }
        )
        assertEquals(
            listOf("4"),
            HomeCategoryShortcuts.preview(entries, MediaKind.SERIES to "Drama").map { it.id }
        )
    }

    @Test fun onlyExistingCategoriesAreShownWithBoundedNavigation() {
        val rails = listOf(
            MediaKind.MOVIE to "",
            MediaKind.MOVIE to "Aventura",
            MediaKind.MOVIE to "Drama",
            MediaKind.MOVIE to "Aventura",
            MediaKind.SERIES to "Comédia",
        )
        assertEquals(
            listOf(MediaKind.MOVIE to "Aventura", MediaKind.MOVIE to "Drama"),
            HomeCategoryShortcuts.select(rails, limit = 2)
        )
    }

    @Test fun sameCategoryNameInDifferentKindsKeepsSeparateNavigation() {
        val rails = listOf(
            MediaKind.MOVIE to "Drama",
            MediaKind.SERIES to "Drama",
            MediaKind.LIVE to "  ",
        )
        assertEquals(
            listOf(MediaKind.MOVIE to "Drama", MediaKind.SERIES to "Drama"),
            HomeCategoryShortcuts.select(rails)
        )
    }
}
