package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import kotlin.test.Test

class NearbyErrorTest {

    @Test
    fun `every missing-permission code asks for permission`() {
        val missing = listOf(
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
        )

        missing.forEach { assertThat(nearbyError(it)).isEqualTo(DataError.Network.PERMISSION_MISSING) }
    }

    @Test
    fun `a radio problem asks to turn Bluetooth and Wi-Fi on`() {
        assertThat(nearbyError(ConnectionsStatusCodes.STATUS_RADIO_ERROR)).isEqualTo(DataError.Network.RADIO_OFF)
        assertThat(nearbyError(ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR)).isEqualTo(DataError.Network.RADIO_OFF)
    }

    @Test
    fun `a peer that is gone reads as disconnected`() {
        assertThat(nearbyError(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)).isEqualTo(DataError.Network.DISCONNECTED)
        assertThat(nearbyError(ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT))
            .isEqualTo(DataError.Network.DISCONNECTED)
    }

    @Test
    fun `anything else is an unknown failure`() {
        assertThat(nearbyError(ConnectionsStatusCodes.STATUS_ERROR)).isEqualTo(DataError.Network.UNKNOWN)
        assertThat(nearbyError(-1)).isEqualTo(DataError.Network.UNKNOWN)
    }
}
