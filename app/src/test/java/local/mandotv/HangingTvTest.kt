package local.mandotv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.mandotv.crypto.publicKeyFingerprint
import local.mandotv.net.LanPolicy
import local.mandotv.net.Tls
import local.mandotv.net.TvTrustManager
import local.mandotv.remote.ConnectionState
import local.mandotv.remote.PairingClient
import local.mandotv.remote.PairingException
import local.mandotv.remote.RemoteController
import local.mandotv.remote.TvTarget
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Un aparato de la WiFi que acepta la conexión TCP pero no habla nunca (un «agujero negro»):
 * la app no debe quedarse colgada esperando el handshake TLS, tiene que soltarlo y reintentar.
 */
class HangingTvTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val holes = mutableListOf<BlackHole>()

    /** Acepta conexiones en [host]:[port] y las deja abiertas sin enviar ni leer nada. */
    private class BlackHole(host: String, port: Int) : Closeable {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(host), port))
        }
        private val accepted = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        @Volatile var connections = 0
            private set

        init {
            thread(isDaemon = true) {
                while (true) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    connections++
                    accepted += s
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @After fun tearDown() {
        scope.cancel()
        holes.forEach { it.close() }
    }

    private fun hole(host: String, port: Int) = BlackHole(host, port).also { holes += it }

    @Test fun handshakeWithASilentDeviceTimesOut() {
        val h = hole("127.0.0.90", LanPolicy.PORT_REMOTE)
        val tcp = testGate().connectTcp("127.0.0.90", LanPolicy.PORT_REMOTE)
        val started = System.nanoTime()
        try {
            Tls.wrap(tcp, "127.0.0.90", TestKeys.phone, TvTrustManager(null), handshakeTimeoutMs = 300)
            fail("el handshake con un aparato mudo debía fallar")
        } catch (e: java.io.IOException) {
            // SocketTimeoutException, a veces envuelta en una SSLException
            val chain = generateSequence<Throwable>(e) { it.cause }
            assertTrue("debía ser un vencimiento de tiempo, no: $e", chain.any { it is SocketTimeoutException })
        } finally {
            runCatching { tcp.close() }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("tardó ${elapsedMs} ms", elapsedMs < 3_000)
        assertTrue(h.connections >= 1)
    }

    @Test fun pairingWithASilentDeviceFailsInsteadOfHanging() {
        hole("127.0.0.91", LanPolicy.PORT_PAIRING)
        try {
            runBlocking {
                withTimeout(15_000) { PairingClient.start(testGate(), "127.0.0.91", TestKeys.phone) }
            }
            fail("el emparejamiento con un aparato mudo debía fallar")
        } catch (e: PairingException) {
            // correcto: error normal que la pantalla enseña al usuario
        }
    }

    @Test fun remoteConnectionToASilentDeviceKeepsRetrying() {
        val h = hole("127.0.0.92", LanPolicy.PORT_REMOTE)
        val c = RemoteController(testGate(), { TestKeys.phone }, scope, log = { println("[mando] $it") }).also { it.start() }
        c.setTarget(TvTarget("id", "Salón", "127.0.0.92", TestKeys.tv.certificate.publicKeyFingerprint()))
        c.setActive(true)
        // Tras el vencimiento del handshake pasa a «esperando» y programa otro intento (no se queda en «conectando»).
        runBlocking { withTimeout(15_000) { c.state.first { it is ConnectionState.Waiting } } }
        assertTrue(h.connections >= 1)
    }
}
