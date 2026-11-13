package com.kotlistine.transferingfilebyair.core.transfer.lan

import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

private const val OwnIdKey = "own_id"
private const val PeerIdKey = "peer_id"
private const val PeerNameKey = "peer_name"
private const val PeerKeyKey = "peer_key"
private const val GapKey = "gap_mm"
private const val OffsetKey = "offset_mm"
private const val SideKey = "peer_side"
private const val ScaleKey = "peer_mm_scale"

/** The key two paired devices agreed on, for linking up again without asking. */
internal const val TrustKeyBytes = 32

/** A device this one paired with: its install id, its name then, and the key they share. */
class LanTrustedPeer(val id: ByteArray, val name: String, val key: ByteArray)

/**
 * What the LAN link keeps across restarts, in a small private file ([file]): this install's id, the device it paired
 * with, and how the two screens sit ([LinkGeometry]: the line-up, and on a laptop which side the phone is on).
 */
class LanMemory(private val file: File) : LinkGeometry {
    private val lock = Any()
    private val properties = Properties().apply {
        runCatching { file.inputStream().use(::load) }
    }

    val ownId: ByteArray = properties.getProperty(OwnIdKey)?.hexToBytesOrNull()?.takeIf { it.size == DeviceIdBytes }
        ?: LanCrypto.random(DeviceIdBytes).also {
            properties.setProperty(OwnIdKey, it.toHex())
            save()
        }

    @Volatile
    var peer: LanTrustedPeer? = readPeer()
        private set

    private val current = MutableStateFlow(readGeometry())
    override val link: StateFlow<SlideLink> = current.asStateFlow()

    override fun update(link: SlideLink) {
        current.value = link
        synchronized(lock) {
            properties.setProperty(GapKey, link.gapMm.toString())
            properties.setProperty(OffsetKey, link.verticalOffsetMm.toString())
            link.peerSide?.let { properties.setProperty(SideKey, it.name) } ?: properties.remove(SideKey)
            properties.setProperty(ScaleKey, link.peerMmScale.toString())
            save()
        }
    }

    /**
     * Remembers [trusted]; a different device than before starts from the default line-up, keeping the placement
     * (the side and height the laptop's user set: a setting of the laptop, not a measurement of one device).
     */
    fun trust(trusted: LanTrustedPeer) {
        val sameDevice = peer?.id?.contentEquals(trusted.id) == true
        peer = trusted
        synchronized(lock) {
            properties.setProperty(PeerIdKey, trusted.id.toHex())
            properties.setProperty(PeerNameKey, trusted.name)
            properties.setProperty(PeerKeyKey, trusted.key.toHex())
            save()
        }
        if (!sameDevice) update(placementOnly())
    }

    /** Forgets the paired device. Where the phone sits is the laptop's own setting, so it stays. */
    fun forget() {
        peer = null
        synchronized(lock) {
            listOf(PeerIdKey, PeerNameKey, PeerKeyKey).forEach(properties::remove)
            save()
        }
        update(placementOnly())
    }

    /** The default line-up, with the placement kept. */
    private fun placementOnly(): SlideLink =
        SlideLink(peerSide = current.value.peerSide, verticalOffsetMm = current.value.verticalOffsetMm)

    private fun readPeer(): LanTrustedPeer? {
        val id = properties.getProperty(PeerIdKey)?.hexToBytesOrNull() ?: return null
        val name = properties.getProperty(PeerNameKey) ?: return null
        val key = properties.getProperty(PeerKeyKey)?.hexToBytesOrNull()?.takeIf { it.size == TrustKeyBytes }
            ?: return null
        return LanTrustedPeer(id, name, key)
    }

    private fun readGeometry(): SlideLink {
        val default = SlideLink()
        return SlideLink(
            gapMm = properties.getProperty(GapKey)?.toFloatOrNull() ?: default.gapMm,
            verticalOffsetMm = properties.getProperty(OffsetKey)?.toFloatOrNull() ?: default.verticalOffsetMm,
            peerSide = properties.getProperty(SideKey)?.let { side -> ExitEdge.entries.firstOrNull { it.name == side } },
            peerMmScale = properties.getProperty(ScaleKey)?.toFloatOrNull() ?: default.peerMmScale,
        )
    }

    /** Written whole to a temporary file first, so a crash mid-write never leaves half a memory. */
    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }
}
