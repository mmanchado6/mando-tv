package local.mandotv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch
import local.mandotv.android.DiscoveredTv
import local.mandotv.android.PhoneApp
import local.mandotv.data.EnterMode
import local.mandotv.data.SavedTv
import local.mandotv.data.Settings
import local.mandotv.data.Shortcut
import local.mandotv.data.Shortcuts
import java.util.UUID

@Composable
private fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.body).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            else Spacer(Modifier.size(16.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) { content() }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Palette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.body).then(modifier).padding(14.dp)) { content() }
}

// ---------------------------------------------------------------- Permiso de red local

@Composable
fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit, denied: Boolean) {
    Page("Mando TV", onBack = null) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Filled.Wifi, null, tint = Palette.accent, modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Text("Permiso de red local", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(
            "Android 17 exige este permiso para que la app pueda encontrar tu tele en la WiFi y enviarle " +
                "las órdenes del mando. Sin él, Android bloquea cualquier conexión con la tele.\n\n" +
                "La app solo habla con dispositivos de tu red local: nunca se conecta a Internet ni envía " +
                "datos fuera de casa.",
            color = Palette.keySubtle, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Conceder permiso") }
        if (denied) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Abrir ajustes de la app") }
        }
    }
}

// ---------------------------------------------------------------- Elegir tele

@Composable
fun DevicesScreen(
    found: List<DiscoveredTv>,
    searching: Boolean,
    saved: List<SavedTv>,
    selectedId: String?,
    onBack: (() -> Unit)?,
    onChoose: (name: String, host: String) -> Unit,
    onSelectSaved: (String) -> Unit,
    onForget: (String) -> Unit,
    onRescan: () -> Unit,
) {
    var manualIp by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf<SavedTv?>(null) }
    Page("Elige tu tele", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("En tu WiFi", color = Palette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            IconButton(onClick = onRescan) { Icon(Icons.Filled.Refresh, "Buscar de nuevo") }
        }
        if (found.isEmpty()) {
            Card {
                Text(
                    if (searching) "Buscando teles Android TV / Google TV…" else "No se ha encontrado ninguna tele.",
                    color = Palette.keySubtle,
                )
                Text(
                    "Comprueba que la tele está encendida y en la misma WiFi (no en la de invitados).",
                    color = Palette.keySubtle, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        found.forEach { tv ->
            val isSaved = saved.any { it.name == tv.name || it.host == tv.host }
            TvRow(tv.name, tv.host, if (isSaved) "Emparejada" else "Emparejar") { onChoose(tv.name, tv.host) }
        }

        SectionTitle("Escribir la IP a mano")
        Card {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { v -> manualIp = v.filter { it.isDigit() || it == '.' }.take(15) },
                label = { Text("IP de la tele (p. ej. 192.168.1.50)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Está en la tele: Ajustes → Red e Internet → tu WiFi.",
                color = Palette.keySubtle, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onChoose("", manualIp) }, enabled = manualIp.count { it == '.' } == 3, modifier = Modifier.fillMaxWidth()) {
                Text("Conectar")
            }
        }

        if (saved.isNotEmpty()) {
            SectionTitle("Teles emparejadas")
            saved.forEach { tv ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Palette.body)
                        .clickable { onSelectSaved(tv.id) }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (tv.id == selectedId) Icons.Filled.CheckCircle else Icons.Filled.Tv, null, tint = Palette.accent)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(tv.name, fontWeight = FontWeight.Bold)
                        Text(tv.host, color = Palette.keySubtle, fontSize = 12.sp)
                    }
                    IconButton(onClick = { confirmForget = tv }) { Icon(Icons.Filled.Delete, "Olvidar ${tv.name}") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Palette.keySubtle, modifier = Modifier.size(14.dp))
            Text(
                "  Solo se aceptan IPs de tu red local. Los datos se guardan únicamente en este móvil.",
                color = Palette.keySubtle, fontSize = 12.sp,
            )
        }
    }
    confirmForget?.let { tv ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("¿Olvidar ${tv.name}?") },
            text = { Text("Se borrará el emparejamiento de este móvil. Para volver a usarla tendrás que emparejar otra vez.") },
            confirmButton = { TextButton(onClick = { onForget(tv.id); confirmForget = null }) { Text("Olvidar") } },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TvRow(name: String, host: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Palette.body)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tv, null, tint = Palette.accent)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, fontWeight = FontWeight.Bold)
            Text(host, color = Palette.keySubtle, fontSize = 12.sp)
        }
        Text(action, color = Palette.accent, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- Emparejar

@Composable
fun PairingScreen(name: String, host: String, state: PairingUi, onSubmit: (String) -> Unit, onRetryCode: () -> Unit, onRestart: () -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Page("Emparejar", onBack = onCancel) {
        Text(name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(host, color = Palette.keySubtle)
        Spacer(Modifier.height(24.dp))
        when (state) {
            PairingUi.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("   Conectando con la tele…")
            }
            PairingUi.WaitingCode, PairingUi.Checking -> {
                Text("Escribe el código de 6 caracteres que aparece ahora en la pantalla de la tele.")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6) },
                    label = { Text("Código") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 8.sp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Solo números del 0 al 9 y letras de la A a la F.", color = Palette.keySubtle, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onSubmit(code) },
                    enabled = code.length == 6 && state == PairingUi.WaitingCode,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (state == PairingUi.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Emparejar")
                }
            }
            is PairingUi.Error -> {
                Text(state.message, color = Palette.error)
                Spacer(Modifier.height(16.dp))
                if (state.canRetryCode) {
                    Button(onClick = { code = ""; onRetryCode() }, modifier = Modifier.fillMaxWidth()) { Text("Escribir el código otra vez") }
                }
                OutlinedButton(onClick = { code = ""; onRestart() }, modifier = Modifier.fillMaxWidth()) { Text("Empezar de nuevo") }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "El emparejamiento se hace una sola vez: después el móvil guarda su certificado y la tele lo reconoce.",
            color = Palette.keySubtle, fontSize = 12.sp,
        )
    }
}

// ---------------------------------------------------------------- Ajustes

@Composable
fun SettingsScreen(
    settings: Settings,
    tv: SavedTv?,
    onBack: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onSaveMac: (String) -> Boolean,
    onRepair: () -> Unit,
    onForget: () -> Unit,
    onEditShortcuts: () -> Unit,
    onCopyLog: () -> Unit,
) {
    var mac by remember(tv?.id) { mutableStateOf(tv?.mac.orEmpty()) }
    var macError by remember { mutableStateOf(false) }
    Page("Ajustes", onBack) {
        SectionTitle("Mando")
        SwitchRow("Vibración al pulsar", "Vibra al pulsar cada botón del mando.", settings.vibration) { v -> onChange { it.copy(vibration = v) } }
        SwitchRow(
            "Volumen con los botones del móvil",
            "Mientras la app está abierta y conectada, los botones de volumen del móvil controlan la tele.",
            settings.phoneVolumeKeys,
        ) { v -> onChange { it.copy(phoneVolumeKeys = v) } }
        OutlinedButton(onClick = onEditShortcuts, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Personalizar accesos directos") }
        SwitchRow(
            "Iconos de apps del móvil",
            "Permite elegir como logo de un acceso el icono de una app instalada en este móvil. Para ello la " +
                "app tiene que ver la lista de apps del móvil (solo en el móvil, no se envía a ningún sitio). " +
                "Desactivado: la app nunca consulta esa lista.",
            settings.phoneAppIcons,
        ) { v -> onChange { it.copy(phoneAppIcons = v) } }

        SectionTitle("Teclado: botón Intro")
        Text(
            "Cada tele reacciona distinto a «Intro». Si al pulsarlo se borra una letra o no busca, prueba otra opción.",
            color = Palette.keySubtle, fontSize = 12.sp,
        )
        EnterMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable { onChange { it.copy(enterMode = mode) } }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.enterMode == mode, onClick = { onChange { it.copy(enterMode = mode) } })
                Text(mode.title)
            }
        }

        SectionTitle("Wake-on-LAN (encender desde apagada)")
        SwitchRow(
            "Usar Wake-on-LAN",
            "Si la tele no responde al pulsar Encender, se le envía el «paquete mágico» por tu red local.",
            settings.wakeOnLan,
        ) { v -> onChange { it.copy(wakeOnLan = v) } }
        if (settings.wakeOnLan && tv != null) {
            Card {
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.uppercase().take(17); macError = false },
                    label = { Text("MAC de la tele") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    isError = macError,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (macError) "MAC no válida" else
                        "En la tele: Ajustes → Red e Internet → (tu red) → Dirección MAC. Activa también en la tele " +
                            "«Encender con Wi-Fi/LAN» o similar. Suele funcionar mejor con cable de red.",
                    color = if (macError) Palette.error else Palette.keySubtle, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Button(onClick = { macError = !onSaveMac(mac) }, modifier = Modifier.padding(top = 8.dp)) { Text("Guardar MAC") }
            }
        }

        if (tv != null) {
            SectionTitle("Tele: ${tv.name}")
            Text("IP: ${tv.host}", color = Palette.keySubtle)
            Text("Huella del certificado: ${tv.fingerprint.take(16)}…", color = Palette.keySubtle, fontSize = 12.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRepair) { Text("Volver a emparejar") }
                OutlinedButton(onClick = onForget) { Text("Olvidar tele") }
            }
        }

        SectionTitle("Privacidad")
        Text(
            "• Solo se conecta a IPs privadas de tu red local, por los puertos 6466/6467 (y el 9 para Wake-on-LAN), " +
                "y siempre a través de la WiFi.\n" +
                "• Sin anuncios, analíticas ni informes de errores.\n" +
                "• Las teles emparejadas y la clave del móvil se guardan solo aquí, cifradas con el Android Keystore. " +
                "Las copias de seguridad están desactivadas.",
            color = Palette.keySubtle, fontSize = 13.sp,
        )

        SectionTitle("Diagnóstico")
        Text(
            "Registro de lo que ha pasado con la conexión (solo en memoria, se borra al cerrar la app). " +
                "Si algo falla puedes copiarlo y compartirlo tú.",
            color = Palette.keySubtle, fontSize = 13.sp,
        )
        OutlinedButton(onClick = onCopyLog, modifier = Modifier.padding(top = 8.dp)) { Text("Copiar registro") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Palette.keySubtle, fontSize = 12.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Accesos directos

/**
 * Editor de accesos directos (sin límite). Cada acceso puede tener logo: una imagen de la
 * galería (selector de fotos del sistema, sin permisos) o el icono de una app del móvil (solo si
 * el ajuste «Iconos de apps del móvil» está activado).
 */
@Composable
fun ShortcutsEditor(
    initial: List<Shortcut>,
    iconFor: (Shortcut) -> ImageBitmap?,
    phoneIconsEnabled: Boolean,
    importIcon: suspend (Uri) -> String?,
    loadPhoneApps: suspend () -> List<PhoneApp>,
    onDone: (List<Shortcut>?) -> Unit,
) {
    val items = remember { mutableStateListOf<Shortcut>().apply { addAll(initial) } }
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    Page("Accesos directos", onBack = { onDone(items.toList()) }) {
        Text(
            "Botones que abren una app en la tele. Puedes usar un enlace (https://…) o el nombre del " +
                "paquete de la app en la tele (por ejemplo, org.xbmc.kodi). Toca uno para editarlo.",
            color = Palette.keySubtle, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        items.forEachIndexed { index, s ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Palette.body)
                    .clickable { editing = s }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(s, iconFor(s), 40.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(s.label, fontWeight = FontWeight.Bold)
                    Text(s.link, color = Palette.keySubtle, fontSize = 12.sp, maxLines = 1)
                }
                IconButton(onClick = { if (index > 0) items.add(index - 1, items.removeAt(index)) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Subir ${s.label}")
                }
                IconButton(onClick = { items.removeAt(index) }) { Icon(Icons.Filled.Delete, "Quitar ${s.label}") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { editing = Shortcut(UUID.randomUUID().toString(), "", "") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.size(8.dp)); Text("Añadir acceso")
        }
        TextButton(onClick = { items.clear(); items.addAll(Shortcuts.defaults) }, modifier = Modifier.fillMaxWidth()) {
            Text("Restaurar los de serie")
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.bodyEdge)
        Button(onClick = { onDone(items.toList()) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Guardar") }
    }
    editing?.let { s ->
        ShortcutDialog(
            s, iconFor, phoneIconsEnabled, importIcon, loadPhoneApps,
            onDismiss = { editing = null },
            onSave = { updated ->
                val i = items.indexOfFirst { it.id == updated.id }
                if (i >= 0) items[i] = updated else items += updated
                editing = null
            },
        )
    }
}

@Composable
private fun ShortcutDialog(
    s: Shortcut,
    iconFor: (Shortcut) -> ImageBitmap?,
    phoneIconsEnabled: Boolean,
    importIcon: suspend (Uri) -> String?,
    loadPhoneApps: suspend () -> List<PhoneApp>,
    onDismiss: () -> Unit,
    onSave: (Shortcut) -> Unit,
) {
    var label by remember { mutableStateOf(s.label) }
    var link by remember { mutableStateOf(s.link.removePrefix("market://launch?id=")) }
    var iconFile by remember { mutableStateOf(s.iconFile) }
    var phonePackage by remember { mutableStateOf(s.phonePackage) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosingApp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Selector de fotos del sistema: no necesita permisos y solo da acceso a la imagen elegida.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importIcon(uri)?.let { iconFile = it; phonePackage = "" } ?: run { error = "No se pudo usar esa imagen" }
        }
    }
    val preview = s.copy(label = label.ifBlank { "?" }, iconFile = iconFile, phonePackage = phonePackage)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (s.label.isEmpty()) "Nuevo acceso" else "Editar acceso") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LogoChooser(
                    preview, iconFor, phoneIconsEnabled,
                    onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPhoneApp = { choosingApp = true },
                    onClear = { iconFile = ""; phonePackage = "" },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(label, { label = it.take(16) }, label = { Text("Nombre") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    link, { link = it.trim() }, label = { Text("Enlace o paquete") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                error?.let { Text(it, color = Palette.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = Shortcuts.normalizeLabel(label)
                val k = Shortcuts.normalizeLink(link)
                error = when {
                    l == null -> "Pon un nombre"
                    k == null -> "Escribe un enlace (https://…) o un paquete (com.ejemplo.app)"
                    else -> null
                }
                if (l != null && k != null) onSave(s.copy(label = l, link = k, iconFile = iconFile, phonePackage = phonePackage))
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    if (choosingApp) {
        PhoneAppPicker(loadPhoneApps, onDismiss = { choosingApp = false }) { app ->
            phonePackage = app.packageName
            iconFile = ""
            if (label.isBlank()) label = app.label.take(16)
            choosingApp = false
        }
    }
}

/** Vista previa del logo y opciones para elegirlo. «App del móvil» solo aparece si el ajuste lo permite. */
@Composable
internal fun LogoChooser(
    preview: Shortcut,
    iconFor: (Shortcut) -> ImageBitmap?,
    phoneIconsEnabled: Boolean,
    onGallery: () -> Unit,
    onPhoneApp: () -> Unit,
    onClear: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(preview, iconFor(preview), 56.dp)
        Column(Modifier.padding(start = 12.dp)) {
            TextButton(onClick = onGallery) { Text("Logo de la galería") }
            if (phoneIconsEnabled) TextButton(onClick = onPhoneApp) { Text("Icono de una app del móvil") }
            if (preview.iconFile.isNotEmpty() || preview.phonePackage.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Quitar logo") }
            }
        }
    }
}

@Composable
private fun PhoneAppPicker(load: suspend () -> List<PhoneApp>, onDismiss: () -> Unit, onPick: (PhoneApp) -> Unit) {
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = load() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Icono de una app del móvil") },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) CircularProgressIndicator(Modifier.size(24.dp))
                else LazyColumn(Modifier.height(320.dp)) {
                    items(list.filter { it.label.contains(filter, ignoreCase = true) }) { app ->
                        Text(
                            app.label,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
