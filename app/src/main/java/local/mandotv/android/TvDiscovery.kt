package local.mandotv.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import local.mandotv.net.LanPolicy
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** Una tele encontrada en la red. */
data class DiscoveredTv(val name: String, val host: String)

/**
 * Busca teles con el servicio mDNS «_androidtvremote2._tcp» usando el NsdManager de Android
 * (el mDNS lo hace el propio sistema, sin librerías). Solo se quedan direcciones IPv4 privadas.
 */
class TvDiscovery(context: Context, private val policy: LanPolicy, private val log: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    private val _found = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val found: StateFlow<List<DiscoveredTv>> = _found.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = mutableMapOf<String, Any>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        _found.value = emptyList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _searching.value = true }
            override fun onDiscoveryStopped(serviceType: String) { _searching.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS: no se pudo empezar a buscar ($errorCode)")
                _searching.value = false
                synchronized(this@TvDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.update { list -> list.filterNot { it.name == info.serviceName } }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { log("mDNS: ${it.message}"); listener = null }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        _searching.value = false
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.values.forEach { cb ->
                runCatching { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
    }

    private fun publish(name: String, addresses: List<InetAddress>) {
        val ip = addresses.filterIsInstance<Inet4Address>().firstOrNull { policy.isLocal(it) } ?: return
        val tv = DiscoveredTv(name, ip.hostAddress ?: return)
        _found.update { list -> (list.filterNot { it.name == name } + tv).sortedBy { it.name.lowercase() } }
    }

    @Synchronized
    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) resolveModern(info) else {
            resolveQueue.addLast(info)
            pumpLegacy()
        }
    }

    @RequiresApi(34)
    private fun resolveModern(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name in infoCallbacks) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { log("mDNS: error $errorCode") }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = publish(name, serviceInfo.hostAddresses)
            override fun onServiceLost() { _found.update { l -> l.filterNot { it.name == name } } }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        infoCallbacks[name] = cb
        runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }.onFailure { infoCallbacks.remove(name) }
    }

    /** Android 10–13: solo se puede resolver un servicio a la vez. */
    @Suppress("DEPRECATION")
    @Synchronized
    private fun pumpLegacy() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                serviceInfo.host?.let { publish(serviceInfo.serviceName, listOf(it)) }
                done()
            }
            private fun done() {
                synchronized(this@TvDiscovery) { resolving = false }
                pumpLegacy()
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
