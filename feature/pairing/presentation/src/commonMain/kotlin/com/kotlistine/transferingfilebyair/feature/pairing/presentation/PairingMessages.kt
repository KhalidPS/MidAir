package com.kotlistine.transferingfilebyair.feature.pairing.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError

internal fun DataError.Network.toPairingMessage(): String = when (this) {
    DataError.Network.PERMISSION_MISSING ->
        "Allow nearby devices and precise location so the two phones can find each other."
    DataError.Network.RADIO_OFF -> "Turn on Bluetooth and Wi-Fi on both phones."
    DataError.Network.LOCATION_OFF ->
        "Turn on Location on both phones. Android only lets apps find nearby phones while Location is on."
    DataError.Network.DISCONNECTED -> "The other phone went out of reach."
    DataError.Network.PAYLOAD_TOO_LARGE,
    DataError.Network.MALFORMED_MESSAGE,
    DataError.Network.UNKNOWN,
    -> "Couldn't connect. Keep the phones close together and try again."
}
