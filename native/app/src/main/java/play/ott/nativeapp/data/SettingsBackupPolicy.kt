package play.ott.nativeapp.data

import play.ott.core.DurableSelections

/** Validate every preference before the importer writes either vault or catalogue. */
internal fun validateBackupPreferences(preferences: UserPreferences) {
    DurableSelections.validateBackup(preferences.selectedSourceId, preferences.favorites, preferences.resumePositions)
    preferences.audioTrack.validate()
    preferences.subtitleTrack.validate()
}

internal fun mergeBackupPreferences(old: UserPreferences, imported: UserPreferences, sourceIds: Set<String>): UserPreferences = old.copy(
    selectedSourceId = DurableSelections.selectedSource(imported.selectedSourceId, old.selectedSourceId, sourceIds),
    favorites = DurableSelections.mergeFavorites(old.favorites, imported.favorites),
    backgroundPlayback = imported.backgroundPlayback,
    resumePositions = DurableSelections.mergeResume(old.resumePositions, imported.resumePositions),
    audioTrack = imported.audioTrack,
    subtitleTrack = imported.subtitleTrack,
)
