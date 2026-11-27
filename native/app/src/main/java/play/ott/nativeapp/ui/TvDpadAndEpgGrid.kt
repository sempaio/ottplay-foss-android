package play.ott.nativeapp.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.Programme
import java.text.SimpleDateFormat
import java.util.Date

private val EpgCyan = Color(0xFF00E5FF)
private val EpgLiveRed = Color(0xFFFF3B5C)
private val EpgSurface = Color(0xFF0E121B)
private val EpgCardBg = Color(0xFF171D2B)
private val EpgCardLiveBg = Color(0xFF152538)
private val EpgBorder = Color(0x2EFFFFFF)

internal enum class TvPlayerRemoteAction {
    NONE,
    NEXT_CHANNEL,
    PREVIOUS_CHANNEL,
    SEEK_FORWARD_10S,
    SEEK_BACK_10S,
    TOGGLE_PLAY_PAUSE,
    REVEAL_HUD,
    OPEN_EPG,
    TOGGLE_DRAWER,
}

internal enum class EpgWindowFilter(val label: String) {
    NOW_AND_NEXT("Agora e próximos"),
    TODAY("Hoje (24h)"),
    ALL("Grade completa"),
}

internal object TvDpadPolicy {
    /**
     * Maps Android TV / Fire TV remote keys during fullscreen playback.
     * When the HUD controls or side drawer are already visible, directional D-Pad keys
     * pass through to Compose focus traversal so the user can move between HUD buttons.
     */
    fun mapPlayerKey(
        keyCode: Int,
        isLive: Boolean,
        controlsVisible: Boolean,
        drawerOpen: Boolean,
        locked: Boolean,
    ): TvPlayerRemoteAction {
        if (locked) {
            return if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                TvPlayerRemoteAction.REVEAL_HUD
            } else {
                TvPlayerRemoteAction.NONE
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> if (isLive) TvPlayerRemoteAction.NEXT_CHANNEL else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_CHANNEL_DOWN -> if (isLive) TvPlayerRemoteAction.PREVIOUS_CHANNEL else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_TV_DATA_SERVICE,
            KeyEvent.KEYCODE_INFO -> if (isLive) TvPlayerRemoteAction.OPEN_EPG else TvPlayerRemoteAction.REVEAL_HUD
            KeyEvent.KEYCODE_MENU -> TvPlayerRemoteAction.TOGGLE_DRAWER
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> TvPlayerRemoteAction.TOGGLE_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!isLive) TvPlayerRemoteAction.SEEK_FORWARD_10S else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (!isLive) TvPlayerRemoteAction.SEEK_BACK_10S else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_DPAD_UP -> if (!controlsVisible && !drawerOpen && isLive) TvPlayerRemoteAction.NEXT_CHANNEL else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!controlsVisible && !drawerOpen && isLive) TvPlayerRemoteAction.PREVIOUS_CHANNEL else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsVisible && !drawerOpen && !isLive) TvPlayerRemoteAction.SEEK_FORWARD_10S else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!controlsVisible && !drawerOpen && !isLive) TvPlayerRemoteAction.SEEK_BACK_10S else TvPlayerRemoteAction.NONE
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> if (!controlsVisible && !drawerOpen) TvPlayerRemoteAction.REVEAL_HUD else TvPlayerRemoteAction.NONE
            else -> TvPlayerRemoteAction.NONE
        }
    }
}

internal object EpgGridLogic {
    fun progressFraction(programme: Programme, nowMs: Long): Float {
        val duration = programme.endMillis - programme.startMillis
        if (duration <= 0L) return 0f
        if (nowMs <= programme.startMillis) return 0f
        if (nowMs >= programme.endMillis) return 1f
        return ((nowMs - programme.startMillis).toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun remainingMinutes(programme: Programme, nowMs: Long): Int {
        if (nowMs >= programme.endMillis) return 0
        val remMs = (programme.endMillis - nowMs).coerceAtLeast(0L)
        return ((remMs + 59_999L) / 60_000L).toInt()
    }

    fun filterProgrammes(
        programmes: List<Programme>,
        window: EpgWindowFilter,
        nowMs: Long,
    ): List<Programme> {
        val sorted = programmes.sortedBy { it.startMillis }
        return when (window) {
            EpgWindowFilter.ALL -> sorted
            EpgWindowFilter.NOW_AND_NEXT -> {
                val upcoming = sorted.filter { it.endMillis > nowMs }
                upcoming.take(12).ifEmpty { sorted.takeLast(6) }
            }
            EpgWindowFilter.TODAY -> {
                val endWindow = nowMs + 24 * 3_600_000L
                val recentCutoff = nowMs - 2 * 3_600_000L
                sorted.filter { it.endMillis >= recentCutoff && it.startMillis <= endWindow }
                    .ifEmpty { sorted.take(24) }
            }
        }
    }

    fun adjacentChannel(channels: List<MediaEntry>, currentId: String, delta: Int): MediaEntry? {
        if (channels.size <= 1) return null
        val idx = channels.indexOfFirst { it.id == currentId }
        if (idx < 0) return channels.firstOrNull()
        val next = (idx + delta).mod(channels.size)
        return channels[next]
    }
}

/**
 * Adds a high-contrast Android TV / D-Pad focus highlight (cyan border + subtle scale)
 * without affecting touch layouts on phones.
 */
internal fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    focusedColor: Color = EpgCyan,
    scaleOnFocus: Float = 1.03f,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .scale(if (focused) scaleOnFocus else 1f)
        .border(
            width = if (focused) 2.5.dp else 0.dp,
            color = if (focused) focusedColor else Color.Transparent,
            shape = shape,
        )
}

/**
 * Responsive TV Guide / Grade EPG with D-Pad channel rail (landscape/TV) + timeline cards,
 * live progress indicator, remaining minutes, window filters, and instant channel switching.
 */
@Composable
internal fun EpgGridGuideDialog(
    entry: MediaEntry,
    liveChannels: List<MediaEntry>,
    programmes: List<Programme>,
    loading: Boolean,
    onAction: (AppAction) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowFilter by remember(entry.id) { mutableStateOf(EpgWindowFilter.NOW_AND_NEXT) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val config = LocalConfiguration.current
    val isWideLayout = config.screenWidthDp >= 640
    val locale = config.locales[0]
    val startFormat = stringResource(R.string.dialog_programme_start_format)
    val endFormat = stringResource(R.string.dialog_programme_end_format)
    val formatter = remember(locale, startFormat) { SimpleDateFormat(startFormat, locale) }
    val timeFormatter = remember(locale, endFormat) { SimpleDateFormat(endFormat, locale) }

    val filtered = remember(programmes, windowFilter, now / 60_000L) {
        EpgGridLogic.filterProgrammes(programmes, windowFilter, now)
    }
    val initialIndex = remember(entry.id, filtered) {
        filtered.indexOfFirst { it.endMillis > System.currentTimeMillis() }.coerceAtLeast(0)
    }
    val guideListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(entry.id, windowFilter, filtered.size) {
        if (filtered.isNotEmpty()) guideListState.scrollToItem(initialIndex)
    }

    val channelIndex = remember(liveChannels, entry.id) {
        liveChannels.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    }
    val channelRailState = rememberLazyListState(initialFirstVisibleItemIndex = channelIndex)
    LaunchedEffect(entry.id) {
        if (liveChannels.isNotEmpty()) channelRailState.scrollToItem(channelIndex)
    }

    Dialog(
        onDismissRequest = { onAction(AppAction.CloseEpg) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .tvRemoteInput()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_CHANNEL_UP -> {
                            EpgGridLogic.adjacentChannel(liveChannels, entry.id, +1)?.let {
                                onAction(AppAction.OpenEpg(it))
                            }
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            EpgGridLogic.adjacentChannel(liveChannels, entry.id, -1)?.let {
                                onAction(AppAction.OpenEpg(it))
                            }
                            true
                        }
                        else -> false
                    }
                }
                .fillMaxWidth(if (isWideLayout) 0.94f else 0.96f)
                .widthIn(max = 1040.dp)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(22.dp),
            color = EpgSurface,
            border = BorderStroke(1.dp, EpgBorder),
        ) {
            Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Left Channel Rail on TV / Landscape
                if (isWideLayout && liveChannels.size > 1) {
                    Column(
                        modifier = Modifier
                            .width(240.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF121722))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "CANAIS (${liveChannels.size})",
                            color = EpgCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        LazyColumn(
                            state = channelRailState,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(liveChannels, key = { it.id }) { ch ->
                                val selected = ch.id == entry.id
                                val shape = RoundedCornerShape(10.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusRing(shape = shape, scaleOnFocus = 1.02f)
                                        .clip(shape)
                                        .background(if (selected) EpgCyan.copy(alpha = 0.2f) else EpgCardBg)
                                        .border(
                                            1.dp,
                                            if (selected) EpgCyan else Color.Transparent,
                                            shape,
                                        )
                                        .clickable {
                                            if (selected) {
                                                onAction(AppAction.Play(ch))
                                                onAction(AppAction.CloseEpg)
                                            } else {
                                                onAction(AppAction.OpenEpg(ch))
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                ) {
                                    Column {
                                        Text(
                                            text = ch.name,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (ch.group.isNotBlank()) {
                                            Text(
                                                text = ch.group,
                                                color = Color(0xFF94A3B8),
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Right / Main EPG Timeline Panel
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "GRADE DE PROGRAMAÇÃO (EPG)",
                                color = EpgCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = entry.name,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.group.isNotBlank()) {
                                Text(
                                    text = entry.group,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (liveChannels.size > 1) {
                            EpgChipButton("◀ Ant.", selected = false) {
                                EpgGridLogic.adjacentChannel(liveChannels, entry.id, -1)?.let {
                                    onAction(AppAction.OpenEpg(it))
                                }
                            }
                            EpgChipButton("Próx. ▶", selected = false) {
                                EpgGridLogic.adjacentChannel(liveChannels, entry.id, +1)?.let {
                                    onAction(AppAction.OpenEpg(it))
                                }
                            }
                        }
                        EpgChipButton(stringResource(R.string.dialog_close), selected = false) {
                            onAction(AppAction.CloseEpg)
                        }
                    }

                    // Primary Watch Live + Window Filters Row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            EpgChipButton("▶ Assistir ao vivo", selected = true) {
                                onAction(AppAction.Play(entry))
                                onAction(AppAction.CloseEpg)
                            }
                        }
                        items(EpgWindowFilter.entries) { filter ->
                            EpgChipButton(filter.label, selected = filter == windowFilter) {
                                windowFilter = filter
                            }
                        }
                    }

                    if (loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = EpgCyan,
                            trackColor = Color(0xFF1F2937),
                        )
                    }

                    if (filtered.isEmpty() && !loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(EpgCardBg)
                                .padding(20.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.dialog_guide_empty),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.dialog_guide_empty_hint),
                                    color = Color(0xFF94A3B8),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    LazyColumn(
                        state = guideListState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filtered) { programme ->
                            val live = now >= programme.startMillis && now < programme.endMillis
                            val past = programme.endMillis <= now
                            val catchup = entry.catchup
                            val inArchiveWindow = catchup != null && catchup.days > 0 &&
                                programme.startMillis >= now - (catchup.days * 86_400_000L)
                            val replayable = past && inArchiveWindow
                            val progress = if (live) EpgGridLogic.progressFraction(programme, now) else 0f
                            val remMin = if (live) EpgGridLogic.remainingMinutes(programme, now) else 0
                            val shape = RoundedCornerShape(14.dp)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusRing(shape = shape, scaleOnFocus = 1.015f)
                                    .clip(shape)
                                    .background(if (live) EpgCardLiveBg else EpgCardBg)
                                    .border(
                                        1.dp,
                                        if (live) EpgCyan.copy(alpha = 0.65f) else EpgBorder,
                                        shape,
                                    )
                                    .focusable()
                                    .clickable(enabled = live || replayable) {
                                        if (live) onAction(AppAction.Play(entry))
                                        else onAction(AppAction.PlayProgramme(entry, programme))
                                        onAction(AppAction.CloseEpg)
                                    }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (live) EpgLiveRed else Color(0xFF283244))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = when {
                                                    live -> "AO VIVO"
                                                    replayable -> "ARQUIVO"
                                                    past -> "ENCERRADO"
                                                    else -> "A SEGUIR"
                                                },
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Text(
                                            text = stringResource(
                                                R.string.dialog_programme_range,
                                                formatter.format(Date(programme.startMillis)),
                                                timeFormatter.format(Date(programme.endMillis)),
                                            ),
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    if (live && remMin > 0) {
                                        Text(
                                            text = "Restam $remMin min",
                                            color = EpgCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                Text(
                                    text = programme.title,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                if (live) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = EpgCyan,
                                        trackColor = Color(0xFF243447),
                                    )
                                }

                                if (programme.description.isNotBlank()) {
                                    Text(
                                        text = programme.description,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpgChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .tvFocusRing(shape = shape, scaleOnFocus = 1.04f)
            .clip(shape)
            .background(if (selected) EpgCyan else Color(0xFF1F2937))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF061018) else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
