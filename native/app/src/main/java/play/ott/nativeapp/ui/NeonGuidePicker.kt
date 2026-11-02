package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

@Composable
internal fun NeonGuidePicker(entries: List<MediaEntry>, onSelect: (MediaEntry) -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val channels by produceState<List<MediaEntry>>(emptyList(), entries, search) {
        if (search.isNotBlank()) delay(250)
        value = withContext(Dispatchers.Default) { entries.filter { it.kind == MediaKind.LIVE && (search.isBlank() || it.name.contains(search, true)) } }
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.tvRemoteInput(), title = { Text(stringResource(R.string.library_tv_guide)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(search, { search = it }, singleLine = true, label = { Text(stringResource(R.string.library_search)) })
            if (channels.isEmpty()) Text(stringResource(R.string.library_section_empty))
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(channels, key = { it.id }) { channel -> ActionButton(channel.name, { onSelect(channel) }, Modifier.fillMaxWidth(), compact = true) }
            }
        }
    }, confirmButton = { ActionButton(stringResource(R.string.dialog_close), onDismiss) })
}
