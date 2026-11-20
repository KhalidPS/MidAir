package com.kotlistine.midair.core.transfer.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** The DNS-SD type, as NSD writes it (JmDNS adds the `.local.` domain). */
private const val NsdServiceType = "_airslide._tcp"
private const val IdAttribute = "id"
private const val NameAttribute = "name"
private const val Tag = "AirLan"

/**
 * mDNS on Android, through the system's Network Service Discovery: no multicast lock or extra permission needed, and
 * the system answers for the app. Runs only while the link wants to be found (pairing, or the app open with a paired
 * laptop), never in the background.
 */
internal class NsdDiscovery(context: Context) : LanDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)

    @Volatile
    private var registration: NsdManager.RegistrationListener? = null

    override fun advertise(deviceId: String, name: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "$name ${deviceId.take(6)}"
            serviceType = NsdServiceType
            setPort(port)
            setAttribute(IdAttribute, deviceId)
            setAttribute(NameAttribute, name)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(Tag, "Announced as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(Tag, "Announcing failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    override fun browse(): Flow<List<LanService>> = callbackFlow {
        val services = ConcurrentHashMap<String, LanService>()
        // Android before 14 resolves one service at a time (a second resolve fails as "already active").
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        fun publish() {
            trySend(services.values.distinctBy { it.deviceId })
        }
        launch {
            for (found in toResolve) {
                val resolved = resolve(found) ?: continue
                val id = resolved.attributes[IdAttribute]?.decodeToString() ?: continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.takeIf { it is Inet4Address }?.hostAddress ?: resolved.host?.hostAddress ?: continue
                services[found.serviceName] = LanService(
                    deviceId = id,
                    name = resolved.attributes[NameAttribute]?.decodeToString() ?: found.serviceName,
                    host = host,
                    port = resolved.port,
                )
                publish()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                services.remove(info.serviceName)
                publish()
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(Tag, "Looking for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                Log.w(Tag, "Looking failed: $code")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, code: Int) = Unit
        }
        trySend(emptyList())
        nsd.discoverServices(NsdServiceType, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(resolved)
            }

            override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                Log.w(Tag, "Couldn't resolve ${failed.serviceName}: $code")
                if (continuation.isActive) continuation.resume(null)
            }
        }
        nsd.resolveService(info, listener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            continuation.invokeOnCancellation { runCatching { nsd.stopServiceResolution(listener) } }
        }
    }
}
