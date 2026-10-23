package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import play.ott.nativeapp.R
import play.ott.nativeapp.StarlinkConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.AppTransportPolicy
import java.net.URI
import java.util.UUID

@Composable
internal fun SourceEditor(original: SourceConfig?, onDismiss: () -> Unit, onSave: (SourceConfig) -> Unit) =
    SourceEditor(original, onDismiss, false, onSave)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceEditor(
    original: SourceConfig?,
    onDismiss: () -> Unit,
    busy: Boolean,
    onSave: (SourceConfig) -> Unit,
) {
    val id = rememberSaveable(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val simpleLogin = (original == null || (original.kind == SourceKind.XTREAM && original.url == StarlinkConfig.XTREAM_BASE_URL)) && !advanced
    val localSource = original != null && !isHttpUrl(original.url)
    var enteredName by rememberSaveable(id) { mutableStateOf(original?.name ?: "STARLINK IPTV") }
    var nameWasEdited by rememberSaveable(id) { mutableStateOf(false) }
    // An untouched generated label follows locale changes, including Activity recreation.
    // Explicit input remains saveable even when it exactly matches an older translation.
    val name = if (!nameWasEdited && original?.nameMessage != null && !original.nameIsUserDefined) original.name else enteredName
    var kind by rememberSaveable(id) { mutableStateOf(original?.kind ?: SourceKind.XTREAM) }
    var url by rememberSaveable(id) { mutableStateOf(original?.url ?: StarlinkConfig.XTREAM_BASE_URL) }
    var username by rememberSaveable(id) { mutableStateOf(original?.username.orEmpty()) }
    var password by remember(id) { mutableStateOf(original?.password.orEmpty()) }
    var mac by rememberSaveable(id) { mutableStateOf(original?.mac.orEmpty()) }
    var epgUrl by rememberSaveable(id) { mutableStateOf(original?.epgUrl.orEmpty()) }
    var catchupHours by rememberSaveable(id) {
        mutableStateOf(original?.catchupDaysFallback?.takeIf { it > 0 }?.times(24)?.toString().orEmpty())
    }
    var headers by rememberSaveable(id) { mutableStateOf(original?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty()) }
    var showPassword by rememberSaveable(id) { mutableStateOf(false) }
    var validation by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    NeonSourceDialog(simpleLogin,
        modifier = Modifier.tvRemoteInput(),
        onDismissRequest = onDismiss,
        title = { Text(if (simpleLogin) "Entrar • STARLINK IPTV" else if (original == null) stringResource(R.string.dialog_source_add_title) else stringResource(R.string.dialog_source_edit_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { advanced = !advanced; if (!advanced) { kind = SourceKind.XTREAM; url = StarlinkConfig.XTREAM_BASE_URL } }) { Text(if (advanced) "Login M81" else "Outras fontes / opções avançadas") }
                if (simpleLogin) Text("Servidor M81 configurado. Informe seu usuário e senha. A lista STARLINK IPTV também será incluída.")
                if (!simpleLogin) {
                Text(stringResource(R.string.dialog_source_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceKind.entries.forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, enabled = !localSource, label = { Text(option.label()) })
                    }
                }
                OutlinedTextField(value = name, onValueChange = { enteredName = it; nameWasEdited = true }, label = { Text(stringResource(R.string.dialog_source_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = if (localSource) (if (original.url.startsWith("content://")) stringResource(R.string.dialog_source_local_file) else stringResource(R.string.dialog_source_demo)) else url,
                    onValueChange = { url = it }, readOnly = localSource,
                    label = { Text(if (kind == SourceKind.M3U) stringResource(R.string.dialog_source_playlist_url) else stringResource(R.string.dialog_source_server_url)) },
                    placeholder = { Text(stringResource(R.string.dialog_source_url_example)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                }
                if (kind == SourceKind.XTREAM) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.dialog_source_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.dialog_source_password)) }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) stringResource(R.string.dialog_hide) else stringResource(R.string.dialog_show)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!simpleLogin) {
                if (kind == SourceKind.STALKER) {
                    OutlinedTextField(
                        value = mac, onValueChange = { mac = it }, label = { Text(stringResource(R.string.dialog_source_mac)) },
                        placeholder = { Text(stringResource(R.string.dialog_source_mac_example)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.dialog_source_mac_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = epgUrl, onValueChange = { epgUrl = it }, label = { Text(stringResource(R.string.dialog_source_epg)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (kind == SourceKind.M3U) OutlinedTextField(
                    value = catchupHours, onValueChange = { catchupHours = it },
                    label = { Text(stringResource(R.string.dialog_source_archive_hours)) },
                    supportingText = { Text(stringResource(R.string.dialog_source_archive_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headers, onValueChange = { headers = it }, label = { Text(stringResource(R.string.dialog_source_headers)) },
                    placeholder = { Text(stringResource(R.string.dialog_source_headers_example)) },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.dialog_source_headers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                validation?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        confirmButton = {
            ActionButton(enabled = !busy, text = if (busy) stringResource(R.string.library_loading) else if (simpleLogin) "Entrar" else if (original == null) stringResource(R.string.dialog_add) else stringResource(R.string.dialog_save), selected = true, onClick = {
                val cleanUrl = if (simpleLogin) StarlinkConfig.XTREAM_BASE_URL else url.trim()
                val parsedHeaders = parseHeaders(headers)
                val hours = if (catchupHours.isBlank()) 0.0 else catchupHours.trim().replace(',', '.').toDoubleOrNull()
                validation = when {
                    name.isBlank() -> R.string.dialog_validation_name
                    !isAllowedNetworkUrl(cleanUrl) && !(localSource && cleanUrl == original.url) ->
                        if (AppTransportPolicy.current.allowInsecureHttp) R.string.dialog_validation_source_http
                        else R.string.dialog_validation_source_https
                    kind == SourceKind.XTREAM && (username.isBlank() || password.isBlank()) -> R.string.dialog_validation_credentials
                    kind == SourceKind.STALKER && !Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac.trim()) -> R.string.dialog_validation_mac
                    epgUrl.isNotBlank() && !isAllowedNetworkUrl(epgUrl.trim()) ->
                        if (AppTransportPolicy.current.allowInsecureHttp) R.string.dialog_validation_epg_http
                        else R.string.dialog_validation_epg_https
                    parsedHeaders == null -> R.string.dialog_validation_headers
                    kind == SourceKind.M3U && (hours == null || !hours.isFinite() || hours < 0) -> R.string.dialog_validation_archive
                    else -> null
                }
                if (validation == null) {
                    val source = original ?: SourceConfig(id, name.trim(), kind, cleanUrl)
                    onSave(source.copy(id = id, name = name.trim(), kind = kind, url = cleanUrl,
                        nameMessage = if (nameWasEdited) null else source.nameMessage,
                        nameIsUserDefined = source.nameIsUserDefined || nameWasEdited,
                        username = username.trim(), password = password, mac = mac.trim(), epgUrl = epgUrl.trim(),
                        headers = parsedHeaders.orEmpty(), catchupDaysFallback = if (kind == SourceKind.M3U) requireNotNull(hours) / 24.0 else source.catchupDaysFallback))
                }
            })
        },
    )
}

@Composable
internal fun SourceKind.label(): String = when (this) {
    SourceKind.M3U -> stringResource(R.string.dialog_source_kind_m3u)
    SourceKind.XTREAM -> stringResource(R.string.dialog_source_kind_xtream)
    SourceKind.STALKER -> stringResource(R.string.dialog_source_kind_stalker)
}

private fun isHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun isAllowedNetworkUrl(value: String): Boolean = isHttpUrl(value) &&
    runCatching { AppTransportPolicy.current.requireHttpUrl(value) }.isSuccess

private fun parseHeaders(text: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    for (line in text.lines().filter { it.isNotBlank() }) {
        val delimiter = line.indexOf(':')
        if (delimiter <= 0) return null
        val key = line.substring(0, delimiter).trim()
        val value = line.substring(delimiter + 1).trim()
        if (!Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(key) || value.contains('\r') || value.contains('\n')) return null
        result[key] = value
    }
    return result
}
