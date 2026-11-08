package local.mandotv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.mandotv.data.RecordCodec
import local.mandotv.data.SavedTv
import local.mandotv.data.Shortcut
import local.mandotv.data.Shortcuts
import local.mandotv.protocol.TvKey
import local.mandotv.remote.WakeOnLan
import local.mandotv.ui.pressAndRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLogicTest {

    private fun holdFor(key: TvKey, ms: Long, connected: (Int) -> Boolean = { true }): List<Boolean> = mutableListOf<Boolean>().also { sent ->
        runTest {
            val job = launch { pressAndRepeat(key) { repeat -> connected(sent.size).also { if (it) sent += repeat } } }
            runCurrent()
            advanceTimeBy(ms)
            runCurrent()
            job.cancel() // se suelta el botón
        }
    }

    @Test fun shortPressSendsOnce() {
        assertEquals(listOf(false), holdFor(TvKey.VOLUME_UP, 100))
        assertEquals(listOf(false), holdFor(TvKey.OK, 3000)) // OK no se repite
        assertEquals(listOf(false), holdFor(TvKey.POWER, 3000))
    }

    @Test fun holdingVolumeRepeats() {
        // 1 al pulsar + repeticiones a partir de 400 ms, cada 120 ms: 400, 520, …, 1000 → 6
        val sent = holdFor(TvKey.VOLUME_UP, 1000)
        assertEquals(7, sent.size)
        assertEquals(false, sent.first())
        assertEquals(true, sent.drop(1).all { it })
    }

    @Test fun channelsRepeatSlower() {
        // 400, 750 → 2 repeticiones en 1 s
        assertEquals(3, holdFor(TvKey.CHANNEL_UP, 1000).size)
    }

    @Test fun arrowsRepeat() {
        // 400, 550, 700, 850, 1000 → 5 repeticiones
        assertEquals(6, holdFor(TvKey.RIGHT, 1000).size)
    }

    @Test fun repeatingStopsIfConnectionIsLost() {
        val sent = holdFor(TvKey.VOLUME_DOWN, 5000) { count -> count < 3 }
        assertEquals(3, sent.size)
    }

    @Test fun recordsRoundTripWithSpecialCharacters() {
        val tvs = listOf(
            SavedTv("1", "Salón\tTV \\ «4K»\nNueva", "192.168.1.50", "ab".repeat(32), "AA:BB:CC:DD:EE:FF"),
            SavedTv("2", "Cuarto", "10.0.0.7", "cd".repeat(32)),
        )
        assertEquals(tvs, RecordCodec.tvs(RecordCodec.encodeTvs(tvs)))
        assertEquals(emptyList<SavedTv>(), RecordCodec.tvs(""))
        assertEquals(Shortcuts.defaults, RecordCodec.shortcuts(RecordCodec.encodeShortcuts(Shortcuts.defaults)))
    }

    @Test fun unlimitedShortcutsWithIcons() {
        val many = (1..12).map { Shortcut("$it", "App $it", "https://app$it.tv", if (it % 2 == 0) "logo$it.png" else "", if (it % 3 == 0) "com.app$it" else "") }
        assertEquals(many, RecordCodec.shortcuts(RecordCodec.encodeShortcuts(many)))
        // Formato antiguo (3 campos) sigue leyéndose
        assertEquals(Shortcut("a", "B", "c"), RecordCodec.shortcuts("a\tB\tc".replace("\\t", "\t")).first())
    }

    @Test fun phoneAppsAreNeverReadWhenTheSettingIsOff() {
        var enabled = false
        var queries = 0
        var icons = 0
        val gate = local.mandotv.android.PhoneAppsGate(
            enabled = { enabled },
            query = { queries++; listOf(local.mandotv.android.PhoneApp("Netflix", "com.netflix.mediaclient")) },
            iconOf = { icons++; null },
        )
        assertEquals(emptyList<Any>(), gate.list())
        assertNull(gate.icon("com.netflix.mediaclient"))
        assertEquals("con el ajuste desactivado no se consulta nada", 0, queries + icons)
        enabled = true
        assertEquals(1, gate.list().size)
        gate.icon("com.netflix.mediaclient")
        assertEquals(1, queries); assertEquals(1, icons)
    }

    @Test fun phoneAppIconsAreMatchedByName() {
        val apps = listOf(
            local.mandotv.android.PhoneApp("YouTube", "com.google.android.youtube"),
            local.mandotv.android.PhoneApp("YouTube Music", "com.google.android.apps.youtube.music"),
            local.mandotv.android.PhoneApp("Amazon Prime Video", "com.amazon.avod.thirdpartyclient"),
            local.mandotv.android.PhoneApp("Disney+", "com.disney.disneyplus"),
            local.mandotv.android.PhoneApp("Netflix", "com.netflix.mediaclient"),
        )
        fun m(l: String) = local.mandotv.android.AppIcons.matchByLabel(l, apps)?.packageName
        assertEquals("com.google.android.youtube", m("YouTube"))
        assertEquals("com.amazon.avod.thirdpartyclient", m("Prime Video"))
        assertEquals("com.disney.disneyplus", m("Disney+"))
        assertEquals("com.netflix.mediaclient", m("netflix"))
        assertNull(m("Kodi"))
        assertNull(m("Tv"))
    }

    @Test fun liveTypingLetterByLetter() {
        val sent = mutableListOf<local.mandotv.protocol.RemoteMessages.TextEdit>()
        val sync = local.mandotv.ui.LiveTextSync("") { sent += it; true }
        listOf("h", "ho", "hol", "hola", "hol", "holá", "holá ñ").forEach { sync.onTextChanged(it) }
        val E = local.mandotv.protocol.RemoteMessages::TextEdit
        assertEquals(
            listOf(E(0, "h"), E(0, "o"), E(0, "l"), E(0, "a"), E(1, ""), E(0, "á"), E(0, " ñ")),
            sent,
        )
    }

    @Test fun liveTypingWithoutConnectionRetriesLater() {
        var online = false
        val sent = mutableListOf<local.mandotv.protocol.RemoteMessages.TextEdit>()
        val sync = local.mandotv.ui.LiveTextSync("ya") { if (online) { sent += it; true } else false }
        sync.onTextChanged("yab")
        assertEquals("ya", sync.sent)
        online = true
        sync.onTextChanged("yabc")
        assertEquals(listOf(local.mandotv.protocol.RemoteMessages.TextEdit(0, "bc")), sent)
    }

    @Test fun shortcutLinks() {
        assertEquals("https://www.youtube.com", Shortcuts.normalizeLink(" https://www.youtube.com "))
        assertEquals("plex://", Shortcuts.normalizeLink("plex://"))
        assertEquals("market://launch?id=org.xbmc.kodi", Shortcuts.normalizeLink("org.xbmc.kodi"))
        for (bad in listOf("", "kodi", "hola mundo", "http//x", "com.", ".com.app", "x".repeat(400))) {
            assertNull(bad, Shortcuts.normalizeLink(bad))
        }
        assertEquals("Prime Video", Shortcuts.normalizeLabel("  Prime Video "))
        assertNull(Shortcuts.normalizeLabel("   "))
        assertEquals(16, Shortcuts.normalizeLabel("Un nombre larguísimo de app")!!.length)
    }

    @Test fun broadcastAddress() {
        fun b(ip: String, prefix: Int) = WakeOnLan.broadcastOf(ip.split('.').map { it.toInt().toByte() }.toByteArray(), prefix)!!.hostAddress
        assertEquals("192.168.1.255", b("192.168.1.37", 24))
        assertEquals("10.0.255.255", b("10.0.3.4", 16))
        assertEquals("172.16.0.127", b("172.16.0.100", 25))
    }
}
