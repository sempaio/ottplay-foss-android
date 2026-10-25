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

@Composable
internal fun ArtworkDiagnosticsDialog(onDismiss: () -> Unit) {
    var report by remember { mutableStateOf(ArtworkDiagnostics.session.report()) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Diagnóstico das capas") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Abra Séries, Filmes e Canais e role cada aba. Volte aqui para comparar. A medição não altera o cache das imagens.")
            ActionButton("Atualizar relatório", { report = ArtworkDiagnostics.session.report() }, Modifier.fillMaxWidth())
            ActionButton("Copiar relatório", { clipboard.setText(AnnotatedString(report)) }, Modifier.fillMaxWidth())
            ActionButton("Zerar medições", { ArtworkDiagnostics.session.clear(); report = ArtworkDiagnostics.session.report() }, Modifier.fillMaxWidth())
            Text("Zerar medições apaga só este relatório; mantém as capas e sua conta.", style = MaterialTheme.typography.bodySmall)
            Text(report, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { ActionButton("Concluído", onDismiss) })
}
