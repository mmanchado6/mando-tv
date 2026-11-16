package local.mandotv.net

import local.mandotv.crypto.ClientIdentity
import local.mandotv.crypto.publicKeyFingerprint
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** El certificado de la tele no coincide con el que se guardó al emparejar. */
class PinMismatchException : CertificateException("El certificado de la tele ha cambiado")

/**
 * Comprobación del certificado de la tele.
 *  - Al emparejar no hay nada guardado: se acepta el que presente (el código que ves en la
 *    pantalla de la tele confirma que hablamos con ella).
 *  - Después, solo se acepta la tele cuya clave pública coincide con la guardada.
 */
class TvTrustManager(private val pinnedFingerprint: String?) : X509ExtendedTrustManager() {
    @Volatile var pinMismatch = false
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("La tele no envió certificado")
        if (pinnedFingerprint != null && cert.publicKeyFingerprint() != pinnedFingerprint) {
            pinMismatch = true
            throw PinMismatchException()
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Presenta siempre el certificado del móvil, pida la tele lo que pida. */
class IdentityKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    private val alias = "mandotv"
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

object Tls {
    /** Lo máximo que se espera a que el otro lado conteste durante el handshake. */
    const val HANDSHAKE_TIMEOUT_MS = 5_000

    /**
     * Envuelve un socket TCP ya conectado (por [LanGate]) en TLS y hace el handshake.
     *
     * El handshake tiene tiempo máximo: sin él, un aparato de la red que aceptase la conexión TCP y
     * no contestase nunca dejaría la app colgada para siempre. Si vence, lanza SocketTimeoutException.
     * Al terminar se quita el límite; quien llama pone el suyo para la lectura normal.
     */
    fun wrap(
        socket: Socket,
        host: String,
        identity: ClientIdentity,
        trust: TvTrustManager,
        handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
    ): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(IdentityKeyManager(identity)), arrayOf(trust), null)
        val ssl = context.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.soTimeout = handshakeTimeoutMs
        ssl.startHandshake()
        ssl.soTimeout = 0
        return ssl
    }
}
