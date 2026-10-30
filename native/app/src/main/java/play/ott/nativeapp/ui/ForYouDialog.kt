package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import play.ott.nativeapp.data.ForYouProfile

/** Optional, local-first wizard. Neither answers nor viewing signals leave the device. */
@Composable
internal fun ForYouDialog(
    profile: ForYouProfile,
    onSave: (Set<String>, Set<String>, Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    var page by remember(profile.id) { mutableStateOf(0) }
    var topics by remember(profile.id) { mutableStateOf(profile.interests) }
    var learning by remember(profile.id) { mutableStateOf(profile.allowViewingSignals) }
    var dislikes by remember(profile.id) { mutableStateOf(profile.dislikedInterests) }
    val steps = listOf(
        listOf("futebol", "gospel", "filmes", "series", "desenhos", "animes", "novelas", "documentarios"),
        listOf("comedia", "acao", "romance", "infantil", "esportes", "natureza", "ficcao cientifica")
    )
    val names = mapOf(
        "futebol" to "Futebol", "gospel" to "Gospel", "filmes" to "Filmes",
        "series" to "Séries", "desenhos" to "Desenhos", "animes" to "Animes",
        "novelas" to "Novelas", "documentarios" to "Documentários",
        "comedia" to "Comédia", "acao" to "Ação", "romance" to "Romance",
        "infantil" to "Infantil", "esportes" to "Esportes",
        "natureza" to "Natureza", "ficcao cientifica" to "Ficção científica"
    )
    AlertDialog(
        onDismissRequest = onSkip,
        modifier = Modifier.tvRemoteInput().testTag("for-you-onboarding"),
        title = { Text(if (page == 0) "O que você gosta de assistir?" else "Quais gêneros você prefere?") },
        text = {
            Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Escolha quantas opções quiser. Você poderá mudar depois.", style = MaterialTheme.typography.bodySmall)
                steps[page].chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { topic ->
                            FilterChip(
                                selected = topic in topics,
                                onClick = { topics = if (topic in topics) topics - topic else topics + topic },
                                label = { Text(names[topic] ?: topic) },
                                modifier = Modifier.weight(1f).testTag("topic-$topic")
                            )
                        }
                    }
                }
                if (page == 1) {
                    Text("Quero menos recomendações de:", style = MaterialTheme.typography.titleSmall)
                    listOf("futebol", "gospel", "filmes", "series", "desenhos", "animes", "novelas", "documentarios").chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { topic ->
                                FilterChip(
                                    selected = topic in dislikes,
                                    onClick = {
                                        dislikes = if (topic in dislikes) dislikes - topic else dislikes + topic
                                        if (topic in dislikes) topics -= topic
                                    },
                                    label = { Text(names[topic] ?: topic) },
                                    modifier = Modifier.weight(1f).testTag("negative-topic-$topic")
                                )
                            }
                        }
                    }
                    Text("Aprendizado opcional", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Considerar o que continuo assistindo")
                            Text("Somente neste dispositivo. Desativado inicialmente.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = learning, onCheckedChange = { learning = it }, modifier = Modifier.testTag("for-you-consent"))
                    }
                }
                Text("A escolha de categorias não será usada para deduzir religião, idade ou características pessoais.", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Pular") } },
        confirmButton = {
            TextButton(onClick = { if (page == 0) page = 1 else onSave(topics - dislikes, dislikes, learning) },
                modifier = Modifier.testTag("for-you-next")) {
                Text(if (page == 0) "Continuar" else "Concluir")
            }
        }
    )
}
