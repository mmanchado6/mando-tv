package local.mandotv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.mandotv.data.Shortcut
import local.mandotv.protocol.TvKey
import local.mandotv.remote.ConnectionState
import local.mandotv.remote.TvStatus
import kotlin.math.min

enum class RemoteTab(val title: String) { MANDO("Mando"), MEDIA("Multimedia"), NUMEROS("123"), APPS("Apps") }

/** Lo que necesita la pantalla del mando (separado del ViewModel para poder previsualizarla y probarla). */
class RemoteUi(
    val tvName: String,
    val state: ConnectionState,
    val status: TvStatus,
    val shortcuts: List<Shortcut>,
    val iconFor: (Shortcut) -> ImageBitmap?,
    /** Cambia cuando hay que volver a pedir los logos. */
    val iconsVersion: Int = 0,
    val onRetry: () -> Unit,
    val onRepair: () -> Unit,
    val onDevices: () -> Unit,
    val onSettings: () -> Unit,
    val onShortcut: (Shortcut) -> Unit,
    val onEditShortcuts: () -> Unit,
    val onOpenKeyboard: () -> Unit,
)

/**
 * Escala de una pestaña según el espacio disponible: así todo cabe sin scroll, desde móviles
 * pequeños hasta grandes (con un tamaño máximo para que no quede gigante en tablets).
 */
private fun scale(maxW: Dp, maxH: Dp, designW: Float, designH: Float): Float =
    min(maxW.value / designW, maxH.value / designH).coerceIn(0.6f, 1.2f)

@Composable
fun RemoteScreen(ui: RemoteUi, initialTab: RemoteTab = RemoteTab.MANDO) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        TopBar(ui)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Cuerpo del mando
            Box(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1B1E24), Color(0xFF121418))))
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))),
                        RoundedCornerShape(34.dp),
                    ),
            ) {
                when (tab) {
                    RemoteTab.MANDO -> MainPad(ui.status)
                    RemoteTab.MEDIA -> MediaPad(ui.status)
                    RemoteTab.NUMEROS -> NumberPad()
                    RemoteTab.APPS -> AppsPad(ui.shortcuts, ui.iconFor, ui.iconsVersion, ui.onShortcut, ui.onEditShortcuts)
                }
            }
        }
        BottomTabs(tab) { tab = it }
    }
}

// ---------------------------------------------------------------- Barra superior

@Composable
private fun TopBar(ui: RemoteUi) {
    val (color, text) = statusLook(ui.state)
    val dot by animateColorAsState(color, label = "estado")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Palette.key),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tv, null, tint = Palette.keyText, modifier = Modifier.size(22.dp))
                Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Palette.background).padding(2.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.tvName, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, color = dot, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = ui.onOpenKeyboard) { Icon(Icons.Filled.Keyboard, "Abrir teclado") }
            IconButton(onClick = ui.onDevices) { Icon(Icons.Filled.Tv, "Elegir tele") }
            IconButton(onClick = ui.onSettings) { Icon(Icons.Filled.Settings, "Ajustes") }
        }
        StatusBanner(ui)
    }
}

@Composable
private fun StatusBanner(ui: RemoteUi) {
    val s = ui.state
    val (msg, action, onAction) = when (s) {
        is ConnectionState.Waiting -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(s) { while (true) { now = System.currentTimeMillis(); delay(250) } }
            val secs = ((s.retryAtMillis - now + 999) / 1000).coerceAtLeast(0)
            Triple("${s.reason}. Reintento en $secs s", "Reintentar", ui.onRetry)
        }
        ConnectionState.NeedsPairing -> Triple("La tele ya no reconoce este móvil.", "Emparejar", ui.onRepair)
        ConnectionState.CertificateChanged ->
            Triple("La tele presenta otro certificado (¿se reseteó?). Por seguridad no se conecta.", "Emparejar", ui.onRepair)
        ConnectionState.NoWifi -> Triple("Conéctate a la WiFi de casa.", null, {})
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Palette.key).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) {
            Button(onClick = onAction, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(action) }
        }
    }
}

fun statusLook(state: ConnectionState): Pair<Color, String> = when (state) {
    is ConnectionState.Connected -> Palette.ok to "Conectado"
    is ConnectionState.Connecting -> Palette.warn to "Conectando…"
    is ConnectionState.Waiting -> Palette.warn to "Reconectando"
    ConnectionState.NoWifi -> Palette.idle to "Sin WiFi"
    ConnectionState.Paused -> Palette.idle to "En pausa"
    ConnectionState.NoTv -> Palette.idle to "Sin tele"
    ConnectionState.NeedsPairing, ConnectionState.CertificateChanged -> Palette.accent to "Hay que emparejar"
}

// ---------------------------------------------------------------- Pestañas

@Composable
private fun BottomTabs(current: RemoteTab, onSelect: (RemoteTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RemoteTab.entries.forEach { t ->
            val isSel = t == current
            val bg by animateColorAsState(if (isSel) Palette.key else Color.Transparent, label = "pestaña")
            val ink by animateColorAsState(if (isSel) Palette.accent else Palette.keySubtle, label = "tinta")
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(bg)
                    .semantics { selected = isSel }
                    .clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    when (t) {
                        RemoteTab.MANDO -> Icons.Filled.SettingsRemote
                        RemoteTab.MEDIA -> Icons.Filled.SmartDisplay
                        RemoteTab.NUMEROS -> Icons.Filled.Dialpad
                        RemoteTab.APPS -> Icons.Filled.Apps
                    },
                    null, tint = ink, modifier = Modifier.size(22.dp),
                )
                Text(t.title, fontSize = 11.sp, color = ink, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- Pestaña «Mando»

@Composable
private fun MainPad(status: TvStatus) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = scale(maxWidth, maxHeight, 370f, 560f)
        val key = 58.dp * u
        Column(
            Modifier.fillMaxSize().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(TvKey.POWER, "Encender o apagar", icon = Icons.Filled.PowerSettingsNew, size = key, color = Palette.power, caption = "Encender")
                // «Fuente»: en tu Sony solo funciona la tecla TV (volver a la tele / antena)
                KeyButton(TvKey.TV, "TV", icon = Icons.Filled.LiveTv, size = key, caption = "TV")
                KeyButton(
                    TvKey.MUTE, "Silencio", icon = Icons.AutoMirrored.Filled.VolumeOff, size = key,
                    caption = if (status.muted) "En silencio" else "Silencio", active = status.muted,
                )
            }
            DPad(diameter = 226.dp * u)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(TvKey.BACK, "Atrás", icon = Icons.AutoMirrored.Filled.ArrowBack, size = 56.dp * u, caption = "Atrás")
                KeyButton(TvKey.PLAY_PAUSE, "Play o pausa", icon = Icons.Filled.PlayArrow, size = 56.dp * u, caption = "Play/Pausa")
                KeyButton(TvKey.HOME, "Inicio", icon = Icons.Filled.Home, size = 56.dp * u, caption = "Home")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Rocker("VOL", TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, "Subir volumen", "Bajar volumen", width = 70.dp * u, height = 150.dp * u)
                VolumeRing(status.volume, status.volumeMax, status.muted, size = 92.dp * u)
                Rocker(
                    "CH", TvKey.CHANNEL_UP, TvKey.CHANNEL_DOWN, "Canal siguiente", "Canal anterior",
                    upIcon = Icons.Filled.KeyboardArrowUp, downIcon = Icons.Filled.KeyboardArrowDown,
                    width = 70.dp * u, height = 150.dp * u,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Pestaña «Multimedia»

@Composable
private fun MediaPad(status: TvStatus) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = scale(maxWidth, maxHeight, 360f, 540f)
        val k = 66.dp * u
        Column(
            Modifier.fillMaxSize().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KeyButton(TvKey.PLAY_PAUSE, "Play o pausa", icon = Icons.Filled.PlayArrow, size = 104.dp * u, caption = "Play / Pausa", color = Color(0xFF34507F))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(TvKey.REWIND, "Retroceder", icon = Icons.Filled.FastRewind, size = k, caption = "Retroceder")
                KeyButton(TvKey.STOP, "Stop", icon = Icons.Filled.Stop, size = k, caption = "Stop")
                KeyButton(TvKey.FAST_FORWARD, "Avanzar", icon = Icons.Filled.FastForward, size = k, caption = "Avanzar")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(TvKey.PREVIOUS, "Anterior", icon = Icons.Filled.SkipPrevious, size = k, caption = "Anterior")
                KeyButton(TvKey.NEXT, "Siguiente", icon = Icons.Filled.SkipNext, size = k, caption = "Siguiente")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Rocker("VOL", TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, "Subir volumen", "Bajar volumen", width = 66.dp * u, height = 138.dp * u)
                VolumeRing(status.volume, status.volumeMax, status.muted, size = 80.dp * u)
                KeyButton(
                    TvKey.MUTE, "Silencio", icon = Icons.AutoMirrored.Filled.VolumeOff, size = 58.dp * u,
                    caption = if (status.muted) "En silencio" else "Silencio", active = status.muted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Pestaña «123»

@Composable
private fun NumberPad() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = scale(maxWidth, maxHeight, 340f, 460f)
        val k = 68.dp * u
        Column(
            Modifier.fillMaxSize().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    r.forEach { n -> KeyButton(TvKey.digits[n], "$n", text = "$n", size = k) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(TvKey.BACK, "Atrás", icon = Icons.AutoMirrored.Filled.ArrowBack, size = k)
                KeyButton(TvKey.NUM_0, "0", text = "0", size = k)
                KeyButton(TvKey.OK, "OK", text = "OK", size = k, color = Color(0xFF34507F))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton(TvKey.CHANNEL_DOWN, "Canal anterior", icon = Icons.Filled.KeyboardArrowDown, size = 52.dp * u, caption = "Canal −")
                KeyButton(TvKey.CHANNEL_UP, "Canal siguiente", icon = Icons.Filled.KeyboardArrowUp, size = 52.dp * u, caption = "Canal +")
            }
        }
    }
}

// ---------------------------------------------------------------- Pestaña «Apps»

@Composable
private fun AppsPad(
    shortcuts: List<Shortcut>,
    iconFor: (Shortcut) -> ImageBitmap?,
    iconsVersion: Int,
    onShortcut: (Shortcut) -> Unit,
    onEdit: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abre una app en la tele con un toque.", color = Palette.keySubtle, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("Editar")
                }
            }
        }
        items(shortcuts, key = { it.id }) { s ->
            val icon = remember(s, iconsVersion) { iconFor(s) }
            AppTile(s, icon) { onShortcut(s) }
        }
        if (shortcuts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No tienes accesos directos. Pulsa «Editar» para añadirlos.", color = Palette.keySubtle, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun AppTile(s: Shortcut, icon: ImageBitmap?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .aspectRatio(0.9f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2C3039), Color(0xFF20232A))))
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .clickable(onClickLabel = "Abrir ${s.label}", onClick = onClick)
            .semantics { contentDescription = "Abrir ${s.label}" }
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(s, icon, 52.dp)
        Spacer(Modifier.height(10.dp))
        Text(s.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AppIcon(s: Shortcut, icon: ImageBitmap?, size: Dp) {
    if (icon != null) {
        Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(appColor(s.label)), contentAlignment = Alignment.Center) {
            Text(s.label.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color.White, fontSize = (size.value * 0.45f).sp)
        }
    }
}

private fun appColor(label: String): Color {
    val colors = listOf(Color(0xFF8E44AD), Color(0xFF16A085), Color(0xFFD35400), Color(0xFF2C7BE5), Color(0xFFC0392B), Color(0xFF27AE60))
    return when (label.lowercase()) {
        "youtube" -> Color(0xFFD93025); "netflix" -> Color(0xFFB20710); "prime video" -> Color(0xFF1A98FF); "disney+" -> Color(0xFF113CCF)
        else -> colors[Math.floorMod(label.lowercase().hashCode(), colors.size)]
    }
}
