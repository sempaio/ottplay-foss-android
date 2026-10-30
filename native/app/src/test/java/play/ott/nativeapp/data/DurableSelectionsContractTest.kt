package play.ott.nativeapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = play.ott.nativeapp.playback.PlaybackTestApplication::class)
class DurableSelectionsContractTest {
    @Test fun `actual validation merge and DataStore resume writer preserve shipping receipts`() = runBlocking {
        val rows = Json.parseToJsonElement(javaClass.getResource("/android-selections-before-core.json")!!.readText()).jsonObject.getValue("rows").jsonArray
        val expected = rows.filter { it.jsonArray.size >= 3 }.associate { it.jsonArray[0].jsonPrimitive.content to it.jsonArray.map { value -> value.jsonPrimitive.content } }
        fun verify(name: String, block: () -> UserPreferences) {
            val actual = try {
                val result = block()
                // Receipts cover the original durable selections, not later preference fields.
                val selections = "UserPreferences(selectedSourceId=${result.selectedSourceId}, favorites=${result.favorites}, backgroundPlayback=${result.backgroundPlayback}, resumePositions=${result.resumePositions})"
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(selections.toByteArray()).joinToString("") { "%02x".format(it) }
                listOf(name, "OK", digest)
            } catch (failure: Throwable) { listOf(name, "ERROR", failure.javaClass.name, failure.message.orEmpty()) }
            assertEquals(expected.getValue(name).take(actual.size), actual, name)
        }
        val valid = UserPreferences()
        verify("defaults validate") { validateBackupPreferences(valid); valid }
        listOf("", "bad\nsource", "x".repeat(512), "x".repeat(513), "\u2000", "\u0085").forEachIndexed { index, id ->
            verify("selected boundary $index") { val value = valid.copy(selectedSourceId = id); validateBackupPreferences(value); value }
        }
        listOf(-1L, 0L, Long.MAX_VALUE).forEach { position -> verify("position $position") { val value = valid.copy(resumePositions = mapOf("film" to position)); validateBackupPreferences(value); value } }
        listOf(500, 501).forEach { count -> verify("position count $count") { val value = valid.copy(resumePositions = (0 until count).associate { "film-$it" to it.toLong() }); validateBackupPreferences(value); value } }
        verify("favorite control character") { val value = valid.copy(favorites = setOf("bad\u0000id")); validateBackupPreferences(value); value }
        val old = UserPreferences("old", (0 until 10000).map { "favorite-$it" }.toSet(), true, (0 until 500).associate { "film-$it" to it.toLong() })
        verify("backup union capacity and overwrite order") { mergeBackupPreferences(old, UserPreferences("new", setOf("favorite-0", "fresh"), false, mapOf("film-0" to 999L, "fresh" to 123L)), setOf("new")) }
        verify("unknown selected source retains previous") { mergeBackupPreferences(old, UserPreferences("missing"), emptySet()) }
        val context = ApplicationProvider.getApplicationContext<Context>(); val store = PreferencesStore(context)
        listOf(Triple("equal position keeps insertion order", "film-0", 0L), Triple("negative clamps before equality", "film-0", -9L), Triple("changed position becomes newest", "film-0", 90L), Triple("new position evicts first", "new", 90L)).forEach { (label, id, position) ->
            store.update { old }; store.saveResumePosition(id, position)
            val saved = store.data.first(); verify(label) { saved }
        }
    }
}
