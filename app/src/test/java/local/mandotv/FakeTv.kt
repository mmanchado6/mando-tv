package local.mandotv

import local.mandotv.crypto.ClientIdentity
import local.mandotv.crypto.publicKeyFingerprint
import local.mandotv.net.IdentityKeyManager
import local.mandotv.net.LanGate
import local.mandotv.net.LanPolicy
import local.mandotv.net.NetworkBinder
import local.mandotv.protocol.Framing
import local.mandotv.protocol.PairingMessages
import local.mandotv.protocol.ProtoMessage
import local.mandotv.protocol.ProtoWriter
import local.mandotv.protocol.RemoteMessages
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** En los tests se permite 127.x.x.x (la tele falsa escucha ahí); el resto de reglas se mantiene. */
class LoopbackPolicy : LanPolicy() {
    override fun isLocal(address: InetAddress) = address.isLoopbackAddress || super.isLocal(address)
}

object NoBinding : NetworkBinder {
    override fun bind(socket: Socket) = Unit
    override fun bind(socket: DatagramSocket) = Unit
}

fun testGate() = LanGate(LoopbackPolicy(), NoBinding)

/** Identidades RSA de prueba (generarlas cuesta, así que se reutilizan). */
object TestKeys {
    val phone: ClientIdentity by lazy { ClientIdentity.generate("Mando TV") }
    val otherPhone: ClientIdentity by lazy { ClientIdentity.generate("Otro") }
    val tv: ClientIdentity by lazy { ClientIdentity.generate("Tele falsa") }
    val otherTv: ClientIdentity by lazy { ClientIdentity.generate("Tele impostora") }
}

/**
 * Tele de mentira que implementa el lado servidor del protocolo con TLS real, escuchando en
 * [host] (una IP 127.0.0.x distinta por test) en los puertos 6466 y 6467.
 */
class FakeTv(
    val host: String,
    private val identity: ClientIdentity = TestKeys.tv,
    /** Huellas de los móviles emparejados (los que la tele reconoce en el 6466). */
    val paired: MutableSet<String> = Collections.synchronizedSet(mutableSetOf()),
    private val pingIntervalMs: Long = 0,
    private val sendInitialState: Boolean = true,
    /** Si no es null, la tele abre una sesión de voz con este número al recibir la tecla Buscar (84). */
    private val voiceSessionId: Int? = null,
) : Closeable {
    /** Lo recibido de la voz, en orden: «begin:7», «audio:8192» (bytes de muestras), «end:7». */
    val voiceEvents = CopyOnWriteArrayList<String>()
    val shownCodes = LinkedBlockingQueue<String>()
    val receivedKeys = CopyOnWriteArrayList<Int>()
    val receivedRaw = LinkedBlockingQueue<ByteArray>()
    val pingReplies = LinkedBlockingQueue<Int>()
    @Volatile var remoteConnections = 0
        private set
    @Volatile var pairingAttempts = 0
        private set
    @Volatile private var current: SSLSocket? = null
    @Volatile private var closed = false

    private val pairingServer = server(LanPolicy.PORT_PAIRING, requireKnownClient = false)
    private val remoteServer = server(LanPolicy.PORT_REMOTE, requireKnownClient = true)

    init {
        acceptLoop(pairingServer) { handlePairing(it) }
        acceptLoop(remoteServer) { handleRemote(it) }
    }

    val fingerprint: String get() = identity.certificate.publicKeyFingerprint()

    /** Corta la conexión del mando actual, como si se fuera la WiFi un momento. */
    fun dropConnection() { runCatching { current?.close() } }

    fun sendToClient(payload: ByteArray) {
        val s = current ?: error("sin conexión")
        synchronized(s) { Framing.write(s.outputStream, payload) }
    }

    override fun close() {
        closed = true
        runCatching { pairingServer.close() }
        runCatching { remoteServer.close() }
        dropConnection()
    }

    private fun server(port: Int, requireKnownClient: Boolean): SSLServerSocket {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (requireKnownClient && chain[0].publicKeyFingerprint() !in paired) {
                    throw CertificateException("certificado no emparejado")
                }
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(ServerKeyManager(identity)), arrayOf(trust), SecureRandom())
        val s = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(host), port))
        s.needClientAuth = true
        return s
    }

    private fun acceptLoop(server: SSLServerSocket, handler: (SSLSocket) -> Unit) = thread(isDaemon = true) {
        while (!closed) {
            val s = try { server.accept() as SSLSocket } catch (e: Exception) { break }
            thread(isDaemon = true) { runCatching { s.use(handler) } }
        }
    }

    private fun handlePairing(s: SSLSocket) {
        pairingAttempts++
        s.startHandshake()
        val client = s.session.peerCertificates[0] as X509Certificate
        fun reply(field: Int, status: Int = 200, body: ProtoWriter.() -> Unit = {}) {
            Framing.write(s.outputStream, ProtoWriter().int(1, 2).int(2, status).message(field, body).toByteArray())
        }
        expect(s, PairingMessages.F_PAIRING_REQUEST); reply(PairingMessages.F_PAIRING_REQUEST_ACK) { string(1, "Tele falsa") }
        expect(s, PairingMessages.F_OPTIONS); reply(PairingMessages.F_OPTIONS) {
            message(2) { int(1, 3); int(2, 6) }; int(3, 1)
        }
        expect(s, PairingMessages.F_CONFIGURATION); reply(PairingMessages.F_CONFIGURATION_ACK)
        // La tele «muestra» el código en pantalla.
        val nonce = ByteArray(2).also { SecureRandom().nextBytes(it) }
        val hash = expectedHash(client, identity.certificate, nonce)
        val code = "%02X".format(hash[0]) + nonce.joinToString("") { "%02X".format(it) }
        shownCodes.add(code)
        val secret = expect(s, PairingMessages.F_SECRET).message(PairingMessages.F_SECRET)!!.bytes(1)
        if (secret != null && secret.contentEquals(hash)) {
            paired += client.publicKeyFingerprint()
            reply(PairingMessages.F_SECRET_ACK) { bytes(1, hash) }
        } else {
            Framing.write(s.outputStream, ProtoWriter().int(1, 2).int(2, 402).toByteArray())
        }
    }

    private fun handleRemote(s: SSLSocket) {
        s.startHandshake() // falla aquí si el móvil no está emparejado
        remoteConnections++
        current = s
        val out = s.outputStream
        fun send(p: ByteArray) = synchronized(s) { Framing.write(out, p) }
        send(ProtoWriter().message(RemoteMessages.F_CONFIGURE) {
            int(1, 0x26f); message(2) { string(1, "FakeModel"); string(2, "FakeVendor"); string(6, "5.0") }
        }.toByteArray())
        expect(s, RemoteMessages.F_CONFIGURE)
        send(ProtoWriter().message(RemoteMessages.F_SET_ACTIVE) {}.toByteArray())
        expect(s, RemoteMessages.F_SET_ACTIVE)
        if (sendInitialState) {
            send(ProtoWriter().message(RemoteMessages.F_START) { bool(1, true) }.toByteArray())
            send(ProtoWriter().message(RemoteMessages.F_SET_VOLUME_LEVEL) { int(6, 100); int(7, 23); bool(8, false) }.toByteArray())
        }
        if (pingIntervalMs > 0) thread(isDaemon = true) {
            var n = 1
            while (!s.isClosed) {
                Thread.sleep(pingIntervalMs)
                runCatching { send(ProtoWriter().message(RemoteMessages.F_PING_REQUEST) { int(1, n++) }.toByteArray()) }
            }
        }
        while (true) {
            val raw = Framing.read(s.inputStream)
            receivedRaw.add(raw)
            val m = ProtoMessage.parse(raw)
            m.message(RemoteMessages.F_KEY_INJECT)?.let {
                receivedKeys += it.int(1)
                if (it.int(1) == 84 && voiceSessionId != null) {
                    send(ProtoWriter().message(RemoteMessages.F_VOICE_BEGIN) { int(1, voiceSessionId); string(2, "com.google.android.katniss") }.toByteArray())
                }
            }
            m.message(RemoteMessages.F_VOICE_BEGIN)?.let { voiceEvents += "begin:${it.int(1)}" }
            m.message(RemoteMessages.F_VOICE_PAYLOAD)?.let { voiceEvents += "audio:${it.bytes(2)?.size ?: 0}" }
            m.message(RemoteMessages.F_VOICE_END)?.let { voiceEvents += "end:${it.int(1)}" }
            m.message(RemoteMessages.F_PING_RESPONSE)?.let { pingReplies.add(it.int(1)) }
        }
    }

    private fun expect(s: SSLSocket, field: Int): ProtoMessage {
        val m = ProtoMessage.parse(Framing.read(s.inputStream))
        check(m.has(field)) { "se esperaba el campo $field" }
        return m
    }

    fun awaitKeys(count: Int, timeoutMs: Long = 5000): List<Int> {
        val end = System.currentTimeMillis() + timeoutMs
        while (receivedKeys.size < count && System.currentTimeMillis() < end) Thread.sleep(10)
        return receivedKeys.toList()
    }

    fun awaitVoice(last: String, timeoutMs: Long = 5000): List<String> {
        val end = System.currentTimeMillis() + timeoutMs
        while (voiceEvents.lastOrNull() != last && System.currentTimeMillis() < end) Thread.sleep(10)
        return voiceEvents.toList()
    }

    fun nextCode(): String = shownCodes.poll(10, TimeUnit.SECONDS) ?: error("la tele no mostró código")

    companion object {
        /** Implementación independiente del hash (vía cadenas hex, como la de Node.js). */
        fun expectedHash(client: X509Certificate, server: X509Certificate, nonce: ByteArray): ByteArray {
            fun hexBytes(n: java.math.BigInteger): ByteArray {
                var h = n.toString(16)
                if (h.length % 2 == 1) h = "0$h"
                return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            val c = client.publicKey as java.security.interfaces.RSAPublicKey
            val sv = server.publicKey as java.security.interfaces.RSAPublicKey
            return MessageDigest.getInstance("SHA-256").run {
                update(hexBytes(c.modulus)); update(hexBytes(c.publicExponent))
                update(hexBytes(sv.modulus)); update(hexBytes(sv.publicExponent))
                update(nonce); digest()
            }
        }
    }
}

private class ServerKeyManager(private val id: ClientIdentity) : javax.net.ssl.X509ExtendedKeyManager() {
    private val delegate = IdentityKeyManager(id)
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = null
    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("tv")
    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "tv"
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?) = "tv"
    override fun getCertificateChain(alias: String?) = delegate.getCertificateChain(alias)
    override fun getPrivateKey(alias: String?) = delegate.getPrivateKey(alias)
}
