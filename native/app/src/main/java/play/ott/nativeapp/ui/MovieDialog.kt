package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry

@Composable
internal fun MovieDialog(entry: MediaEntry, loading: Boolean, favorite: Boolean, onAction: (AppAction) -> Unit) {
    Dialog({ onAction(AppAction.CloseMovie) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.tvRemoteInput().fillMaxWidth(.94f).widthIn(max = 860.dp).fillMaxHeight(.9f), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(entry.name, style = MaterialTheme.typography.headlineMedium)
                if (entry.logo.isNotBlank()) CatalogArtwork(entry, Modifier.fillMaxWidth().heightIn(max = 200.dp), surface = "detalhe")
                Text(entry.group, color = MaterialTheme.colorScheme.secondary)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (entry.description.isNotBlank()) Text(entry.description)
                ActionButton(stringResource(R.string.dialog_watch), { onAction(AppAction.Play(entry)); onAction(AppAction.CloseMovie) }, Modifier.fillMaxWidth(), selected = true, enabled = !loading)
                ActionButton(stringResource(if (favorite) R.string.library_favorite_selected else R.string.library_favorite_add), { onAction(AppAction.ToggleFavorite(entry)) }, Modifier.fillMaxWidth())
                ActionButton(stringResource(R.string.dialog_close), { onAction(AppAction.CloseMovie) }, Modifier.fillMaxWidth())
            }
        }
    }
}
