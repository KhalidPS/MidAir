package com.kotlistine.midair.core.transfer.nearby

import android.content.Context
import android.util.Base64
import android.util.Log
import com.kotlistine.midair.core.transfer.domain.ExitEdge
import com.kotlistine.midair.core.transfer.domain.LinkGeometry
import com.kotlistine.midair.core.transfer.domain.SlideLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val PrefsName = "peer_memory"
private const val OwnIdKey = "own_id"
private const val PeerIdKey = "peer_id"
private const val PeerNameKey = "peer_name"
private const val PeerKeyKey = "peer_key"
private const val GapKey = "gap_mm"
private const val OffsetKey = "offset_mm"
private const val SideKey = "peer_side"
private const val ScaleKey = "peer_mm_scale"

/**
 * What this phone remembers between sessions, in its private storage: its own id, the phone it paired with (and the
 * key they agreed on), and how the two screens were lined up, so the next session links up and lines up by itself.
 * Main thread only, like the Nearby callbacks that use it.
 */
internal class PeerMemory(context: Context) : LinkGeometry {

    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val ownId: ByteArray = prefs.getString(OwnIdKey, null)?.decoded()
        ?: random(DeviceIdBytes).also { prefs.edit().putString(OwnIdKey, it.encoded()).apply() }

    var peer: TrustedPeer? = readPeer()
        private set

    private val current = MutableStateFlow(if (peer != null) readGeometry() else SlideLink())
    override val link: StateFlow<SlideLink> = current.asStateFlow()

    /** Kept, and remembered with the paired phone (only while there is one). */
    override fun update(link: SlideLink) {
        current.value = link
        Log.i("AirSlide", "Lined up: gap ${link.gapMm} mm, offset ${link.verticalOffsetMm} mm, peer mm x ${link.peerMmScale}")
        if (peer == null) return
        prefs.edit()
            .putFloat(GapKey, link.gapMm)
            .putFloat(OffsetKey, link.verticalOffsetMm)
            .putString(SideKey, link.peerSide?.name)
            .putFloat(ScaleKey, link.peerMmScale)
            .apply()
    }

    /** Remembers [trusted]; a different phone than before starts from the default line-up. */
    fun trust(trusted: TrustedPeer) {
        val samePhone = peer?.id?.contentEquals(trusted.id) == true
        peer = trusted
        prefs.edit()
            .putString(PeerIdKey, trusted.id.encoded())
            .putString(PeerNameKey, trusted.name)
            .putString(PeerKeyKey, trusted.key.encoded())
            .apply()
        if (!samePhone) update(SlideLink())
    }

    fun forget() {
        peer = null
        prefs.edit().remove(PeerIdKey).remove(PeerNameKey).remove(PeerKeyKey).remove(GapKey).remove(OffsetKey)
            .remove(SideKey).remove(ScaleKey).apply()
        current.value = SlideLink()
    }

    fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun readPeer(): TrustedPeer? {
        val id = prefs.getString(PeerIdKey, null)?.decoded() ?: return null
        val name = prefs.getString(PeerNameKey, null) ?: return null
        val key = prefs.getString(PeerKeyKey, null)?.decoded() ?: return null
        return TrustedPeer(id, name, key)
    }

    private fun readGeometry(): SlideLink = SlideLink(
        gapMm = prefs.getFloat(GapKey, SlideLink.DefaultGapMm),
        verticalOffsetMm = prefs.getFloat(OffsetKey, 0f),
        peerSide = prefs.getString(SideKey, null)?.let { side -> ExitEdge.entries.firstOrNull { it.name == side } },
        peerMmScale = prefs.getFloat(ScaleKey, 1f),
    )
}

/** The proof that whoever answers holds [key]: HMAC-SHA256 over [proofInput]. */
internal fun prove(key: ByteArray, askerNonce: ByteArray, answererId: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(proofInput(askerNonce, answererId))
    }

private fun ByteArray.encoded(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.decoded(): ByteArray? = try {
    Base64.decode(this, Base64.NO_WRAP)
} catch (_: IllegalArgumentException) {
    null
}
