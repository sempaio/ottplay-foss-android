package play.ott.nativeapp.core

/** Use the Xtream guide when the local XMLTV cache has no live or future programme. */
suspend fun currentGuide(
    cached: List<Programme>,
    sourceKind: SourceKind?,
    nowMillis: Long,
    loadShortEpg: suspend () -> List<Programme>,
): List<Programme> {
    if (sourceKind != SourceKind.XTREAM || cached.any { it.endMillis > nowMillis }) return cached
    val current = loadShortEpg()
    return current.ifEmpty { cached }
}
