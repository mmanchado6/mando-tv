package local.mandotv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.mandotv.ui.DevicesScreen
import local.mandotv.ui.KeySender
import local.mandotv.ui.KeyboardSheet
import local.mandotv.ui.MandoTheme
import local.mandotv.ui.MandoViewModel
import local.mandotv.ui.PairingScreen
import local.mandotv.ui.Palette
import local.mandotv.ui.PermissionScreen
import local.mandotv.ui.ProvideKeySender
import local.mandotv.ui.RemoteScreen
import local.mandotv.ui.RemoteUi
import local.mandotv.ui.Screen
import local.mandotv.ui.SettingsScreen
import local.mandotv.ui.ShortcutsEditor

class MainActivity : ComponentActivity() {
    private val vm: MandoViewModel by viewModels()
    private var permissionGranted by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)
    private var lastVolumeKey = 0L

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
        vm.onPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        permissionGranted = hasLocalNetworkPermission()
        vm.onPermission(permissionGranted)
        setContent {
            MandoTheme {
                Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
                    if (!permissionGranted) {
                        PermissionScreen(
                            onRequest = { requestPermission.launch(LOCAL_NETWORK) },
                            onOpenSettings = ::openAppSettings,
                            denied = permissionDenied,
                        )
                    } else {
                        App(vm, onCopyLog = ::copyLog)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!permissionGranted && hasLocalNetworkPermission()) {
            permissionGranted = true
            vm.onPermission(true)
        }
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    /** Botones físicos de volumen del móvil → volumen de la tele (si está activado en Ajustes). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val s = vm.store.settings.value
            if (s.phoneVolumeKeys && vm.controller.isConnected) {
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0 || now - lastVolumeKey >= 120) {
                    lastVolumeKey = now
                    vm.handlePhoneVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            vm.store.settings.value.phoneVolumeKeys && vm.controller.isConnected
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun copyLog() {
        val text = "Mando TV ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}\n" + vm.log.text()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnóstico Mando TV", text))
        Toast.makeText(this, "Registro copiado", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Permiso de red local de Android 17 (Manifest.permission.ACCESS_LOCAL_NETWORK). */
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

private enum class Overlay { NONE, SETTINGS, SHORTCUTS }

@Composable
private fun App(vm: MandoViewModel, onCopyLog: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val screen by vm.screen.collectAsStateWithLifecycle()
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val status by vm.controller.status.collectAsStateWithLifecycle()
    val tvs by vm.store.tvs.collectAsStateWithLifecycle()
    val selectedId by vm.store.selectedId.collectAsStateWithLifecycle()
    val selected by vm.selectedTv.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val shortcuts by vm.store.shortcuts.collectAsStateWithLifecycle()
    val found by vm.discovery.found.collectAsStateWithLifecycle()
    val searching by vm.discovery.searching.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val keyboard by vm.keyboard.collectAsStateWithLifecycle()
    val iconsVersion by vm.iconsVersion.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(Overlay.NONE) }

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeMessage() }
    }

    when (overlay) {
        Overlay.SETTINGS -> {
            BackHandler { overlay = Overlay.NONE }
            SettingsScreen(
                settings = settings,
                tv = selected,
                onBack = { overlay = Overlay.NONE },
                onChange = vm::updateSettings,
                onSaveMac = { vm.setMac(it).also { ok -> if (ok) vm.toast("MAC guardada") } },
                onRepair = { overlay = Overlay.NONE; vm.repairSelected() },
                onForget = { overlay = Overlay.NONE; selected?.let { vm.forgetTv(it.id) } },
                onEditShortcuts = { overlay = Overlay.SHORTCUTS },
                onCopyLog = onCopyLog,
            )
            return
        }
        Overlay.SHORTCUTS -> {
            BackHandler { overlay = Overlay.NONE }
            ShortcutsEditor(
                initial = shortcuts,
                iconFor = vm.icons::iconFor,
                phoneIconsEnabled = settings.phoneAppIcons,
                importIcon = vm::importIcon,
                loadPhoneApps = vm::phoneApps,
            ) { result ->
                result?.let(vm::saveShortcuts)
                overlay = Overlay.NONE
            }
            return
        }
        Overlay.NONE -> Unit
    }

    when (val s = screen) {
        Screen.Devices -> {
            if (selected != null) BackHandler { vm.backFromDevices() }
            DevicesScreen(
                found = found,
                searching = searching,
                saved = tvs,
                selectedId = selectedId,
                onBack = if (selected != null) ({ vm.backFromDevices() }) else null,
                onChoose = vm::chooseTv,
                onSelectSaved = vm::selectSaved,
                onForget = vm::forgetTv,
                onRescan = { vm.discovery.stop(); vm.discovery.start() },
            )
        }
        is Screen.Pairing -> {
            BackHandler { vm.leavePairing() }
            PairingScreen(
                name = s.name, host = s.host, state = pairing,
                onSubmit = vm::submitCode,
                onRetryCode = vm::retryCodeEntry,
                onRestart = { vm.startPairing(s.name, s.host) },
                onCancel = vm::leavePairing,
            )
        }
        Screen.Remote -> {
            val tv = selected
            if (tv == null) {
                LaunchedEffect(Unit) { vm.showDevices() }
                return
            }
            val sender = remember { KeySender(send = vm::press, haptics = vm.haptics) }
            ProvideKeySender(sender) {
                RemoteScreen(
                    RemoteUi(
                        tvName = tv.name,
                        state = state,
                        status = status,
                        shortcuts = shortcuts,
                        iconFor = vm.icons::iconFor,
                        iconsVersion = iconsVersion,
                        onRetry = vm.controller::retryNow,
                        onRepair = vm::repairSelected,
                        onDevices = vm::showDevices,
                        onSettings = { overlay = Overlay.SETTINGS },
                        onShortcut = vm::launch,
                        onEditShortcuts = { overlay = Overlay.SHORTCUTS },
                        onOpenKeyboard = vm::openKeyboard,
                    ),
                )
                keyboard?.let { req ->
                    KeyboardSheet(req, onEdit = vm::sendEdit, onEnter = vm::submitEnter, onClose = vm::closeKeyboard)
                }
            }
        }
    }
}
