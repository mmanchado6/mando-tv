package local.mandotv.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.mandotv.crypto.ClientIdentity
import local.mandotv.crypto.PairingSecret
import local.mandotv.net.LanGate
import local.mandotv.net.LanPolicy
import local.mandotv.net.Tls
import local.mandotv.net.TvTrustManager
import local.mandotv.protocol.Framing
import local.mandotv.protocol.PairingMessages
import java.io.Closeable
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Emparejamiento en el puerto 6467:
 *  1. [start]: petición → opciones → configuración. La tele muestra entonces un código.
 *  2. [PairingSession.submitCode]: el usuario escribe el código, se envía el secreto y la tele
 *     confirma. A partir de ahí la tele reconoce el certificado del móvil.
 */
object PairingClient {
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun start(
        gate: LanGate,
        host: String,
        identity: ClientIdentity,
        clientName: String = "Mando TV",
    ): PairingSession = withContext(Dispatchers.IO) {
        val tcp = gate.connectTcp(host, LanPolicy.PORT_PAIRING)
        val ssl = try {
            Tls.wrap(tcp, host, identity, TvTrustManager(pinnedFingerprint = null))
        } catch (e: Exception) {
            runCatching { tcp.close() }
            throw PairingException("No se pudo establecer la conexión segura con la tele", e)
        }
        try {
            ssl.soTimeout = READ_TIMEOUT_MS
            val server = ssl.session.peerCertificates.first() as X509Certificate
            exchange(ssl, PairingMessages.pairingRequest(clientName), PairingMessages.F_PAIRING_REQUEST_ACK)
            exchange(ssl, PairingMessages.options(), PairingMessages.F_OPTIONS)
            exchange(ssl, PairingMessages.configuration(), PairingMessages.F_CONFIGURATION_ACK)
            PairingSession(ssl, server, identity)
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw if (e is PairingException) e else PairingException("La tele no aceptó el emparejamiento", e)
        }
    }

    internal fun exchange(ssl: SSLSocket, request: ByteArray, expected: Int) {
        Framing.write(ssl.outputStream, request)
        val reply = PairingMessages.parse(Framing.read(ssl.inputStream))
        if (!reply.ok) {
            throw PairingException(
                when (reply.status) {
                    402 -> "Código incorrecto"
                    else -> "La tele respondió con un error (${reply.status})"
                },
            )
        }
        if (reply.kind != expected) throw PairingException("Respuesta inesperada de la tele")
    }
}

class PairingSession internal constructor(
    private val ssl: SSLSocket,
    /** Certificado de la tele: se guarda su huella para reconocerla después. */
    val serverCertificate: X509Certificate,
    private val identity: ClientIdentity,
) : Closeable {

    /**
     * Comprueba el código localmente (suma de control) y, si es correcto, lo envía.
     * Lanza [PairingSecret.InvalidCodeException] si está mal escrito (la sesión sigue abierta
     * y se puede volver a intentar) o [PairingException] si falla la comunicación.
     */
    suspend fun submitCode(code: String) = withContext(Dispatchers.IO) {
        val secret = PairingSecret.compute(identity.certificate, serverCertificate, code)
        try {
            PairingClient.exchange(ssl, PairingMessages.secret(secret), PairingMessages.F_SECRET_ACK)
        } catch (e: PairingException) {
            throw e
        } catch (e: Exception) {
            throw PairingException("Se perdió la conexión con la tele. Vuelve a empezar el emparejamiento.", e)
        } finally {
            close()
        }
    }

    override fun close() {
        runCatching { ssl.close() }
    }
}
