package local.mandotv.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.mandotv.net.NetworkBinder
import local.mandotv.remote.WakeOnLan
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/**
 * Vigila la red WiFi (o Ethernet) y ata a ella todos los sockets de la app, de modo que el
 * tráfico nunca pueda salir por los datos móviles ni por una VPN.
 * No exige que la WiFi tenga Internet: funciona aunque el router esté desconectado.
 */
class WifiMonitor(context: Context) : NetworkBinder {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private data class Lan(val network: Network, val link: LinkProperties?)

    @Volatile private var current: Lan? = null
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = Lan(network, cm.getLinkProperties(network))
            _available.value = true
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (current?.network == network || current == null) {
                current = Lan(network, linkProperties)
                _available.value = true
            }
        }

        override fun onLost(network: Network) {
            if (current?.network == network) {
                current = null
                _available.value = false
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun network(): Network = current?.network ?: throw IOException("No hay conexión WiFi")

    override fun bind(socket: Socket) = network().bindSocket(socket)
    override fun bind(socket: DatagramSocket) = network().bindSocket(socket)

    /** Dirección de difusión de la WiFi (p. ej. 192.168.1.255) para Wake-on-LAN. */
    fun broadcastAddress(): InetAddress? {
        val link = current?.link ?: return null
        val la = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return WakeOnLan.broadcastOf(la.address.address, la.prefixLength)
    }
}
