package local.mandotv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.mandotv.protocol.RemoteMessages
import local.mandotv.protocol.TvKey

/** Vibración al pulsar. */
interface Haptics {
    fun tap()
    fun tick()
    fun long()

    object None : Haptics {
        override fun tap() = Unit
        override fun tick() = Unit
        override fun long() = Unit
    }
}

/** Cómo envía la UI una tecla (con su dirección: corta, mantener, soltar): true si salió hacia la tele. */
class KeySender(val send: (TvKey, Int) -> Boolean, val haptics: Haptics = Haptics.None) {
    fun short(key: TvKey) = send(key, RemoteMessages.SHORT)
}

val LocalKeySender = staticCompositionLocalOf { KeySender({ _, _ -> false }) }

@Composable
fun ProvideKeySender(sender: KeySender, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalKeySender provides sender, content = content)

/**
 * Zona pulsable de una tecla: vibra, envía al pulsar y repite si se mantiene (cuando la tecla lo
 * admite). También es accesible con TalkBack.
 */
@Composable
fun Modifier.remoteKey(key: TvKey, label: String, onPressedChange: (Boolean) -> Unit): Modifier {
    // rememberUpdatedState: aunque la pantalla se redibuje (p. ej. al cambiar el volumen),
    // la pulsación mantenida no se interrumpe.
    val sender by rememberUpdatedState(LocalKeySender.current)
    val scope = rememberCoroutineScope()
    fun sendOnce(repeat: Boolean): Boolean {
        val ok = sender.short(key)
        if (ok) if (repeat) sender.haptics.tick() else sender.haptics.tap()
        return ok
    }
    return this
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { sendOnce(false); true }
        }
        .pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onPressedChange(true)
                if (key.longPress) {
                    // Pulsación corta al soltar; si se mantiene, «mantener» y luego «soltar» (p. ej. OK
                    // largo abre el menú de opciones en Google TV).
                    var long = false
                    val job = scope.launch {
                        delay(TvKey.LONG_PRESS_MS)
                        long = true
                        if (sender.send(key, RemoteMessages.START_LONG)) sender.haptics.long() else long = false
                    }
                    val up = waitForUpOrCancellation()
                    job.cancel()
                    if (long) sender.send(key, RemoteMessages.END_LONG) else if (up != null) sendOnce(false)
                } else {
                    val job = scope.launch { pressAndRepeat(key) { sendOnce(it) } }
                    waitForUpOrCancellation()
                    job.cancel()
                }
                onPressedChange(false)
            }
        }
}

/** Zona pulsable que ejecuta una acción de la app (no una tecla), con la misma sensación al tacto. */
@Composable
fun Modifier.actionKey(label: String, onPressedChange: (Boolean) -> Unit, onClick: () -> Unit): Modifier {
    val haptics = LocalKeySender.current.haptics
    val action by rememberUpdatedState(onClick)
    return this
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { action(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onPressedChange(true)
                val up = waitForUpOrCancellation()
                onPressedChange(false)
                if (up != null) {
                    haptics.tap()
                    action()
                }
            }
        }
}

/** Cara de una tecla con relieve: luz arriba, sombra abajo y brillo de acento al pulsar. */
@Composable
private fun KeyFace(
    pressed: Boolean,
    size: Dp,
    color: Color,
    shape: Shape,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(90), label = "escala")
    val glow by animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "brillo")
    Box(
        modifier
            .size(size)
            .scale(scale)
            .shadow(if (pressed) 1.dp else 6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(lerp(color, Color.White, if (pressed) 0.04f else 0.10f), lerp(color, Color.Black, 0.18f)),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        lerp(Color.White.copy(alpha = 0.16f), Palette.accent, glow),
                        lerp(Color.White.copy(alpha = 0.02f), Palette.accent.copy(alpha = 0.6f), glow),
                    ),
                ),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Caption(text: String?) {
    if (text == null) return
    Spacer(Modifier.height(5.dp))
    Text(text, color = Palette.keySubtle, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
}

/** Tecla redonda o rectangular con icono y/o texto. [active] la resalta (p. ej. Silencio activado). */
@Composable
fun KeyButton(
    key: TvKey,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    text: String? = null,
    size: Dp = 60.dp,
    color: Color = Palette.key,
    contentColor: Color = Palette.keyText,
    shape: Shape = CircleShape,
    caption: String? = null,
    active: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val face by animateColorAsState(if (active) Palette.warn else color, label = "color")
    val ink by animateColorAsState(if (active) Color(0xFF2B1A00) else contentColor, label = "tinta")
    Column(
        modifier.semantics { if (active) stateDescription = "Activado" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KeyFace(pressed, size, face, shape, Modifier.remoteKey(key, label) { pressed = it }) {
            if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(size * 0.44f))
            if (text != null) Text(text, color = ink, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.32f).sp)
        }
        Caption(caption)
    }
}

/** Botón con el mismo aspecto que una tecla, pero que ejecuta una acción de la app. */
@Composable
fun ActionButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    caption: String? = null,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        KeyFace(pressed, size, Palette.key, CircleShape, Modifier.actionKey(label, { pressed = it }, onClick)) {
            Icon(icon, null, tint = Palette.keyText, modifier = Modifier.size(size * 0.44f))
        }
        Caption(caption)
    }
}

/** Balancín vertical tipo mando: «+» arriba, etiqueta en medio, «−» abajo. */
@Composable
fun Rocker(
    title: String,
    up: TvKey,
    down: TvKey,
    upLabel: String,
    downLabel: String,
    modifier: Modifier = Modifier,
    upIcon: ImageVector = Icons.Filled.Add,
    downIcon: ImageVector = Icons.Filled.Remove,
    width: Dp = 72.dp,
    height: Dp = 156.dp,
) {
    val shape = RoundedCornerShape(width / 2)
    Box(
        modifier
            .width(width)
            .height(height)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF30353F), Color(0xFF1F232A), Color(0xFF292D35))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.02f))), shape),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            RockerHalf(up, upLabel, upIcon, Modifier.weight(1f))
            Text(
                title, color = Palette.keySubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            RockerHalf(down, downLabel, downIcon, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RockerHalf(key: TvKey, label: String, icon: ImageVector, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (pressed) Palette.accent.copy(alpha = 0.22f) else Color.Transparent, tween(100), label = "fondo")
    Box(
        modifier.fillMaxWidth().background(bg).remoteKey(key, label) { pressed = it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Palette.keyText, modifier = Modifier.size(28.dp))
    }
}

/** Cruceta circular con OK en el centro. */
@Composable
fun DPad(modifier: Modifier = Modifier, diameter: Dp = 230.dp) {
    Box(
        modifier
            .size(diameter)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF2F343E), Color(0xFF1B1E24))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.02f))), CircleShape),
    ) {
        val arm = diameter * 0.33f
        val wide = diameter * 0.42f
        DPadArm(TvKey.UP, "Arriba", Icons.Filled.KeyboardArrowUp, Modifier.align(Alignment.TopCenter).size(wide, arm))
        DPadArm(TvKey.DOWN, "Abajo", Icons.Filled.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter).size(wide, arm))
        DPadArm(TvKey.LEFT, "Izquierda", Icons.AutoMirrored.Filled.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart).size(arm, wide))
        DPadArm(TvKey.RIGHT, "Derecha", Icons.AutoMirrored.Filled.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd).size(arm, wide))
        KeyButton(
            TvKey.OK, "OK", text = "OK", size = diameter * 0.37f,
            color = Color(0xFF34507F), modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun DPadArm(key: TvKey, label: String, icon: ImageVector, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (pressed) Palette.accent.copy(alpha = 0.20f) else Color.Transparent, tween(100), label = "fondo")
    Box(
        modifier.padding(5.dp).clip(RoundedCornerShape(26.dp)).background(bg).remoteKey(key, label) { pressed = it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Palette.keyText, modifier = Modifier.size(38.dp))
    }
}

/** Botón de color (rojo A, verde B, amarillo C, azul D). */
@Composable
fun ColorKey(key: TvKey, color: Color, letter: String, name: String, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    KeyButton(
        key, "$name ($letter)", text = letter, size = size, color = color,
        contentColor = if (color == Palette.yellow) Color(0xFF2B2600) else Color.White,
        shape = RoundedCornerShape(size * 0.3f), modifier = modifier,
    )
}

/**
 * Anillo de volumen: se rellena según el nivel que informa la tele. Ámbar con el altavoz tachado
 * si está en silencio; gris y vacío si la tele aún no ha informado.
 */
@Composable
fun VolumeRing(level: Int?, max: Int?, muted: Boolean, size: Dp = 88.dp, modifier: Modifier = Modifier) {
    val fraction = if (level != null && max != null && max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, tween(250), label = "volumen")
    val color by animateColorAsState(if (muted) Palette.warn else Palette.accent, label = "color")
    val desc = when {
        muted -> "Tele en silencio"
        level != null -> "Volumen de la tele: $level"
        else -> "Volumen desconocido"
    }
    Box(modifier.size(size).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.09f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.08f), 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(color, 135f, 270f * animated, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (muted) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, null, tint = Palette.warn, modifier = Modifier.size(size * 0.3f))
            } else {
                Text(level?.toString() ?: "–", fontSize = (size.value * 0.28f).sp, fontWeight = FontWeight.Bold, color = Palette.keyText)
            }
            Text("VOL", fontSize = (size.value * 0.12f).sp, color = Palette.keySubtle, letterSpacing = 1.sp)
        }
    }
}
