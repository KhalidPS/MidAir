package com.kotlistine.midair.feature.pairing.presentation

import com.kotlistine.midair.core.domain.util.DataError

internal fun DataError.Network.toPairingMessage(): String = when (this) {
    DataError.Network.PERMISSION_MISSING ->
        "Allow nearby devices and precise location so the two phones can find each other."
    DataError.Network.RADIO_OFF -> "Turn on Bluetooth and Wi-Fi on both phones."
    DataError.Network.LOCATION_OFF ->
        "Turn on Location on both phones. Android only lets apps find nearby phones while Location is on."
    DataError.Network.DISCONNECTED -> "The other phone went out of reach."
    DataError.Network.MALFORMED_MESSAGE -> "That isn't a pairing code. Open “Pair with a QR code” on the laptop and scan that."
    DataError.Network.PAYLOAD_TOO_LARGE,
    DataError.Network.CANCELLED,
    DataError.Network.UNKNOWN,
    -> "Couldn't connect. Keep the phones close together and try again."
}
