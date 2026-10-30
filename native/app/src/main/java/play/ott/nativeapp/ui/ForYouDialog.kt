package play.ott.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import play.ott.nativeapp.data.ForYouEngine
import play.ott.nativeapp.data.ForYouProfile

/** Optional, local-only experience built to match the owner's black two-column reference. */
@Composable
internal fun ForYouDialog(
    profile: ForYouProfile,
    onSave: (Set<String>, Set<String>, Set<String>, Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    var page by remember(profile.id) { mutableStateOf(0) }
    var topics by remember(profile.id) { mutableStateOf(profile.interests) }
    var subtopics by remember(profile.id) { mutableStateOf(profile.subInterests) }
    var dislikes by remember(profile.id) { mutableStateOf(profile.dislikedInterests) }
    var learning by remember(profile.id) { mutableStateOf(profile.allowViewingSignals) }
    val primary = listOf("futebol", "gospel", "desenhos", "filmes", "series", "documentarios", "animes", "novelas")
    val secondary = ForYouEngine.topics.filterNot { it in primary }
    val labels = mapOf(
        "futebol" to "Futebol", "gospel" to "Gospel", "desenhos" to "Desenhos", "filmes" to "Filmes",
        "series" to "Séries", "documentarios" to "Documentários", "animes" to "Animes", "novelas" to "Novelas",
        "comedia" to "Comédia", "acao" to "Ação", "romance" to "Romance",
        "infantil" to "Infantil", "esportes" to "Esportes", "natureza" to "Natureza",
        "ficcao cientifica" to "Ficção científica"
    )
    val emojis = mapOf(
        "futebol" to "⚽", "gospel" to "🎵", "desenhos" to "🎨", "filmes" to "🎬",
        "series" to "📺", "documentarios" to "🌍", "animes" to "✨", "novelas" to "❤️",
        "comedia" to "😄", "acao" to "⚡", "romance" to "💗", "infantil" to "🧸",
        "esportes" to "🏅", "natureza" to "🌿", "ficcao cientifica" to "🚀"
    )

    Dialog(onDismissRequest = onSkip, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.90f).widthIn(max = 600.dp)
                .testTag("for-you-onboarding").tvRemoteInput(),
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF070809),
            border = BorderStroke(1.dp, Color(0xFF323336)),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "STARLINK PARA VOCÊ   ·   " + (page + 1) + "/3",
                    color = Color(0xFF8DDCEB), style = MaterialTheme.typography.labelMedium
                )
                Text(
                    when (page) {
                        0 -> "O que você mais gosta de assistir?"
                        1 -> "O que você prefere dentro de cada assunto?"
                        else -> "Deixe a experiência do seu jeito"
                    },
                    color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                Text(
                    when (page) {
                        0 -> "Escolha seus interesses. Você pode mudar tudo depois."
                        1 -> "Escolha os tipos que mais gosta. É opcional."
                        else -> "Refine seus gostos e decida se quer usar seu histórico local."
                    },
                    color = Color(0xFFADB0B8), style = MaterialTheme.typography.bodyMedium
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (page) {
                        0 -> PreferenceGrid(primary, labels, emojis, topics, "topic") { topic ->
                            if (topic in topics) {
                                topics -= topic
                                subtopics = subtopics.filterNot { it.startsWith(topic + ":") }.toSet()
                            } else {
                                topics += topic
                                dislikes -= topic
                            }
                        }
                        1 -> {
                            val selected = primary.filter { it in topics && ForYouEngine.subtopics[it].orEmpty().isNotEmpty() }
                            if (selected.isEmpty()) {
                                Text("Você pode continuar sem escolher assuntos específicos.",
                                    color = Color(0xFFBDC1C9), style = MaterialTheme.typography.bodyLarge)
                            } else {
                                selected.forEach { parent ->
                                    Text(labels[parent] ?: parent, color = Color.White,
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    val choices = ForYouEngine.subtopics[parent].orEmpty()
                                    PreferenceGrid(
                                        choices.map { parent + ":" + it.first },
                                        choices.associate { (id, label) -> (parent + ":" + id) to label },
                                        choices.associate { (id, _) -> (parent + ":" + id) to (emojis[parent] ?: "✨") },
                                        subtopics, "subtopic"
                                    ) { value ->
                                        subtopics = if (value in subtopics) subtopics - value else subtopics + value
                                    }
                                }
                            }
                        }
                        else -> {
                            Text("Outros gêneros", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            PreferenceGrid(secondary, labels, emojis, topics, "genre") { topic ->
                                if (topic in topics) topics -= topic else { topics += topic; dislikes -= topic }
                            }
                            Text("Quero ver menos de", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("Essas escolhas não escondem os conteúdos do catálogo.",
                                color = Color(0xFFADB0B8), style = MaterialTheme.typography.bodySmall)
                            PreferenceGrid(primary, labels, emojis, dislikes, "less") { topic ->
                                if (topic in dislikes) dislikes -= topic
                                else {
                                    dislikes += topic
                                    topics -= topic
                                    subtopics = subtopics.filterNot { it.startsWith(topic + ":") }.toSet()
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Aprender com minha reprodução", color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("Opcional e somente neste aparelho. Desligado inicialmente.",
                                        color = Color(0xFFADB0B8), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = learning, onCheckedChange = { learning = it },
                                    modifier = Modifier.testTag("for-you-consent"))
                            }
                            Text("Nenhuma escolha será usada para deduzir religião, idade ou outros atributos pessoais.",
                                color = Color(0xFF9EA1A9), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Button(
                    onClick = {
                        if (page < 2) page++
                        else onSave(topics - dislikes,
                            subtopics.filter { it.substringBefore(":") in topics && it in ForYouEngine.validSubInterests }.toSet(),
                            dislikes, learning)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("for-you-next"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Text(if (page == 2) "Criar minha experiência" else "Continuar",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (page > 0) {
                        TextButton(onClick = { page-- }) { Text("Voltar", color = Color(0xFFBDC1C9)) }
                    } else Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSkip) { Text("Pular", color = Color(0xFFBDC1C9)) }
                }
            }
        }
    }
}

@Composable
private fun PreferenceGrid(
    keys: List<String>,
    labels: Map<String, String>,
    emojis: Map<String, String>,
    selected: Set<String>,
    tagPrefix: String,
    onToggle: (String) -> Unit,
) {
    keys.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { key ->
                Card(
                    onClick = { onToggle(key) },
                    modifier = Modifier.weight(1f).heightIn(min = 124.dp).testTag(tagPrefix + "-" + key),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor =
                        if (key in selected) Color(0xFF29292B) else Color(0xFF070809)),
                    border = BorderStroke(1.5.dp,
                        if (key in selected) Color(0xFF6D6F74) else Color(0xFF313236))
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(emojis[key] ?: "✨", style = MaterialTheme.typography.headlineMedium)
                            Text(if (key in selected) "✓" else "○",
                                color = if (key in selected) Color.White else Color(0xFF7B7C82),
                                style = MaterialTheme.typography.titleLarge)
                        }
                        Text(labels[key] ?: key, color = Color.White,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
