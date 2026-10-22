package play.ott.nativeapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.SourceConfig

private val Context.preferences by preferencesDataStore("player")
@Serializable data class UserPreferences(
    val selectedSourceId: String? = null,
    val favorites: Set<String> = emptySet(),
    val backgroundPlayback: Boolean = true,
    val resumePositions: Map<String, Long> = emptyMap(),
    val audioTrack: TrackPreference = TrackPreference(),
    val subtitleTrack: TrackPreference = TrackPreference(),
)

/** A track number belongs to one manifest; only language/role/label survive replacement. */
@Serializable data class TrackPreference(
    val mode: String = "auto",
    val language: String? = null,
    val roleFlags: Int = 0,
    val label: String? = null,
) {
    fun validate() {
        require(mode in setOf("auto", "off", "track")) { "Invalid track preference" }
        require(language == null || language.matches(Regex("[a-zA-Z0-9]{1,8}(-[a-zA-Z0-9]{1,8}){0,7}"))) { "Invalid track language" }
        require(roleFlags in 0..0x1ffff) { "Invalid track role" }
        require(label == null || (label.length in 1..128 && label.none { it.isISOControl() })) { "Invalid track label" }
        require(mode == "track" || (language == null && roleFlags == 0 && label == null)) { "Invalid automatic track preference" }
        require(mode != "track" || language != null || roleFlags != 0 || label != null) { "Empty track preference" }
    }
}
@Serializable data class SettingsBackup(
    val version: Int = 1,
    val sources: List<SourceConfig>,
    val preferences: UserPreferences = UserPreferences()
)

class PreferencesStore(context: Context) {
    private val store = context.preferences
    private val key = stringPreferencesKey("preferences.v1")
    private val json = Json { ignoreUnknownKeys = true }
    internal val resumeWriter by lazy { ResumePositionWriter(::saveResumePosition) }
    val data: Flow<UserPreferences> = store.data.map { preferences ->
        preferences[key]?.let { json.decodeFromString<UserPreferences>(it) } ?: UserPreferences()
    }
    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.edit { values ->
            val current = values[key]?.let { json.decodeFromString<UserPreferences>(it) } ?: UserPreferences()
            values[key] = json.encodeToString(transform(current))
        }
    }

    suspend fun saveResumePosition(id: String, positionMs: Long) = update { preferences ->
        val positions = play.ott.core.DurableSelections.saveResume(preferences.resumePositions, id, positionMs)
        if (positions === preferences.resumePositions) preferences else preferences.copy(resumePositions = positions)
    }
}
