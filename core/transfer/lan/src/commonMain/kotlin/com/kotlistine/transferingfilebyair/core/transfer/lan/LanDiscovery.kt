package com.kotlistine.transferingfilebyair.core.transfer.lan

import kotlinx.coroutines.flow.Flow

/** A device running the app on the same local network, as it announced itself. */
data class LanService(
    /** The install's id, in hex. */
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Announcing this device on the local network and finding the others (mDNS / DNS-SD: JmDNS on the desktop, NSD on
 * Android). It only finds; the link checks who a device really is when it connects.
 */
interface LanDiscovery {
    /** Announces this device ([deviceId] in hex, [name]) as listening on [port], until [stopAdvertising]. */
    fun advertise(deviceId: String, name: String, port: Int)

    fun stopAdvertising()

    /** The devices announcing themselves now (this one left out by the caller), while collected. */
    fun browse(): Flow<List<LanService>>
}
