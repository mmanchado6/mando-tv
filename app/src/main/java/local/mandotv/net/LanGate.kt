package local.mandotv.net

import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Se lanza cuando se intenta conectar a algo que no es la red local. Nunca se abre el socket. */
class BlockedDestinationException(message: String) : IOException(message)

/**
 * Reglas de «solo red local». Toda conexión de la app pasa por [LanGate], que las aplica
 * ANTES de abrir el socket:
 *  1. Solo direcciones IPv4 escritas en número: jamás se consulta un DNS.
 *  2. Solo rangos privados: 10.0.0.0/8, 172.16.0.0/12 y 192.168.0.0/16.
 *  3. Solo los puertos del protocolo (6466 mando, 6467 emparejamiento) y el 9 para Wake-on-LAN.
 */
open class LanPolicy {
    open val allowedTcpPorts: Set<Int> = setOf(PORT_REMOTE, PORT_PAIRING)

    /** Convierte texto a IPv4 sin resolver nombres. Devuelve null si no es una IPv4 válida. */
    fun parseIpv4(text: String): Inet4Address? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null // sin ceros a la izquierda (ambiguo: octal)
            val v = p.toInt()
            if (v > 255) return null
            bytes[i] = v.toByte()
        }
        // getByAddress no hace ninguna consulta de red.
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    open fun isLocal(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address.map { it.toInt() and 0xFF }
        return when {
            b[0] == 10 -> true
            b[0] == 172 && b[1] in 16..31 -> true
            b[0] == 192 && b[1] == 168 -> true
            else -> false
        }
    }

    /** Valida un destino TCP y devuelve su dirección, o lanza [BlockedDestinationException]. */
    fun checkTcp(host: String, port: Int): InetAddress {
        val address = parseIpv4(host) ?: throw BlockedDestinationException("«$host» no es una IP local válida")
        if (!isLocal(address)) throw BlockedDestinationException("$host no es una dirección de la red local")
        if (port !in allowedTcpPorts) throw BlockedDestinationException("Puerto $port no permitido")
        return address
    }

    /**
     * Valida el destino de un paquete Wake-on-LAN: la dirección de difusión de la propia red
     * (p. ej. 192.168.1.255) o la IP de la tele, siempre dentro de los rangos privados, puerto 9.
     */
    fun checkWakeOnLan(address: InetAddress, port: Int) {
        if (!isLocal(address)) throw BlockedDestinationException("Destino de Wake-on-LAN fuera de la red local")
        if (port != PORT_WOL) throw BlockedDestinationException("Puerto $port no permitido para Wake-on-LAN")
    }

    companion object {
        const val PORT_REMOTE = 6466
        const val PORT_PAIRING = 6467
        const val PORT_WOL = 9
    }
}

/**
 * Ata los sockets a la red WiFi (o Ethernet) para que nunca salgan por datos móviles.
 * En Android lo implementa [local.mandotv.android.WifiBinder]; en los tests, uno que no hace nada.
 */
interface NetworkBinder {
    /** Lanza IOException si no hay WiFi. */
    fun bind(socket: Socket)
    fun bind(socket: DatagramSocket)
}

/** Puerta única de red: toda conexión de la app se crea aquí. */
class LanGate(
    val policy: LanPolicy,
    private val binder: NetworkBinder,
) {
    fun connectTcp(host: String, port: Int, timeoutMs: Int = 5000): Socket {
        val address = policy.checkTcp(host, port)
        val socket = Socket()
        try {
            binder.bind(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    fun sendUdp(address: InetAddress, port: Int, payload: ByteArray, broadcast: Boolean) {
        policy.checkWakeOnLan(address, port)
        DatagramSocket().use { s ->
            binder.bind(s)
            s.broadcast = broadcast
            s.send(java.net.DatagramPacket(payload, payload.size, address, port))
        }
    }
}
