package local.mandotv.remote

import local.mandotv.net.LanGate
import local.mandotv.net.LanPolicy
import java.net.InetAddress

/**
 * Wake-on-LAN: «paquete mágico» = 6 bytes 0xFF seguidos de 16 repeticiones de la MAC de la tele.
 * Se envía por UDP (puerto 9) a la dirección de difusión de tu WiFi y a la IP de la tele.
 * Nunca sale de la red local: [LanGate] lo comprueba.
 */
object WakeOnLan {
    /** Acepta «AA:BB:CC:DD:EE:FF», «AA-BB-…» o «AABBCCDDEEFF». Devuelve null si no es válida. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().replace(":", "").replace("-", "").replace(".", "")
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val mac = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        if (mac.all { it == 0.toByte() } || mac.all { it == 0xFF.toByte() }) return null
        return mac
    }

    /** Dirección de difusión de una red IPv4: 192.168.1.37/24 → 192.168.1.255. */
    fun broadcastOf(ip: ByteArray, prefixLength: Int): InetAddress? {
        if (ip.size != 4) return null
        val prefix = prefixLength.coerceIn(8, 30)
        var v = 0
        for (b in ip) v = (v shl 8) or (b.toInt() and 0xFF)
        val bcast = v or ((1 shl (32 - prefix)) - 1)
        return InetAddress.getByAddress(ByteArray(4) { (bcast ushr (24 - it * 8)).toByte() })
    }

    fun formatMac(mac: ByteArray) = mac.joinToString(":") { "%02X".format(it) }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
    }

    /** Envía el paquete varias veces (UDP puede perder alguno). */
    fun wake(gate: LanGate, mac: ByteArray, tvHost: String, broadcast: InetAddress?) {
        val packet = magicPacket(mac)
        val tv = gate.policy.parseIpv4(tvHost)
        repeat(3) {
            broadcast?.let { runCatching { gate.sendUdp(it, LanPolicy.PORT_WOL, packet, broadcast = true) } }
            tv?.let { runCatching { gate.sendUdp(it, LanPolicy.PORT_WOL, packet, broadcast = false) } }
            Thread.sleep(100)
        }
    }
}
