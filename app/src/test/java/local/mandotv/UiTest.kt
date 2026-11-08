package local.mandotv

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import local.mandotv.android.DiscoveredTv
import local.mandotv.data.SavedTv
import local.mandotv.data.Settings
import local.mandotv.data.Shortcut
import local.mandotv.data.Shortcuts
import local.mandotv.protocol.TvKey
import local.mandotv.remote.ConnectionState
import local.mandotv.remote.TvStatus
import local.mandotv.ui.DevicesScreen
import local.mandotv.ui.KeySender
import local.mandotv.ui.LogoChooser
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.mandotv.ui.KeyboardRequest
import local.mandotv.ui.KeyboardSheet
import local.mandotv.ui.MandoTheme
import local.mandotv.ui.PairingScreen
import local.mandotv.ui.PairingUi
import local.mandotv.ui.ProvideKeySender
import local.mandotv.ui.RemoteScreen
import local.mandotv.ui.RemoteTab
import local.mandotv.ui.RemoteUi
import local.mandotv.ui.SettingsScreen
import local.mandotv.ui.ShortcutsEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pruebas de la interfaz con Robolectric (sin móvil). Además guardan capturas PNG de cada
 * pantalla en salida/capturas/ para revisar el diseño.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h820dp-night-xxhdpi")
class UiTest {
    @get:Rule val rule = createComposeRule()

    private val sent = CopyOnWriteArrayList<TvKey>()
    private val directions = CopyOnWriteArrayList<Int>()
    private val vibrations = CopyOnWriteArrayList<String>()
    private val haptics = object : local.mandotv.ui.Haptics {
        override fun tap() { vibrations += "tap" }
        override fun tick() { vibrations += "tick" }
        override fun long() { vibrations += "long" }
    }

    private fun ui(
        state: ConnectionState = ConnectionState.Connected("Salón"),
        status: TvStatus = TvStatus(powerOn = true, volume = 14, volumeMax = 100),
        shortcuts: List<Shortcut> = Shortcuts.defaults,
    ) = RemoteUi(
        tvName = "SONY-BRAVIA-49", state = state, status = status, shortcuts = shortcuts, iconFor = { null },
        onRetry = {}, onRepair = {}, onDevices = {}, onSettings = {}, onShortcut = {}, onEditShortcuts = {}, onOpenKeyboard = {},
    )

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            MandoTheme { ProvideKeySender(KeySender(send = { k, d -> sent += k; directions += d; true }, haptics = haptics)) { content() } }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("capturas.dir") ?: "build/capturas").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** El último control de la pestaña debe quedar por encima de la barra de pestañas (sin scroll). */
    private fun assertFits(lastControl: String) {
        val control = rule.onNodeWithContentDescription(lastControl).getBoundsInRoot()
        val tabs = rule.onNodeWithText("Mando").getBoundsInRoot()
        assertTrue("«$lastControl» (${control.bottom}) no cabe sobre las pestañas (${tabs.top})", control.bottom <= tabs.top)
        assertTrue("«$lastControl» es demasiado pequeño", control.bottom - control.top >= 24.dp)
    }

    @Test fun mainRemote() {
        show { RemoteScreen(ui()) }
        capture("1-mando")
        assertFits("Bajar volumen")
        rule.onNodeWithContentDescription("Inicio").performClick()
        rule.onNodeWithContentDescription("OK").performClick()
        rule.onNodeWithContentDescription("Encender o apagar").performClick()
        rule.onNodeWithContentDescription("Silencio").performClick()
        rule.onNodeWithContentDescription("TV").performClick()
        assertEquals(listOf(TvKey.HOME, TvKey.OK, TvKey.POWER, TvKey.MUTE, TvKey.TV), sent.toList())
    }

    @Test fun everyPressVibrates() {
        show { RemoteScreen(ui()) }
        rule.onNodeWithContentDescription("Inicio").performClick()
        rule.onNodeWithContentDescription("Atrás").performClick()
        assertEquals(listOf("tap", "tap"), vibrations.toList())
    }

    @Test fun holdingOkSendsALongPress() {
        show { RemoteScreen(ui()) }
        rule.mainClock.autoAdvance = false
        // Toque corto: pulsación normal al soltar
        rule.onNodeWithContentDescription("OK").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithContentDescription("OK").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf(local.mandotv.protocol.RemoteMessages.SHORT), directions.toList())
        // Mantener: «mantener» a los 500 ms y «soltar» al levantar el dedo
        directions.clear()
        rule.onNodeWithContentDescription("OK").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_200)
        rule.onNodeWithContentDescription("OK").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(
            listOf(local.mandotv.protocol.RemoteMessages.START_LONG, local.mandotv.protocol.RemoteMessages.END_LONG),
            directions.toList(),
        )
        assertTrue(sent.all { it == TvKey.OK })
        assertTrue("debe vibrar más fuerte al mantener", "long" in vibrations)
    }

    @Test fun muteIsHighlightedWhenTvIsMuted() {
        show { RemoteScreen(ui(status = TvStatus(volume = 14, volumeMax = 100, muted = true))) }
        rule.onNodeWithText("En silencio").assertExists()
        rule.onNodeWithContentDescription("Tele en silencio").assertExists()
        capture("1b-mando-silencio")
    }

    @Test fun removedButtonsAreGone() {
        show { RemoteScreen(ui()) }
        for (gone in listOf("Guía", "Menú", "Subtítulos", "Ajustes de la tele", "Info", "Fuente")) {
            rule.onNodeWithContentDescription(gone).assertDoesNotExist()
        }
        rule.onNodeWithText("123").performClick()
        rule.onNodeWithContentDescription("Guía").assertDoesNotExist()
    }

    @Test fun holdingVolumeRepeatsAndReleasingStops() {
        show { RemoteScreen(ui()) }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Subir volumen").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithContentDescription("Subir volumen").performTouchInput { up() }
        val whileHeld = sent.size
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("se esperaban varias repeticiones, hubo $whileHeld", whileHeld in 5..9)
        assertEquals("al soltar debe parar", whileHeld, sent.size)
        assertTrue(sent.all { it == TvKey.VOLUME_UP })
    }

    @Test fun otherTabs() {
        show { RemoteScreen(ui(), initialTab = RemoteTab.MEDIA) }
        capture("2-multimedia")
        assertFits("Bajar volumen")
        rule.onNodeWithText("123").performClick(); capture("3-numeros")
        assertFits("Canal siguiente")
        rule.onNodeWithContentDescription("7").performClick()
        rule.onNodeWithContentDescription("Rojo (A)").assertDoesNotExist() // los colores no funcionan en la Sony
        assertEquals(listOf(TvKey.NUM_7), sent.toList())
    }

    @Test fun manyShortcuts() {
        val many = Shortcuts.defaults + listOf("Kodi", "Plex", "Spotify", "Twitch", "Filmin", "RTVE Play").map {
            Shortcut(it, it, "market://launch?id=com.${it.lowercase().replace(" ", "")}")
        }
        show { RemoteScreen(ui(shortcuts = many), initialTab = RemoteTab.APPS) }
        capture("4-apps")
    }

    // --- El mando debe caber sin scroll en móviles pequeños y grandes (alto útil, sin barras del sistema)

    @Config(qualifiers = "w320dp-h500dp-night-xhdpi")
    @Test fun fitsOnATinyPhone() = fitsEverywhere("5a-mando-pequeno")

    @Config(qualifiers = "w360dp-h580dp-night-xhdpi")
    @Test fun fitsOnASmallPhone() = fitsEverywhere("5b-mando-mediano")

    @Config(qualifiers = "w600dp-h900dp-night-xhdpi")
    @Test fun fitsOnATablet() = fitsEverywhere("5c-mando-tablet")

    private fun fitsEverywhere(name: String) {
        show { RemoteScreen(ui()) }
        capture(name)
        assertFits("Bajar volumen")
        rule.onNodeWithText("Multimedia").performClick()
        assertFits("Bajar volumen")
        rule.onNodeWithText("123").performClick()
        assertFits("Canal siguiente")
    }

    @Test fun reconnectingBanner() {
        show { RemoteScreen(ui(ConnectionState.Waiting(System.currentTimeMillis() + 4000, "Se cortó la conexión"))) }
        rule.onNodeWithText("Reintentar").assertExists()
        capture("6-reconectando")
    }

    @Test fun keyboardPanel() {
        show {
            RemoteScreen(ui())
            KeyboardSheet(KeyboardRequest(1, "star", "Buscar en YouTube"), onEdit = { true }, onEnter = { sent += TvKey.ENTER }, onClose = {})
        }
        rule.onNodeWithText("Escribiendo en la tele").assertExists()
        capture("7-teclado")
        rule.onNodeWithText("Intro").performClick()
        assertEquals(listOf(TvKey.ENTER), sent.toList())
    }

    @Test fun devices() {
        show {
            DevicesScreen(
                found = listOf(DiscoveredTv("SONY-BRAVIA-49", "192.168.1.50"), DiscoveredTv("Dormitorio", "192.168.1.61")),
                searching = true,
                saved = listOf(SavedTv("1", "SONY-BRAVIA-49", "192.168.1.50", "ab".repeat(32))),
                selectedId = "1", onBack = {}, onChoose = { _, _ -> }, onSelectSaved = {}, onForget = {}, onRescan = {},
            )
        }
        capture("8-teles")
    }

    @Test fun pairing() {
        show { PairingScreen("Dormitorio", "192.168.1.61", PairingUi.WaitingCode, {}, {}, {}, {}) }
        capture("9-emparejar")
    }

    @Test fun settings() {
        show {
            SettingsScreen(
                Settings(phoneVolumeKeys = true), SavedTv("1", "SONY-BRAVIA-49", "192.168.1.50", "ab".repeat(32), "AA:BB:CC:DD:EE:FF"),
                {}, {}, { true }, {}, {}, {}, {},
            )
        }
        rule.onNodeWithText("Iconos de apps del móvil").assertExists()
        capture("10-ajustes")
    }

    @Test fun shortcutsEditorHasNoLimit() {
        show { ShortcutsEditor(Shortcuts.defaults, { null }, phoneIconsEnabled = false, importIcon = { null }, loadPhoneApps = { emptyList() }) {} }
        rule.onNodeWithText("Añadir acceso").assertExists()
        capture("11-accesos")
        rule.onNodeWithContentDescription("Quitar YouTube").performClick()
        rule.onNodeWithText("YouTube").assertDoesNotExist()
    }

    @Test fun logoChooserRespectsThePhoneAppsSetting() {
        var enabled by mutableStateOf(false)
        show { LogoChooser(Shortcuts.defaults[1], { null }, enabled, {}, {}, {}) }
        rule.onNodeWithText("Logo de la galería").assertExists()
        // Con el ajuste desactivado no se ofrece elegir apps del móvil
        rule.onNodeWithText("Icono de una app del móvil").assertDoesNotExist()
        enabled = true
        rule.onNodeWithText("Icono de una app del móvil").assertExists()
    }
}
