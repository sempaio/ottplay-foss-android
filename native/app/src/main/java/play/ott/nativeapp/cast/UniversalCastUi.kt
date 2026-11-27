package play.ott.nativeapp.cast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import play.ott.nativeapp.ui.CinemaPlayerLogic

private val CastCyan = Color(0xFF00E5FF)
private val CastGreen = Color(0xFF2FE68E)
private val CastLiveRed = Color(0xFFFF3B5C)
private val CastSurface = Color(0xFF0E1320)
private val CastCard = Color(0xFF161D2E)
private val CastBorder = Color(0x33FFFFFF)

/**
 * Unified direct-casting dialog (DLNA Smart TVs + Chromecast / Google TV).
 * Streams ONLY the video to the TV in background so the user can freely use their phone or lock the screen.
 */
@Composable
internal fun UniversalCastDialog(
    castState: CastSessionState,
    mediaTitle: String,
    onRescan: () -> Unit,
    onSelectDevice: (CastDevice) -> Unit,
    onDisconnect: () -> Unit,
    onExternalCastApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CastSurface)
                    .border(1.dp, CastBorder, RoundedCornerShape(20.dp))
                    .clickable(enabled = false, onClick = {})
                    .padding(18.dp)
                    .testTag("universal-cast-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "📡 Transmitir para TV",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            mediaTitle.ifBlank { "Samsung • LG • Chromecast • Google TV • Roku • Kodi" },
                            color = Color(0xFF9FB0C7),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CastPillButton(
                            label = if (castState.scanning) "Buscando..." else "🔄 Buscar",
                            highlighted = false,
                            enabled = !castState.scanning,
                            onClick = onRescan,
                        )
                        CastPillButton(label = "✕", highlighted = false, onClick = onDismiss)
                    }
                }

                // Background-usage / Battery optimization banner (prevents Android 30s freeze on minimize!)
                BatteryBackgroundBanner()

                // Busy / error feedback
                castState.busyAction?.let { busy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CastCyan.copy(alpha = 0.14f))
                            .border(1.dp, CastCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(color = CastCyan, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
                        Text(busy, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                castState.errorMessage?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x33FF3B5C))
                            .border(1.dp, CastLiveRed.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(err, color = Color(0xFFFFD6DD), fontSize = 12.sp)
                    }
                }

                // Discovered TVs & Chromecasts on Wi-Fi
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "TVs E CHROMECASTS NO WI-FI",
                        color = CastCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (castState.scanning) {
                        CircularProgressIndicator(color = CastCyan, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                    }
                }

                if (castState.devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CastCard)
                            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Text(
                            if (castState.scanning) {
                                "Procurando TVs Samsung, LG, Chromecast, Google TV, Android TV, Roku, TV Box e Kodi no mesmo Wi-Fi..."
                            } else {
                                castState.scanStatus ?: "Nenhuma TV ou Chromecast encontrado agora. Verifique se celular e TV estão no mesmo Wi-Fi e toque em Buscar."
                            },
                            color = Color(0xFFB7C3D4),
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(castState.devices, key = { it.avTransportControlUrl }) { device ->
                            val isConnected = castState.connectedDevice?.avTransportControlUrl == device.avTransportControlUrl
                            val badge = if (device.protocol == CastProtocol.CHROMECAST) "Chromecast" else "Smart TV"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isConnected) CastCyan.copy(alpha = 0.18f) else CastCard)
                                    .border(
                                        1.dp,
                                        if (isConnected) CastCyan else Color(0x22FFFFFF),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable(enabled = castState.busyAction == null) {
                                        onSelectDevice(device)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(if (device.protocol == CastProtocol.CHROMECAST) "📡" else "📺", fontSize = 20.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.friendlyName,
                                        color = if (isConnected) CastCyan else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val meta = listOf(badge, device.modelName.ifBlank { device.manufacturer }, device.host)
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                        .joinToString(" • ")
                                    Text(
                                        meta,
                                        color = Color(0xFF9AA8BC),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isConnected) {
                                    CastPillButton("Desconectar", highlighted = true, onClick = onDisconnect)
                                } else {
                                    Text(
                                        "Transmitir →",
                                        color = CastCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }

                // Secondary option: external cast app (Web Video Caster / VLC)
                CastFallbackOptionRow(
                    icon = "🎬",
                    title = "Enviar por App Externo (Web Video Caster / VLC)",
                    subtitle = "Opcional: abre o link direto no seu app de transmissão favorito",
                    onClick = onExternalCastApp,
                )
            }
        }
    }
}

/**
 * Full-screen TV Remote Control Overlay shown while actively casting to a Smart TV or Chromecast.
 */
@Composable
internal fun CastingActiveOverlay(
    castState: CastSessionState,
    onTogglePlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onAdjustVolume: (Int) -> Unit,
    onSwitchTv: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onPollPosition: suspend () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val device = castState.connectedDevice ?: return
    val overlayContext = LocalContext.current
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableFloatStateOf(castState.tvPositionMs.toFloat()) }

    // On Android 13+, request POST_NOTIFICATIONS once so the active MediaSession foreground
    // control stays pinned on the lockscreen and prevents OEM background throttling.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(device.avTransportControlUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                overlayContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching { notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    LaunchedEffect(device.avTransportControlUrl, castState.isPlayingOnTv) {
        // Only poll from UI if CastRelayService background poller is not already running,
        // OR gently on VOD (every 5s) so the seek bar stays up to date while the user views this screen.
        while (true) {
            if (!CastRelayService.isServiceActive || !castState.castingIsLive) {
                onPollPosition()
            }
            delay(5_000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070B14), Color(0xFF0E1829), Color(0xFF070B14)),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("casting-active-overlay"),
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastPillButton("← Navegar", highlighted = false, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    if (device.protocol == CastProtocol.CHROMECAST) "📡 Transmitindo via Chromecast" else "📡 Transmitindo na Smart TV",
                    color = CastCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    device.friendlyName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CastPillButton("📺 Trocar TV", highlighted = false, onClick = onSwitchTv)
            CastPillButton("✕ Desconectar", highlighted = true, onClick = onDisconnect)
        }

        // Center Remote Card
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xCC111827))
                .border(1.dp, CastCyan.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (castState.castingIsLive) {
                    Box(
                        Modifier
                            .background(CastLiveRed, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("● AO VIVO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Text(
                    castState.castingTitle.ifBlank { "Reproduzindo na TV" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            BatteryBackgroundBanner()

            // Live DLNA/Cast telemetry pill so connection health is always transparent
            val stats = castState.proxyStats
            val mbSent = stats.bytesSent / (1024.0 * 1024.0)
            val tvStateText = castState.tvTransportState.ifBlank { if (castState.isPlayingOnTv) "PLAYING" else "PAUSED" }
            val telemetry = buildString {
                append(if (device.protocol == CastProtocol.CHROMECAST) "Cast" else "DLNA")
                if (castState.castingDirectMode) {
                    append(" Direto (M81/CDN)")
                }
                append(" • TV: ")
                append(tvStateText)
                if (!castState.castingDirectMode) {
                    append(" • ")
                    append(stats.totalRequests)
                    append(" reqs")
                    if (stats.bytesSent > 0L) {
                        append(" • ")
                        append("%.1f MB".format(mbSent))
                    }
                }
                if (castState.autoReconnectCount > 0) {
                    append(" • ")
                    append(castState.autoReconnectCount)
                    append(" rec.")
                }
            }
            val subDiag = if (castState.castingDirectMode) {
                "Sem uso do celular — pode fechar ou bloquear a tela"
            } else {
                buildString {
                    if (stats.bindAddress.isNotBlank()) append(stats.bindAddress)
                    if (stats.lastHttpSummary.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(stats.lastHttpSummary)
                    }
                }
            }
            Text(
                castState.busyAction ?: telemetry,
                color = Color(0xFF98B0CC),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subDiag.isNotBlank()) {
                Text(
                    subDiag,
                    color = Color(0xFF6F849E),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // VOD Seek Bar on TV
            if (!castState.castingIsLive && castState.tvDurationMs > 0L) {
                val shownPos = if (scrubbing) scrubMs.toLong() else castState.tvPositionMs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        CinemaPlayerLogic.formatTimeMs(shownPos),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Slider(
                        value = shownPos.toFloat().coerceIn(0f, castState.tvDurationMs.toFloat()),
                        onValueChange = {
                            scrubbing = true
                            scrubMs = it
                        },
                        onValueChangeFinished = {
                            scrubbing = false
                            onSeekTo(scrubMs.toLong())
                        },
                        valueRange = 0f..castState.tvDurationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = CastCyan,
                            activeTrackColor = CastCyan,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.weight(1f).height(26.dp),
                    )
                    Text(
                        CinemaPlayerLogic.formatTimeMs(castState.tvDurationMs),
                        color = Color(0xFFB8C6D9),
                        fontSize = 12.sp,
                    )
                }
            }

            // Main Transport Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (castState.castingIsLive && onPrevious != null) {
                    CastRoundControl("⏮", onClick = onPrevious)
                } else if (!castState.castingIsLive) {
                    CastRoundControl("-15s") { onSeekRelative(-15_000L) }
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(CastCyan.copy(alpha = 0.18f))
                        .border(2.dp, CastCyan, CircleShape)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (castState.isPlayingOnTv) "⏸" else "▶",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (castState.castingIsLive && onNext != null) {
                    CastRoundControl("⏭", onClick = onNext)
                } else if (!castState.castingIsLive) {
                    CastRoundControl("+15s") { onSeekRelative(15_000L) }
                }
            }

            // TV Volume Controls
            if (device.renderingControlUrl != null || device.protocol == CastProtocol.CHROMECAST) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CastPillButton("🔉 Vol -", highlighted = false) { onAdjustVolume(-8) }
                    Text(
                        "Volume TV: ${castState.tvVolume}%",
                        color = Color(0xFFD3DFEE),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CastPillButton("🔊 Vol +", highlighted = false) { onAdjustVolume(8) }
                }
            }

            // Quick Bottom Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastPillButton("🔄 Reconectar", highlighted = false, onClick = onReconnect)
                CastPillButton("🧭 Navegar", highlighted = false, onClick = onBack)
                CastPillButton("📲 Celular", highlighted = true, onClick = onDisconnect)
            }
        }
    }
}

@Composable
private fun BatteryBackgroundBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var unrestricted by remember { mutableStateOf(CastPowerPolicy.isBatteryUnrestricted(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = CastPowerPolicy.isBatteryUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unrestricted) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CastGreen.copy(alpha = 0.12f))
                .border(1.dp, CastGreen.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            Text(
                "✓ 2º plano liberado — pode minimizar o app ou bloquear a tela sem travar a TV.",
                color = Color(0xFFCFFCE5),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33FFB300))
                .border(1.dp, Color(0xAAFFB300), RoundedCornerShape(10.dp))
                .clickable { CastPowerPolicy.requestUnrestrictedBattery(context) }
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⚡", fontSize = 15.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    "Liberar 2º plano (toque aqui)",
                    color = Color(0xFFFFE599),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Evita que o Android trave a TV ~30s depois de minimizar o app.",
                    color = Color(0xFFFFF0C2),
                    fontSize = 10.sp,
                )
            }
            Text("Liberar ›", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CastFallbackOptionRow(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CastCard)
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(icon, fontSize = 19.sp)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF97A7BD), fontSize = 11.sp, maxLines = 2)
        }
        Text("›", color = CastCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CastPillButton(
    label: String,
    highlighted: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) CastCyan.copy(alpha = 0.22f) else Color(0xFF1A2234))
            .border(
                1.dp,
                if (highlighted) CastCyan else CastBorder,
                RoundedCornerShape(16.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (!enabled) Color(0xFF6B778C) else if (highlighted) CastCyan else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CastRoundControl(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF1A2234))
            .border(1.dp, CastBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
