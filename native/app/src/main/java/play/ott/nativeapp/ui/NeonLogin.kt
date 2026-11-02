package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun NeonSourceDialog(simple: Boolean, modifier: Modifier, onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit, dismissButton: @Composable () -> Unit, confirmButton: @Composable () -> Unit) {
    if (!simple) {
        AlertDialog(modifier = modifier, onDismissRequest = onDismissRequest, title = title, text = text, dismissButton = dismissButton, confirmButton = confirmButton)
        return
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    NeonEmblem(Modifier.size(90.dp))
                    title()
                    text()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { dismissButton(); confirmButton() }
                    SupportButton()
                }
            }
        }
    }
}
