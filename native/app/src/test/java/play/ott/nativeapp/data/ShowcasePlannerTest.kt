package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

class ShowcasePlannerTest {
    @Test fun xtreamCategoriesAppearBeforeAnyMoviesAreDownloaded() {
        val groups = listOf(
            XtreamGroup(id = "8", name = "Aventura", kind = MediaKind.MOVIE),
            XtreamGroup(id = "9", name = "Drama", kind = MediaKind.MOVIE),
            XtreamGroup(id = "10", name = "Esportes", kind = MediaKind.LIVE),
        )
        assertEquals(
            listOf("Aventura", "Drama"),
            ShowcasePlanner.categories(emptyList(), MediaKind.MOVIE, groups)
        )
    }

    @Test fun xtreamRowsMatchOriginalProviderGroupWhenClassificationDiffers() {
        val library = listOf(
            entry(1, MediaKind.LIVE, "Variedade"),
            entry(2, MediaKind.LIVE, "Notícias"),
        )
        val rows = ShowcasePlanner.rows(
            library, MediaKind.LIVE, selectedCategory = "Variedade", preferSourceGroup = true
        )
        assertEquals(listOf("1"), rows.single().items.map { it.id })
        assertEquals("Variedade", rows.single().title)
    }
    private fun entry(id: Int, kind: MediaKind, group: String) =
        MediaEntry(id = id.toString(), sourceId = "qa", name = "Conteúdo $id", kind = kind, group = group)

    @Test fun onlyRealGroupsAppearForSelectedMediaKind() {
        val library = listOf(
            entry(1, MediaKind.MOVIE, "Aventura"),
            entry(2, MediaKind.MOVIE, "Aventura"),
            entry(3, MediaKind.MOVIE, "Drama"),
            entry(4, MediaKind.SERIES, "Drama"),
            entry(5, MediaKind.LIVE, "Esportes"),
        )
        val groups = ShowcasePlanner.categories(library, MediaKind.MOVIE)
        assertEquals(setOf("Aventura", "Drama"), groups.toSet())
        assertFalse(groups.contains("Esportes"))
        assertFalse(groups.contains("Globoplay"))
    }

    @Test fun changingBatchRotatesWithoutRepeatingOrLosingSources() {
        val library = (0 until 9).map { entry(it, MediaKind.MOVIE, "Aventura") }
        val first = ShowcasePlanner.rows(library, MediaKind.MOVIE, "Aventura", 0, batchSize = 4).single()
        val second = ShowcasePlanner.rows(library, MediaKind.MOVIE, "Aventura", 1, batchSize = 4).single()
        val third = ShowcasePlanner.rows(library, MediaKind.MOVIE, "Aventura", 2, batchSize = 4).single()
        assertEquals(listOf("0", "1", "2", "3"), first.items.map { it.id })
        assertEquals(listOf("4", "5", "6", "7"), second.items.map { it.id })
        assertEquals(listOf("8", "0", "1", "2"), third.items.map { it.id })
        assertEquals(4, third.items.distinctBy { it.id }.size)
    }

    @Test fun selectedCategoryLimitsVitrinesWithoutChangingCatalog() {
        val library = listOf(
            entry(1, MediaKind.LIVE, "Esportes"),
            entry(2, MediaKind.LIVE, "Notícias"),
            entry(3, MediaKind.MOVIE, "Esportes"),
        )
        val filtered = ShowcasePlanner.rows(library, MediaKind.LIVE, "Esportes", 0)
        assertEquals(1, filtered.size)
        assertEquals("Esportes", filtered.single().title)
        assertEquals(listOf("1"), filtered.single().items.map { it.id })
        assertEquals(3, library.size)
    }

    @Test fun noEmptyInventedCategoriesOrCrossKindMixing() {
        val library = listOf(
            entry(1, MediaKind.SERIES, ""),
            entry(2, MediaKind.MOVIE, "Ação"),
            entry(3, MediaKind.SERIES, "Animes"),
        )
        assertEquals(listOf("Animes"), ShowcasePlanner.categories(library, MediaKind.SERIES))
        assertEquals(listOf("Animes"), ShowcasePlanner.rows(library, MediaKind.SERIES, "", 0).map { it.title })
        assertTrue(ShowcasePlanner.rows(library, MediaKind.LIVE, "", 0).isEmpty())
    }

    @Test fun rowsRespectLimitAndPreserveIdentifiers() {
        val library = (0 until 40).map {
            entry(it, MediaKind.MOVIE, if (it % 2 == 0) "Drama" else "Comédia")
        }
        val result = ShowcasePlanner.rows(library, MediaKind.MOVIE, "", 0, maxRows = 1, batchSize = 6)
        assertEquals(1, result.size)
        assertEquals(6, result.single().items.size)
        assertTrue(result.single().items.all { it.sourceId == "qa" })
    }
}
