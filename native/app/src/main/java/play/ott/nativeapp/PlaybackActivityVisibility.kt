package play.ott.nativeapp

/** A brief Activity recreation must not look like leaving TV playback for Home. */
internal class PlaybackActivityVisibility(
    private val emit: (Boolean) -> Unit,
    private val scheduleHandoffExpiry: (() -> Unit) -> (() -> Unit),
) {
    private val started = mutableSetOf<Any>()
    private var cancelHandoff: (() -> Unit)? = null
    private var generation = 0

    fun started(activity: Any) {
        started += activity
        clearHandoff()
        emit(true)
    }

    fun stopped(activity: Any, changingConfigurations: Boolean) {
        if (!started.remove(activity)) return
        clearHandoff()
        if (changingConfigurations && started.isEmpty()) {
            val expected = generation
            cancelHandoff = scheduleHandoffExpiry {
                if (generation == expected) {
                    cancelHandoff = null
                    emit(started.isNotEmpty())
                }
            }
        }
        emit(started.isNotEmpty() || cancelHandoff != null)
    }

    private fun clearHandoff() {
        generation++
        cancelHandoff?.invoke()
        cancelHandoff = null
    }
}
