package play.ott.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One application-owned queue orders checkpoints across destroyed/recreated playback services. */
internal class ResumePositionWriter(
    private val persist: suspend (String, Long) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private sealed interface Write {
        data class Position(val id: String, val value: Long) : Write
        data class Barrier(val completed: CompletableDeferred<Unit>) : Write
    }
    private val queue = Channel<Write>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (write in queue) when (write) {
            is Write.Barrier -> write.completed.complete(Unit)
            is Write.Position -> {
                try {
                    persist(write.id, write.value)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The next tick retries; no stale cache hides imported positions. */ }
            }
        }
    }

    fun enqueue(id: String, positionMs: Long) {
        if (id.isNotBlank()) check(queue.trySend(Write.Position(id, positionMs)).isSuccess) { "Resume writer closed" }
    }

    suspend fun awaitIdle() {
        val completed = CompletableDeferred<Unit>()
        queue.send(Write.Barrier(completed))
        completed.await()
    }

    suspend fun close() { queue.close(); worker.join() }
}
