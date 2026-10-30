package play.ott.nativeapp.data

import kotlinx.serialization.Serializable
import play.ott.nativeapp.core.MediaEntry

/** Descriptor may contain authenticated URLs: persist only through SourceVault encryption. */
@Serializable
data class RecentPlayback(val entry: MediaEntry, val positionMs: Long, val durationMs: Long? = null, val updatedMs: Long = System.currentTimeMillis()) {
    override fun toString() = "RecentPlayback(positionMs=$positionMs, durationMs=$durationMs)"
}
