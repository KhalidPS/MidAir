package com.kotlistine.transferingfilebyair.core.transfer.lan

/** What a QR code's text starts with; the number is this format's version. */
private const val InvitePrefix = "airslide:1?"

/** The one-time secret's size: enough that it can't be guessed while the code is on screen. */
internal const val InviteTokenBytes = 16

/**
 * An invitation to pair, shown by the laptop as a QR code: where it listens ([hosts], every address it has on the
 * local network, tried in turn, and [port]), who it is ([deviceId] in hex, [name]), and a one-time secret ([token]).
 * Whoever scans it proves they know the secret, and so does the laptop: the two pair without comparing codes, as
 * seeing the laptop's screen is the check.
 */
class LanInvite(
    val hosts: List<String>,
    val port: Int,
    val deviceId: String,
    val name: String,
    val token: ByteArray,
) {
    fun toText(): String = InvitePrefix + listOf(
        "h" to hosts.joinToString(","),
        "p" to port.toString(),
        "i" to deviceId,
        "t" to token.toHex(),
        "n" to name.encodeName(),
    ).joinToString("&") { (key, value) -> "$key=$value" }

    companion object {
        /** A scanned code's text, or null when it isn't one of ours (any other QR code). */
        fun parse(text: String): LanInvite? {
            if (!text.startsWith(InvitePrefix)) return null
            val fields = text.removePrefix(InvitePrefix).split("&").mapNotNull { field ->
                val split = field.indexOf('=')
                if (split <= 0) null else field.substring(0, split) to field.substring(split + 1)
            }.toMap()
            val hosts = fields["h"]?.split(",")?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: return null
            val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
            val deviceId = fields["i"]?.takeIf { it.hexToBytesOrNull()?.size == DeviceIdBytes } ?: return null
            val token = fields["t"]?.hexToBytesOrNull()?.takeIf { it.size == InviteTokenBytes } ?: return null
            val name = fields["n"]?.decodeName() ?: return null
            return LanInvite(hosts, port, deviceId, name, token)
        }
    }
}

/** A name as UTF-8 bytes in hex: no character in it can break the code's fields apart. */
private fun String.encodeName(): String = encodeToByteArray().toHex()

private fun String.decodeName(): String? = hexToBytesOrNull()?.decodeToString()
