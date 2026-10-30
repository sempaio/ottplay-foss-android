package play.ott.nativeapp.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LegacyImportResult(val sources: List<SourceConfig>, val notes: List<String>, val messages: List<CoreMessage> = emptyList())

/**
 * Imports provider configuration from an explicitly selected legacy storage dump.
 * The ordinary FOSS settings-v1 export does not include provider credentials.
 * This reader never executes JavaScript, decodes arbitrary compressed payloads, or fetches URLs.
 */
object LegacySourceImporter {
    fun parse(text: String, textResolver: CoreTextResolver = CoreTextResolver.ENGLISH): LegacyImportResult {
        if (text.length > 2 * 1024 * 1024) throw ProviderException("Legacy settings file exceeds 2 MB")
        val result = try {
            play.ott.core.LegacySettingsImport.native(parseProviderJson(text).toProviderValue(),
                { parseProviderJson(it).toProviderValue() }, { it.toHttpUrlOrNull()?.toString() },
                { it.toHttpUrlOrNull()!!.encodedPath })
        } catch (failure: play.ott.core.DurableStateFailure) { throw ProviderException(failure.message.orEmpty()) }
        val sources = result.sources.map { value ->
            val nameMessage = value.nameKey.takeIf { it.isNotEmpty() }?.let { CoreMessage(CoreMessageKey.valueOf(it), value.args) }
            SourceConfig(stableId(*value.identity.toTypedArray()), nameMessage?.let(textResolver::resolve) ?: value.name,
                SourceKind.valueOf(value.type), value.url, value.username, value.password, mac = value.mac,
                catchupDaysFallback = value.days, nameMessage = nameMessage)
        }.distinctBy { it.id }
        val messages = result.messages.map { CoreMessage(CoreMessageKey.valueOf(it.key), it.args) }
        return LegacyImportResult(sources, messages.map(textResolver::resolve), messages)
    }
}
