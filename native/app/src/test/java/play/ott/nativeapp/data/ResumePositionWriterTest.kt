package play.ott.nativeapp.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ResumePositionWriterTest {
    @Test fun `a slow checkpoint from the old service cannot overwrite a new service seek`() = runTest {
        val oldWrite = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Long>()
        val writer = ResumePositionWriter({ _, value ->
            if (value == 1_000L) oldWrite.await()
            persisted += value
        }, this)
        writer.enqueue("movie", 1_000)
        runCurrent()
        // The old service closes while its disk operation is still pending. The new service seeks.
        writer.enqueue("movie", 500)
        runCurrent()
        assertTrue(persisted.isEmpty())
        oldWrite.complete(Unit)
        writer.awaitIdle()
        assertEquals(listOf(1_000L, 500L), persisted)
        writer.close()
    }

    @Test fun `paused position retries failed writes and remains coherent after an imported offset`() = runTest {
        var attempts = 0
        var stored: Long? = null
        val writer = ResumePositionWriter({ _, value ->
            attempts++
            if (attempts == 1) throw java.io.IOException("Synthetic storage failure")
            stored = value
        }, this)
        writer.enqueue("paused", 2_000)
        writer.awaitIdle()
        assertEquals(null, stored)
        writer.enqueue("paused", 2_000)
        writer.awaitIdle()
        assertEquals(2_000, stored)
        stored = 9_000 // A settings import or another preference writer changed the disk value.
        writer.enqueue("paused", 2_000)
        writer.awaitIdle()
        assertEquals(2_000, stored)
        assertEquals(3, attempts)
        writer.close()
    }
}
