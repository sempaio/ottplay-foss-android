package play.ott.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import play.ott.nativeapp.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.Programme
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal fun GuideDialog(entry: MediaEntry, programmes: List<Programme>, loading: Boolean, onAction: (AppAction) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val ordered = remember(programmes) { programmes.sortedBy { it.startMillis } }
    val locale = LocalConfiguration.current.locales[0]
    val startFormat = stringResource(R.string.dialog_programme_start_format)
    val endFormat = stringResource(R.string.dialog_programme_end_format)
    val formatter = remember(locale, startFormat) { SimpleDateFormat(startFormat, locale) }
    val timeFormatter = remember(locale, endFormat) { SimpleDateFormat(endFormat, locale) }
    val initialIndex = remember(entry.id, ordered) {
        ordered.indexOfFirst { it.endMillis > System.currentTimeMillis() }.coerceAtLeast(0)
    }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(entry.id, ordered) { if (ordered.isNotEmpty()) scroll.scrollToItem(initialIndex) }
    Dialog(onDismissRequest = { onAction(AppAction.CloseEpg) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.tvRemoteInput().fillMaxWidth(.94f).widthIn(max = 860.dp).fillMaxHeight(.91f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dialog_guide), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(entry.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    ActionButton(stringResource(R.string.dialog_close), { onAction(AppAction.CloseEpg) }, compact = true)
                }
                ActionButton(stringResource(R.string.dialog_watch_live), { onAction(AppAction.Play(entry)); onAction(AppAction.CloseEpg) }, selected = true)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ordered.isEmpty() && !loading) {
                    Text(stringResource(R.string.dialog_guide_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.dialog_guide_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(state = scroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ordered) { programme ->
                        val live = now >= programme.startMillis && now < programme.endMillis
                        val past = programme.endMillis <= now
                        val catchup = entry.catchup
                        val inArchiveWindow = catchup != null && catchup.days > 0 && programme.startMillis >= now - (catchup.days * 86_400_000).toLong()
                        val replayable = past && inArchiveWindow
                        ProgrammeCard(
                            title = programme.title,
                            description = programme.description,
                            time = stringResource(R.string.dialog_programme_range, formatter.format(Date(programme.startMillis)), timeFormatter.format(Date(programme.endMillis))),
                            status = when { live -> stringResource(R.string.dialog_programme_live); replayable -> stringResource(R.string.dialog_programme_archive); past -> stringResource(R.string.dialog_programme_ended); else -> stringResource(R.string.dialog_programme_soon) },
                            live = live,
                            actionLabel = when { live -> stringResource(R.string.dialog_watch); replayable -> stringResource(R.string.dialog_watch_archive); else -> null },
                            onClick = {
                                if (live) onAction(AppAction.Play(entry)) else onAction(AppAction.PlayProgramme(entry, programme))
                                onAction(AppAction.CloseEpg)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCard(title: String, description: String, time: String, status: String, live: Boolean, actionLabel: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp)).focusable(),
        colors = CardDefaults.cardColors(containerColor = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actionLabel?.let { ActionButton(it, onClick, selected = live) }
        }
    }
}

@Composable
internal fun SeriesDialog(entry: MediaEntry, episodes: List<MediaEntry>, loading: Boolean, onAction: (AppAction) -> Unit) {
    val ordered = remember(episodes) { episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.name })) }
    Dialog(onDismissRequest = { onAction(AppAction.CloseSeries) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.tvRemoteInput().fillMaxWidth(.94f).widthIn(max = 860.dp).fillMaxHeight(.91f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dialog_series), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(entry.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    ActionButton(stringResource(R.string.dialog_close), { onAction(AppAction.CloseSeries) }, compact = true)
                }
                if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ordered.isEmpty() && !loading) Text(stringResource(R.string.dialog_series_empty))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ordered.groupBy { it.season ?: 1 }.forEach { (season, entries) ->
                        item { Text(stringResource(R.string.dialog_season, season), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                        items(entries, key = { it.id }) { episode ->
                            var focused by remember { mutableStateOf(false) }
                            val shape = RoundedCornerShape(16.dp)
                            Card(
                                onClick = { onAction(AppAction.Play(episode)); onAction(AppAction.CloseSeries) },
                                shape = shape,
                                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.border(BorderStroke(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent), shape),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        episode.episode?.let { Text(stringResource(R.string.dialog_episode, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
                                        Text(episode.name, style = MaterialTheme.typography.titleMedium)
                                        if (episode.description.isNotBlank()) Text(episode.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    ActionButton(stringResource(R.string.dialog_watch), { onAction(AppAction.Play(episode)); onAction(AppAction.CloseSeries) }, compact = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
