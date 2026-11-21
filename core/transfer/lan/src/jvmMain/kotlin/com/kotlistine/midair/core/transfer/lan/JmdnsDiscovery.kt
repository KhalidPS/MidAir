package com.kotlistine.midair.core.transfer.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** The DNS-SD type every install announces itself as. Android's NSD uses the same, without the domain. */
const val LanServiceType = "_airslide._tcp.local."

private const val IdProperty = "id"
private const val NameProperty = "name"
private const val ResolveTimeoutMillis = 1_000L

/**
 * mDNS on the desktop, through JmDNS on every network interface at once (a laptop often has several: Wi-Fi, Ethernet,
 * virtual adapters): a phone hears the laptop's announcement on the network they share, with that network's address.
 */
class JmdnsDiscovery : LanDiscovery {
    private val mdns: JmmDNS by lazy { JmmDNS.Factory.getInstance() }

    @Volatile
    private var registered: ServiceInfo? = null

    override fun advertise(deviceId: String, name: String, port: Int) {
        stopAdvertising()
        // Several laptops may share a name; the id's start keeps the service names apart.
        val info = ServiceInfo.create(
            LanServiceType,
            "$name ${deviceId.take(6)}",
            port,
            0,
            0,
            mapOf(IdProperty to deviceId, NameProperty to name),
        )
        mdns.registerService(info)
        registered = info
    }

    override fun stopAdvertising() {
        registered?.let { runCatching { mdns.unregisterService(it) } }
        registered = null
    }

    override fun browse(): Flow<List<LanService>> = callbackFlow {
        val services = ConcurrentHashMap<String, LanService>()
        fun publish() {
            trySend(services.values.distinctBy { it.deviceId })
        }
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                event.dns.requestServiceInfo(event.type, event.name, ResolveTimeoutMillis)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                services.remove(event.name)
                publish()
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val id = info.getPropertyString(IdProperty) ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.inetAddresses.firstOrNull()?.hostAddress
                    ?: return
                services[event.name] = LanService(
                    deviceId = id,
                    name = info.getPropertyString(NameProperty) ?: event.name,
                    host = host,
                    port = info.port,
                )
                publish()
            }
        }
        mdns.addServiceListener(LanServiceType, listener)
        send(emptyList())
        awaitClose { mdns.removeServiceListener(LanServiceType, listener) }
    }.flowOn(Dispatchers.IO)

    /** Withdraws this device and stops mDNS (when the app quits). */
    fun close() {
        stopAdvertising()
        runCatching { mdns.close() }
    }
}
