package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import play.ott.nativeapp.ArtworkOrigins
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.artworkInventory

@Composable
internal fun ArtworkOriginsDialog(state: AppUiState, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val report by produceState("Preparando relatório…", revision, state.selectedSourceId, state.entries) {
        value = withContext(Dispatchers.Default) {
            "STARLINK IPTV ${BuildConfig.VERSION_NAME}\nCódigo: ${BuildConfig.SOURCE_REVISION}\n" +
                artworkInventory(state.selectedSourceId, state.entries) + "\n\n" + ArtworkOrigins.session.report()
        }
    }
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Origem das capas") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Abra Séries, Filmes e TV ao vivo e role cada aba. Volte aqui para comparar as origens e os tempos.")
            ActionButton("Atualizar relatório", { revision++ }, Modifier.fillMaxWidth())
            ActionButton("Copiar relatório", { clipboard.setText(AnnotatedString(report)) }, Modifier.fillMaxWidth())
            ActionButton("Zerar medições", { ArtworkOrigins.session.clear(); revision++ }, Modifier.fillMaxWidth())
            Text("Zerar mantém sua conta, seu catálogo e as imagens salvas. O relatório não é enviado automaticamente.", style = MaterialTheme.typography.bodySmall)
            Text(report, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { ActionButton("Concluído", onDismiss) })
}
