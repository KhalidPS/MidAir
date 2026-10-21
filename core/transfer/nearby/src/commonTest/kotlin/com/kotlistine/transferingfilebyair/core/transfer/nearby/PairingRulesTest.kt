package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import kotlin.test.Test

class PairingRulesTest {

    private val pixel = PeerDevice("ep-pixel", "Khalid's Pixel")
    private val stranger = PeerDevice("ep-stranger", "Khalid's Pixel")

    private fun decide(
        isIncoming: Boolean,
        endpointId: String,
        requestedEndpoint: String? = null,
        connectedEndpoint: String? = null,
        state: LinkState = LinkState.Searching,
    ) = decideRequest(isIncoming, endpointId, requestedEndpoint, connectedEndpoint, state)

    @Test
    fun `even the request this phone's user started waits for the code to be confirmed`() {
        assertThat(decide(isIncoming = false, endpointId = pixel.id, requestedEndpoint = pixel.id))
            .isEqualTo(RequestDecision.ASK_USER)
        assertThat(
            decide(isIncoming = false, endpointId = pixel.id, requestedEndpoint = pixel.id, state = LinkState.Connecting(pixel)),
        ).isEqualTo(RequestDecision.ASK_USER)
    }

    @Test
    fun `when both people press Connect at once, the request from the phone being asked is the same attempt`() {
        assertThat(
            decide(isIncoming = true, endpointId = pixel.id, requestedEndpoint = pixel.id, state = LinkState.Connecting(pixel)),
        ).isEqualTo(RequestDecision.ASK_USER)
    }

    @Test
    fun `a request from someone else is put to this phone's user, never accepted on its own`() {
        assertThat(decide(isIncoming = true, endpointId = pixel.id)).isEqualTo(RequestDecision.ASK_USER)
        assertThat(decide(isIncoming = true, endpointId = pixel.id, state = LinkState.Found(pixel)))
            .isEqualTo(RequestDecision.ASK_USER)
    }

    @Test
    fun `an outgoing request this phone never made is turned down`() {
        assertThat(decide(isIncoming = false, endpointId = stranger.id, requestedEndpoint = pixel.id))
            .isEqualTo(RequestDecision.REJECT)
        assertThat(decide(isIncoming = false, endpointId = stranger.id)).isEqualTo(RequestDecision.REJECT)
    }

    @Test
    fun `requests are turned down while paired`() {
        assertThat(decide(isIncoming = true, endpointId = stranger.id, connectedEndpoint = pixel.id))
            .isEqualTo(RequestDecision.REJECT)
    }

    @Test
    fun `requests are turned down while this phone is asking someone`() {
        assertThat(decide(isIncoming = true, endpointId = stranger.id, requestedEndpoint = pixel.id))
            .isEqualTo(RequestDecision.REJECT)
    }

    @Test
    fun `requests are turned down while another one waits for this user`() {
        assertThat(decide(isIncoming = true, endpointId = stranger.id, state = LinkState.Incoming(pixel, "4821")))
            .isEqualTo(RequestDecision.REJECT)
    }

    @Test
    fun `requests are turned down after this user agreed to someone, until that settles`() {
        assertThat(decide(isIncoming = true, endpointId = stranger.id, state = LinkState.Connecting(pixel, "4821")))
            .isEqualTo(RequestDecision.REJECT)
    }

    @Test
    fun `only outcomes of the current attempt count`() {
        assertThat(isCurrentAttempt(pixel.id, requestedEndpoint = pixel.id, state = LinkState.Searching)).isTrue()
        assertThat(isCurrentAttempt(pixel.id, null, LinkState.Incoming(pixel, "4821"))).isTrue()
        assertThat(isCurrentAttempt(pixel.id, null, LinkState.Connecting(pixel, "4821"))).isTrue()
        assertThat(isCurrentAttempt(stranger.id, null, LinkState.Connecting(pixel, "4821"))).isFalse()
        assertThat(isCurrentAttempt(stranger.id, null, LinkState.Searching)).isFalse()
    }

    @Test
    fun `leaving withdraws whichever attempt is waiting`() {
        assertThat(pendingEndpoint(null, LinkState.Incoming(pixel, "4821"))).isEqualTo(pixel.id)
        assertThat(pendingEndpoint(null, LinkState.Connecting(pixel, "4821"))).isEqualTo(pixel.id)
        assertThat(pendingEndpoint(pixel.id, LinkState.Failed(com.kotlistine.transferingfilebyair.core.domain.util.DataError.Network.UNKNOWN)))
            .isEqualTo(pixel.id)
        assertThat(pendingEndpoint(null, LinkState.Searching)).isNull()
    }
}
