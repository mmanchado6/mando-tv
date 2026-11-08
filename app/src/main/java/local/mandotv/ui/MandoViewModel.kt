package local.mandotv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.mandotv.BuildConfig
import local.mandotv.android.AppIcons
import local.mandotv.android.PhoneApp
import local.mandotv.android.TvDiscovery
import local.mandotv.android.VibratorHaptics
import local.mandotv.data.EnterMode
import local.mandotv.android.WifiMonitor
import local.mandotv.crypto.PairingSecret
import local.mandotv.crypto.publicKeyFingerprint
import local.mandotv.data.AppStore
import local.mandotv.data.IdentityStore
import local.mandotv.data.SavedTv
import local.mandotv.data.Settings
import local.mandotv.data.Shortcut
import local.mandotv.net.BlockedDestinationException
import local.mandotv.net.LanGate
import local.mandotv.net.LanPolicy
import local.mandotv.protocol.RemoteMessages
import local.mandotv.protocol.TvKey
import local.mandotv.remote.ImeEvent
import local.mandotv.remote.ConnectionState
import local.mandotv.remote.PairingClient
import local.mandotv.remote.PairingSession
import local.mandotv.remote.RemoteController
import local.mandotv.remote.TvTarget
import local.mandotv.remote.WakeOnLan
import java.util.UUID

/** Pantallas de la app. */
sealed interface Screen {
    data object Devices : Screen
    data class Pairing(val name: String, val host: String) : Screen
    data object Remote : Screen
}

sealed interface PairingUi {
    data object Connecting : PairingUi
    data object WaitingCode : PairingUi
    data object Checking : PairingUi
    data class Error(val message: String, val canRetryCode: Boolean) : PairingUi
}

class MandoViewModel(app: Application) : AndroidViewModel(app) {
    val log = DiagnosticLog()
    private val policy = LanPolicy()
    private val wifi = WifiMonitor(app)
    private val gate = LanGate(policy, wifi)
    private val identityStore = IdentityStore(app)
    val store = AppStore(app)
    val discovery = TvDiscovery(app, policy) { log.add(it) }
    val icons = AppIcons(app) { store.settings.value.phoneAppIcons }

    val controller = RemoteController(
        gate = gate,
        identityProvider = { identityStore.get() },
        scope = viewModelScope,
        appVersion = BuildConfig.VERSION_NAME,
        onUnreachable = { lookForMovedTv(it) },
        log = { log.add(it) },
    )

    private val _screen = MutableStateFlow<Screen>(Screen.Devices)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Connecting)
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _keyboard = MutableStateFlow<KeyboardRequest?>(null)
    /** Panel de teclado abierto (por petición de la tele o a mano). */
    val keyboard: StateFlow<KeyboardRequest?> = _keyboard.asStateFlow()
    private var keyboardIds = 0L
    private val _iconsVersion = MutableStateFlow(0)
    /** Cambia cuando hay que redibujar los logos (ajuste activado/desactivado, accesos editados). */
    val iconsVersion: StateFlow<Int> = _iconsVersion.asStateFlow()
    private val _identityReady = MutableStateFlow(false)
    val identityReady: StateFlow<Boolean> = _identityReady.asStateFlow()

    val selectedTv: StateFlow<SavedTv?> = combine(store.tvs, store.selectedId) { tvs, id -> tvs.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var pairingSession: PairingSession? = null
    private var pairingJob: Job? = null
    private var backgroundJob: Job? = null
    private var rediscoveryJob: Job? = null
    @Volatile private var permissionGranted = false

    init {
        wifi.start()
        controller.start()
        viewModelScope.launch(Dispatchers.Default) {
            val existed = identityStore.exists()
            identityStore.get() // crea la identidad la primera vez (tarda ~1 s)
            if (!existed && store.tvs.value.isNotEmpty()) store.forgetAllTvs()
            _identityReady.value = true
        }
        viewModelScope.launch {
            wifi.available.collect { ok ->
                controller.setWifiAvailable(ok)
                if (ok) controller.retryNow()
            }
        }
        viewModelScope.launch {
            // La tele tiene un campo de texto activo: se abre el teclado solo.
            controller.imeEvents.collect { e ->
                when {
                    e is ImeEvent.Show && _screen.value == Screen.Remote -> {
                        _keyboard.value = KeyboardRequest(++keyboardIds, e.text, e.label, auto = true)
                    }
                    // La tele ya no tiene el campo activo: se cierra si lo abrió la tele
                    e is ImeEvent.Hide && _keyboard.value?.auto == true -> _keyboard.value = null
                }
            }
        }
        viewModelScope.launch {
            combine(store.settings, store.shortcuts) { st, list -> st.phoneAppIcons to list }.collect { (_, list) ->
                withContext(Dispatchers.IO) { icons.preload(list) }
                _iconsVersion.value++
            }
        }
        viewModelScope.launch {
            selectedTv.collect { tv ->
                controller.setTarget(tv?.target())
                if (tv != null && _screen.value == Screen.Devices) _screen.value = Screen.Remote
            }
        }
    }

    // ---------- Ciclo de vida ----------

    fun onPermission(granted: Boolean) {
        permissionGranted = granted
        if (granted && _screen.value == Screen.Devices) discovery.start()
    }

    /** La app pasa a primer plano. */
    fun onForeground() {
        backgroundJob?.cancel()
        controller.setActive(true)
        controller.retryNow()
        if (permissionGranted && _screen.value == Screen.Devices) discovery.start()
    }

    /** La app pasa a segundo plano: tras 1 minuto se desconecta para ahorrar batería. */
    fun onBackground() {
        discovery.stop()
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay(60_000)
            controller.setActive(false)
        }
    }

    // ---------- Navegación ----------

    fun showDevices() {
        _screen.value = Screen.Devices
        if (permissionGranted) discovery.start()
    }

    fun backFromDevices() {
        discovery.stop()
        if (selectedTv.value != null) _screen.value = Screen.Remote
    }

    fun consumeMessage() { _message.value = null }
    fun toast(text: String) { _message.value = text }

    // ---------- Elegir tele / emparejar ----------

    /** El usuario elige una tele encontrada o escribe su IP. */
    fun chooseTv(name: String, hostInput: String) {
        val host = hostInput.trim()
        val ip = policy.parseIpv4(host)
        if (ip == null || !policy.isLocal(ip)) {
            _message.value = "Escribe una IP de tu red local (192.168.x.x, 10.x.x.x o 172.16-31.x.x)"
            return
        }
        val known = store.tvs.value.firstOrNull { it.host == host || (name.isNotBlank() && it.name == name) }
        if (known != null) {
            if (known.host != host) store.saveTv(known.copy(host = host))
            store.select(known.id)
            discovery.stop()
            _screen.value = Screen.Remote
            return
        }
        startPairing(name.ifBlank { "Tele $host" }, host)
    }

    fun repairSelected() {
        val tv = selectedTv.value ?: return showDevices()
        startPairing(tv.name, tv.host)
    }

    fun startPairing(name: String, host: String) {
        discovery.stop()
        cancelPairing()
        _screen.value = Screen.Pairing(name, host)
        _pairing.value = PairingUi.Connecting
        pairingJob = viewModelScope.launch {
            try {
                pairingSession = PairingClient.start(gate, host, identityStore.get())
                _pairing.value = PairingUi.WaitingCode
            } catch (e: Exception) {
                log.add("Emparejamiento: ${e.javaClass.simpleName}: ${e.message}")
                _pairing.value = PairingUi.Error(friendly(e), canRetryCode = false)
            }
        }
    }

    fun submitCode(code: String) {
        val screen = _screen.value as? Screen.Pairing ?: return
        val session = pairingSession ?: return
        _pairing.value = PairingUi.Checking
        viewModelScope.launch {
            try {
                session.submitCode(code)
                pairingSession = null
                val fp = session.serverCertificate.publicKeyFingerprint()
                val previous = store.tvs.value.firstOrNull { it.host == screen.host || it.fingerprint == fp }
                val tv = SavedTv(
                    id = previous?.id ?: UUID.randomUUID().toString(),
                    name = screen.name,
                    host = screen.host,
                    fingerprint = fp,
                    mac = previous?.mac ?: "",
                )
                store.saveTv(tv)
                store.select(tv.id)
                controller.setTarget(tv.target())
                controller.retryNow()
                _screen.value = Screen.Remote
                _message.value = "Emparejado con ${tv.name}"
            } catch (e: PairingSecret.InvalidCodeException) {
                _pairing.value = PairingUi.Error(
                    if (e.message == "Código incorrecto") "Código incorrecto. Revisa el que muestra la tele." else e.message ?: "Código no válido",
                    canRetryCode = true,
                )
            } catch (e: Exception) {
                pairingSession = null
                log.add("Emparejamiento: ${e.javaClass.simpleName}: ${e.message}")
                _pairing.value = PairingUi.Error(friendly(e), canRetryCode = false)
            }
        }
    }

    fun retryCodeEntry() { if (pairingSession != null) _pairing.value = PairingUi.WaitingCode }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingSession?.close()
        pairingSession = null
    }

    fun leavePairing() {
        cancelPairing()
        showDevices()
    }

    fun forgetTv(id: String) {
        store.removeTv(id)
        if (store.selectedId.value == null) showDevices()
    }

    fun selectSaved(id: String) {
        store.select(id)
        discovery.stop()
        _screen.value = Screen.Remote
    }

    fun setMac(mac: String): Boolean {
        val tv = selectedTv.value ?: return false
        if (mac.isBlank()) { store.saveTv(tv.copy(mac = "")); return true }
        val parsed = WakeOnLan.parseMac(mac) ?: return false
        store.saveTv(tv.copy(mac = WakeOnLan.formatMac(parsed)))
        return true
    }

    // ---------- Mando ----------

    val haptics = VibratorHaptics(app) { store.settings.value.vibration }

    /** Envía una tecla (corta, mantener o soltar). Devuelve false si no hay conexión. */
    fun press(key: TvKey, direction: Int = RemoteMessages.SHORT): Boolean {
        if (controller.send(key, direction)) return true
        if (direction != RemoteMessages.SHORT) return false
        if (key == TvKey.POWER) return wake()
        _message.value = "Sin conexión con la tele"
        controller.retryNow()
        return false
    }

    /** Wake-on-LAN cuando la tele no responde y se pulsa encender. */
    private fun wake(): Boolean {
        val tv = selectedTv.value ?: return false
        val s = store.settings.value
        val mac = WakeOnLan.parseMac(tv.mac)
        if (!s.wakeOnLan || mac == null) {
            _message.value = if (!s.wakeOnLan) "Sin conexión con la tele" else
                "La tele no responde. Para encenderla desde apagada, añade su MAC en Ajustes → Wake-on-LAN"
            controller.retryNow()
            return false
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WakeOnLan.wake(gate, mac, tv.host, wifi.broadcastAddress()) }
                .onFailure { log.add("Wake-on-LAN: ${it.message}") }
            withContext(Dispatchers.Main) { _message.value = "Encendiendo la tele…" }
            // Le damos tiempo a arrancar y reintentamos varias veces
            repeat(6) { delay(2500); controller.retryNow() }
        }
        return true
    }

    /** «Intro» del teclado, según el modo elegido en Ajustes. */
    fun submitEnter() {
        when (store.settings.value.enterMode) {
            EnterMode.ENTER -> press(TvKey.ENTER)
            EnterMode.NUMPAD_ENTER -> press(TvKey.NUMPAD_ENTER)
            EnterMode.NEWLINE -> sendEdit(RemoteMessages.TextEdit(0, "\n"))
        }
    }

    fun openKeyboard() { _keyboard.value = KeyboardRequest(++keyboardIds, "", "") }
    fun closeKeyboard() { _keyboard.value = null }

    /** Escritura letra a letra desde el panel de teclado. */
    fun sendEdit(edit: RemoteMessages.TextEdit): Boolean =
        controller.sendEdit(edit).also { if (!it) _message.value = "Sin conexión con la tele" }

    /** Copia a la app la imagen elegida en la galería (selector de fotos del sistema). */
    suspend fun importIcon(uri: android.net.Uri): String? = withContext(Dispatchers.IO) { icons.importFromGallery(uri) }

    /** Apps del móvil para elegir icono. Vacío si el ajuste está desactivado (no se consulta nada). */
    suspend fun phoneApps(): List<PhoneApp> = withContext(Dispatchers.IO) { icons.phoneApps.list() }

    fun launch(shortcut: Shortcut) {
        if (!controller.launchApp(shortcut.link)) {
            _message.value = "Sin conexión con la tele"
            controller.retryNow()
        }
    }

    fun saveShortcuts(list: List<Shortcut>) {
        store.setShortcuts(list)
        viewModelScope.launch(Dispatchers.IO) { icons.cleanup(list) }
    }

    fun updateSettings(transform: (Settings) -> Settings) = store.updateSettings(transform)

    /** Botones físicos de volumen del móvil. Devuelve true si se ha usado para la tele. */
    fun handlePhoneVolume(up: Boolean): Boolean {
        if (!store.settings.value.phoneVolumeKeys || !controller.isConnected) return false
        return controller.send(if (up) TvKey.VOLUME_UP else TvKey.VOLUME_DOWN)
    }

    // ---------- Tele que cambia de IP ----------

    /** Tras varios fallos seguidos se busca la tele por mDNS: si aparece con otra IP, se actualiza. */
    private fun lookForMovedTv(target: TvTarget) {
        if (!permissionGranted || rediscoveryJob?.isActive == true) return
        rediscoveryJob = viewModelScope.launch {
            log.add("Buscando si ${target.name} cambió de IP…")
            discovery.start()
            try {
                repeat(20) {
                    val match = discovery.found.value.firstOrNull { it.name == target.name && it.host != target.host }
                    if (match != null) {
                        val tv = store.tvs.value.firstOrNull { it.id == target.id } ?: return@launch
                        log.add("${tv.name} ahora está en ${match.host}")
                        store.saveTv(tv.copy(host = match.host)) // el certificado fijado confirma que es la misma
                        return@launch
                    }
                    delay(1000)
                }
            } finally {
                if (_screen.value != Screen.Devices) discovery.stop()
            }
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is BlockedDestinationException -> e.message ?: "Dirección no permitida"
        e.message?.contains("WiFi") == true -> "No hay conexión WiFi"
        e is java.net.SocketTimeoutException || e.cause is java.net.SocketTimeoutException ->
            "La tele no responde. ¿Está encendida y en la misma WiFi?"
        e is java.net.ConnectException || e.cause is java.net.ConnectException ->
            "No se pudo conectar. Comprueba la IP y que la tele esté encendida."
        else -> e.message ?: "Error de conexión"
    }

    override fun onCleared() {
        cancelPairing()
        discovery.stop()
    }
}

/** Registro de diagnóstico: solo en memoria, sin datos sensibles; se puede copiar desde Ajustes. */
class DiagnosticLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    @Synchronized fun add(line: String) {
        lines.addLast("${fmt.format(java.util.Date())}  $line")
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}

/** Estado combinado para la barra superior. */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.NoTv -> "Sin tele elegida"
    ConnectionState.Paused -> "En pausa"
    ConnectionState.NoWifi -> "Sin WiFi"
    is ConnectionState.Connecting -> "Conectando…"
    is ConnectionState.Connected -> "Conectado"
    is ConnectionState.Waiting -> reason
    ConnectionState.NeedsPairing -> "Hay que emparejar de nuevo"
    ConnectionState.CertificateChanged -> "La tele ha cambiado: vuelve a emparejar"
}
