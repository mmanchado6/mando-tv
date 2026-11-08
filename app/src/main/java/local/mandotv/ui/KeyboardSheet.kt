package local.mandotv.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import local.mandotv.protocol.RemoteMessages

/** Petición de abrir el teclado: [text] es lo que ya hay escrito en el campo de la tele. */
data class KeyboardRequest(val id: Long, val text: String, val label: String, val auto: Boolean = false)

/**
 * Sincroniza un campo de texto del móvil con el de la tele, letra a letra. Recuerda lo último que
 * se envió y, ante cada cambio, calcula cuántas letras borrar y qué añadir.
 */
class LiveTextSync(initial: String, private val send: (RemoteMessages.TextEdit) -> Boolean) {
    var sent: String = initial
        private set

    /** Devuelve false si no se pudo enviar (sin conexión); en ese caso no se da por enviado. */
    fun onTextChanged(now: String): Boolean {
        val edit = RemoteMessages.textEdit(sent, now) ?: return true
        if (!send(edit)) return false
        sent = now
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSheet(
    request: KeyboardRequest,
    onEdit: (RemoteMessages.TextEdit) -> Boolean,
    onEnter: () -> Unit,
    onClose: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sync = remember(request.id) { LiveTextSync(request.text, onEdit) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Palette.body) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp).imePadding()) {
            Text("Escribiendo en la tele", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                request.label.ifBlank { "Lo que escribas aparece en la tele al momento." },
                color = Palette.keySubtle, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            LiveTextField(request, sync, onEnter = { onEnter(); onClose() })
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Palette.keySubtle, modifier = Modifier.size(12.dp))
                Text("  Teclado en modo incógnito: no aprende lo que escribes.", color = Palette.keySubtle, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Cerrar") }
                FilledTonalButton(
                    onClick = { onEnter(); onClose() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Palette.accent.copy(alpha = 0.25f), contentColor = Palette.keyText),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardReturn, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Intro")
                }
            }
        }
    }
}

/**
 * Campo nativo de Android: permite pedir al teclado el modo incógnito
 * (IME_FLAG_NO_PERSONALIZED_LEARNING) y abrirlo automáticamente.
 */
@Composable
private fun LiveTextField(request: KeyboardRequest, sync: LiveTextSync, onEnter: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    AndroidView(
        modifier = Modifier.fillMaxWidth().clip(shape).background(Palette.key).border(1.dp, Palette.accent.copy(alpha = 0.5f), shape),
        factory = { ctx ->
            EditText(ctx).apply {
                hint = "Escribe aquí"
                setHintTextColor(Palette.keySubtle.toArgb())
                setTextColor(Palette.keyText.toArgb())
                textSize = 20f
                background = null
                setPadding(44, 40, 44, 40)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isSingleLine = true
                setText(request.text)
                setSelection(text.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) { sync.onTextChanged(s?.toString().orEmpty()) }
                })
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) { onEnter(); true } else false
                }
                // Abre el teclado del móvil automáticamente
                post {
                    requestFocus()
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
    )
}
