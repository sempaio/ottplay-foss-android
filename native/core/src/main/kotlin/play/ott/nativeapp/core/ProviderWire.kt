package play.ott.nativeapp.core

import kotlinx.serialization.json.*
import play.ott.core.ProviderValue
import play.ott.core.ProviderValueKind

/** The host JSON parser supplies typed data, never provider decisions. */
internal fun JsonElement.toProviderValue(): ProviderValue = when (this) {
    JsonNull -> ProviderValue.nil
    is JsonObject -> ProviderValue.obj(mapValues { it.value.toProviderValue() })
    is JsonArray -> ProviderValue.array(map { it.toProviderValue() })
    is JsonPrimitive -> ProviderValue(if (isString) ProviderValueKind.TEXT else if (booleanOrNull != null) ProviderValueKind.BOOLEAN else ProviderValueKind.NUMBER, content)
}

internal fun ProviderValue.toJsonElement(): JsonElement = when(kind) {
    ProviderValueKind.MISSING, ProviderValueKind.NULL -> JsonNull
    ProviderValueKind.TEXT -> JsonPrimitive(scalar)
    ProviderValueKind.NUMBER -> scalar.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(scalar.toDouble())
    ProviderValueKind.BOOLEAN -> JsonPrimitive(scalar=="true")
    ProviderValueKind.ARRAY -> JsonArray(elements.map { it.toJsonElement() })
    ProviderValueKind.OBJECT -> JsonObject(properties.mapValues { it.value.toJsonElement() })
}
