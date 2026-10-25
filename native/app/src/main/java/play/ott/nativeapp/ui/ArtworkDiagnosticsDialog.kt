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
import play.ott.nativeapp.ArtworkDiagnostics
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.artworkContextReport

@Composable
internal fun ArtworkDiagnosticsDialog(state: AppUiState, onDismiss: () -> Unit) {
    fun currentReport() = artworkContextReport(BuildConfig.VERSION_NAME, BuildConfig.SOURCE_REVISION,
        state.sources.firstOrNull { it.id == state.selectedSourceId }, state.entries, state.isBusy) +
        "\n\n" + ArtworkDiagnostics.session.report()
    var report by remember { mutableStateOf(currentReport()) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Diagnóstico das capas") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Abra Séries, Filmes e Canais e role cada aba. Volte aqui para comparar. A medição não altera o cache das imagens.")
            ActionButton("Atualizar relatório", { report = currentReport() }, Modifier.fillMaxWidth())
            ActionButton("Copiar relatório", { clipboard.setText(AnnotatedString(report)) }, Modifier.fillMaxWidth())
            ActionButton("Zerar medições", { ArtworkDiagnostics.session.clear(); report = currentReport() }, Modifier.fillMaxWidth())
            Text("Zerar medições apaga só este relatório; mantém as capas e sua conta.", style = MaterialTheme.typography.bodySmall)
            Text(report, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { ActionButton("Concluído", onDismiss) })
}
