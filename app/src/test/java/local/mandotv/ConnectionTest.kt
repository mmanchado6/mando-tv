package local.mandotv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.mandotv.crypto.ClientIdentity
import local.mandotv.crypto.PairingSecret
import local.mandotv.crypto.publicKeyFingerprint
import local.mandotv.protocol.ProtoMessage
import local.mandotv.protocol.ProtoWriter
import local.mandotv.protocol.RemoteMessages
import local.mandotv.protocol.TvKey
import local.mandotv.remote.ConnectionState
import local.mandotv.remote.ImeEvent
import local.mandotv.remote.PairingClient
import local.mandotv.remote.RemoteController
import local.mandotv.remote.TvTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pruebas de extremo a extremo contra [FakeTv]: TLS real, mensajes reales, sockets reales
 * (en 127.0.0.x). Lo único que cambia respecto a la app es que aquí se permite el loopback.
 */
class ConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tvs = mutableListOf<FakeTv>()

    private fun tv(n: Int, pingMs: Long = 0, identity: ClientIdentity = TestKeys.tv, pairedWith: ClientIdentity? = TestKeys.phone): FakeTv {
        val paired = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        pairedWith?.let { paired += it.certificate.publicKeyFingerprint() }
        return FakeTv("127.0.0.$n", identity, paired, pingIntervalMs = pingMs).also { tvs += it }
    }

    private fun controller(
        identity: ClientIdentity = TestKeys.phone,
        onUnreachable: (TvTarget) -> Unit = {},
        idleMs: Long = 16_000,
        pollMs: Int = 4_000,
    ) = RemoteController(
        testGate(), { identity }, scope, onUnreachable = onUnreachable, log = { println("[mando] $it") },
        idleTimeoutMs = idleMs, idlePollMs = pollMs,
    ).also { it.start() }

    private fun target(host: String, fp: String = TestKeys.tv.certificate.publicKeyFingerprint()) =
        TvTarget("id", "Salón", host, fp)

    private fun RemoteController.await(timeoutMs: Long = 10_000, pred: (ConnectionState) -> Boolean): ConnectionState =
        runBlocking { withTimeout(timeoutMs) { state.first(pred) } }

    @After fun tearDown() {
        scope.cancel()
        tvs.forEach { it.close() }
    }

    @Test fun pairingWithTheCodeShownOnTheTv() = runBlocking {
        val fake = tv(11, pairedWith = null)
        val session = PairingClient.start(testGate(), fake.host, TestKeys.phone)
        val code = fake.nextCode()
        assertEquals(fake.fingerprint, session.serverCertificate.publicKeyFingerprint())
        session.submitCode(code.lowercase())
        assertTrue("la tele debe recordar el móvil", TestKeys.phone.certificate.publicKeyFingerprint() in fake.paired)
    }

    @Test fun mistypedCodeIsCaughtAndCanBeRetried() = runBlocking {
        val fake = tv(12, pairedWith = null)
        val session = PairingClient.start(testGate(), fake.host, TestKeys.phone)
        val code = fake.nextCode()
        val wrong = "%02X".format((code.substring(0, 2).toInt(16) + 1) and 0xFF) + code.substring(2)
        try { session.submitCode(wrong); fail() } catch (_: PairingSecret.InvalidCodeException) {}
        assertFalse(TestKeys.phone.certificate.publicKeyFingerprint() in fake.paired)
        session.submitCode(code) // la misma sesión sigue valiendo
        assertTrue(TestKeys.phone.certificate.publicKeyFingerprint() in fake.paired)
    }

    @Test fun pairThenControl() {
        val fake = tv(13, pairedWith = null)
        runBlocking {
            val s = PairingClient.start(testGate(), fake.host, TestKeys.phone)
            s.submitCode(fake.nextCode())
        }
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        assertTrue(c.send(TvKey.HOME))
        assertEquals(listOf(3), fake.awaitKeys(1))
    }

    @Test fun keysArriveInOrderAndTvStateIsShown() {
        val fake = tv(14)
        val c = controller()
        assertFalse("sin conexión no se envía nada", c.send(TvKey.OK))
        c.setTarget(target(fake.host)); c.setActive(true)
        assertEquals(ConnectionState.Connected("Salón"), c.await { it is ConnectionState.Connected })
        val keys = listOf(TvKey.VOLUME_UP, TvKey.VOLUME_UP, TvKey.MUTE, TvKey.UP, TvKey.OK, TvKey.NUM_7, TvKey.RED, TvKey.POWER)
        keys.forEach { assertTrue(c.send(it)) }
        assertEquals(keys.map { it.code }, fake.awaitKeys(keys.size))
        runBlocking { withTimeout(5000) { c.status.first { it.powerOn == true && it.volume == 23 } } }
        assertEquals(100, c.status.value.volumeMax)
    }

    @Test fun answersPings() {
        val fake = tv(15, pingMs = 100)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        assertEquals(1, fake.pingReplies.poll(5, TimeUnit.SECONDS))
        assertEquals(2, fake.pingReplies.poll(5, TimeUnit.SECONDS))
    }

    @Test fun reconnectsByItselfAfterADrop() {
        val fake = tv(16)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        fake.dropConnection()
        c.await { it is ConnectionState.Waiting || it is ConnectionState.Connecting }
        c.await { it is ConnectionState.Connected }
        assertEquals(2, fake.remoteConnections)
        assertTrue(c.send(TvKey.BACK))
        assertEquals(listOf(4), fake.awaitKeys(1))
    }

    @Test fun pressingKeysKeepsTheConnectionAliveEvenWithoutPings() {
        // La tele no hace ping mientras recibe órdenes: eso NO debe contar como conexión muerta
        val fake = tv(24) // sin pings
        val c = controller(idleMs = 1_500, pollMs = 200)
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        repeat(12) { assertTrue(c.send(TvKey.RIGHT)); Thread.sleep(300) }
        assertEquals(1, fake.remoteConnections)
        assertTrue(c.isConnected)
    }

    @Test fun silenceInBothDirectionsReconnects() {
        val fake = tv(25) // sin pings
        val c = controller(idleMs = 1_000, pollMs = 200)
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        c.await { it is ConnectionState.Waiting || it is ConnectionState.Connecting }
        c.await { it is ConnectionState.Connected }
        assertTrue(fake.remoteConnections >= 2)
    }

    @Test fun tvClosingItsKeyboardClosesOurs() {
        val fake = tv(26)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        val events = java.util.concurrent.CopyOnWriteArrayList<ImeEvent>()
        val job = scope.launch { c.imeEvents.collect { events += it } }
        Thread.sleep(100)
        fake.sendToClient(ProtoWriter().message(RemoteMessages.F_IME_KEY_INJECT) {
            message(1) { int(1, 4) }; message(2) { int(1, 166) }
        }.toByteArray())
        fake.sendToClient(ProtoWriter().message(RemoteMessages.F_IME_CLOSED) {}.toByteArray())
        val end = System.currentTimeMillis() + 5000
        while (events.size < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf(ImeEvent.Show("", ""), ImeEvent.Hide), events.toList())
        job.cancel()
    }

    @Test fun longPressDirectionsReachTheTv() {
        val fake = tv(27)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        c.send(TvKey.OK, RemoteMessages.START_LONG)
        c.send(TvKey.OK, RemoteMessages.END_LONG)
        val dirs = generateSequence { fake.receivedRaw.poll(5, TimeUnit.SECONDS) }.map { ProtoMessage.parse(it) }
            .mapNotNull { it.message(RemoteMessages.F_KEY_INJECT) }.take(2).map { it.int(1) to it.int(2) }.toList()
        assertEquals(listOf(23 to 1, 23 to 2), dirs)
    }

    @Test fun unknownPhoneMustPairAgain() {
        val fake = tv(17, pairedWith = null) // la tele no conoce este móvil (p. ej. se reseteó)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it == ConnectionState.NeedsPairing }
    }

    @Test fun impostorTvIsRejected() {
        val fake = tv(18, identity = TestKeys.otherTv) // otro aparato con la IP de nuestra tele
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it == ConnectionState.CertificateChanged }
        assertEquals(0, fake.receivedKeys.size)
    }

    @Test fun unreachableTvRetriesAndAsksForRediscovery() {
        val unreachable = AtomicInteger()
        val c = controller(onUnreachable = { unreachable.incrementAndGet() })
        c.setTarget(target("127.0.0.19")) // nadie escucha ahí
        c.setActive(true)
        val waiting = c.await { it is ConnectionState.Waiting } as ConnectionState.Waiting
        assertEquals("La tele no responde", waiting.reason)
        c.await { (it as? ConnectionState.Connecting)?.attempt == 2 }
        assertTrue(unreachable.get() >= 1)
        // Si la tele aparece, se conecta sola
        tv(19)
        c.retryNow()
        c.await { it is ConnectionState.Connected }
    }

    @Test fun pausingClosesTheConnectionAndResumingReconnects() {
        val fake = tv(20)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        c.setActive(false)
        c.await { it == ConnectionState.Paused }
        assertFalse(c.send(TvKey.OK))
        c.setActive(true)
        c.await { it is ConnectionState.Connected }
        assertEquals(2, fake.remoteConnections)
        c.setWifiAvailable(false)
        c.await { it == ConnectionState.NoWifi }
    }

    @Test fun tvAsksForTextAndTypingIsSentLetterByLetter() {
        val fake = tv(22)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        val event = scope.async { c.imeEvents.first() }
        Thread.sleep(100)
        fake.sendToClient(ProtoWriter().message(RemoteMessages.F_IME_SHOW_REQUEST) {
            message(2) { int(1, 6); string(2, "ab"); string(6, "Buscar") }
        }.toByteArray())
        assertEquals(ImeEvent.Show("ab", "Buscar"), runBlocking { withTimeout(5000) { event.await() } })
        // El usuario borra la «b» y escribe «cd»: 1 «borrar» y luego el texto, en orden
        assertTrue(c.sendEdit(RemoteMessages.TextEdit(1, "cd")))
        val received = generateSequence { fake.receivedRaw.poll(5, TimeUnit.SECONDS) }.map { ProtoMessage.parse(it) }
            .filter { it.has(RemoteMessages.F_KEY_INJECT) || it.has(RemoteMessages.F_IME_BATCH_EDIT) }.take(2).toList()
        assertEquals(TvKey.DEL.code, received[0].message(RemoteMessages.F_KEY_INJECT)!!.int(1))
        val batch = received[1].message(RemoteMessages.F_IME_BATCH_EDIT)!!
        assertEquals(6, batch.int(2)) // contador del campo que indicó la tele
        assertEquals("cd", batch.message(3)!!.message(2)!!.string(3))
    }

    @Test fun focusedSearchFieldOpensKeyboardOnceAndUnfocusClosesIt() {
        val fake = tv(23)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        val events = java.util.concurrent.CopyOnWriteArrayList<ImeEvent>()
        val job = scope.launch { c.imeEvents.collect { events += it } }
        Thread.sleep(100)
        fun inject(field: Boolean) = fake.sendToClient(ProtoWriter().message(RemoteMessages.F_IME_KEY_INJECT) {
            message(1) { int(1, 215); string(12, "com.google.android.youtube.tv") }
            if (field) message(2) { int(1, 701); string(2, "ga"); int(5, 1) }
        }.toByteArray())
        inject(false); inject(true); inject(true); inject(false)
        val end = System.currentTimeMillis() + 5000
        while (events.size < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals(listOf(ImeEvent.Show("ga", ""), ImeEvent.Hide), events.toList())
        // El texto usa los contadores de ese campo
        inject(true)
        Thread.sleep(200)
        assertTrue(c.sendEdit(RemoteMessages.TextEdit(0, "tos")))
        val batch = generateSequence { fake.receivedRaw.poll(5, TimeUnit.SECONDS) }.map { ProtoMessage.parse(it) }
            .first { it.has(RemoteMessages.F_IME_BATCH_EDIT) }.message(RemoteMessages.F_IME_BATCH_EDIT)!!
        assertEquals(215, batch.int(1)); assertEquals(701, batch.int(2))
        // La tele confirma con un contador de campo nuevo: no reabre el teclado, pero se usa ese contador
        val before = events.size
        fake.sendToClient(ProtoWriter().message(RemoteMessages.F_IME_SHOW_REQUEST) {
            message(2) { int(1, 704); string(2, "gatos"); int(3, 5); int(4, 5) }
        }.toByteArray())
        Thread.sleep(200)
        assertEquals(before, events.size)
        assertTrue(c.sendEdit(RemoteMessages.TextEdit(0, "!")))
        val next = generateSequence { fake.receivedRaw.poll(5, TimeUnit.SECONDS) }.map { ProtoMessage.parse(it) }
            .first { it.has(RemoteMessages.F_IME_BATCH_EDIT) }.message(RemoteMessages.F_IME_BATCH_EDIT)!!
        assertEquals(704, next.int(2))
        job.cancel()
    }

    @Test fun textUsesTheCountersOfTheFocusedField() {
        val fake = tv(21)
        val c = controller()
        c.setTarget(target(fake.host)); c.setActive(true)
        c.await { it is ConnectionState.Connected }
        fake.sendToClient(ProtoWriter().message(RemoteMessages.F_IME_KEY_INJECT) {
            message(1) { int(1, 8); string(12, "com.google.android.youtube.tv") }
            message(2) { int(1, 3) }
        }.toByteArray())
        runBlocking { withTimeout(5000) { c.status.first { it.currentApp == "com.google.android.youtube.tv" } } }
        assertTrue(c.sendText("canción"))
        assertTrue(c.launchApp("https://www.youtube.com"))
        val batch = generateSequence { fake.receivedRaw.poll(5, TimeUnit.SECONDS) }
            .map { ProtoMessage.parse(it) }.first { it.has(RemoteMessages.F_IME_BATCH_EDIT) }
            .message(RemoteMessages.F_IME_BATCH_EDIT)!!
        assertEquals(8, batch.int(1))
        assertEquals(3, batch.int(2))
        assertEquals("canción", batch.message(3)!!.message(2)!!.string(3))
        val link = generateSequence { fake.receivedRaw.poll(5, TimeUnit.SECONDS) }
            .map { ProtoMessage.parse(it) }.first { it.has(RemoteMessages.F_APP_LINK) }
        assertEquals("https://www.youtube.com", link.message(RemoteMessages.F_APP_LINK)!!.string(1))
    }
}
