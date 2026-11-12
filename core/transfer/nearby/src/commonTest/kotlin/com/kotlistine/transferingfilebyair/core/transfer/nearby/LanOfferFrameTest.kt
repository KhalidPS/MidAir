package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class LanOfferFrameTest {

    private val offer = LinkFrame.LanOffer(address = "192.168.1.23", port = 40_123, token = ByteArray(LanTokenBytes) { it.toByte() })

    @Test
    fun `a Wi-Fi offer survives a round trip`() {
        assertThat(decodeLinkFrame(offer.encode())).isEqualTo(offer)
    }

    @Test
    fun `an offer of a name instead of an address is dropped`() {
        assertThat(decodeLinkFrame(LinkFrame.LanOffer("evil.example", 80, offer.token).encode())).isNull()
    }

    @Test
    fun `an offer with an impossible port or address is dropped`() {
        assertThat(decodeLinkFrame(offer.copyWith(port = 0).encode())).isNull()
        assertThat(decodeLinkFrame(offer.copyWith(port = 70_000).encode())).isNull()
        assertThat(decodeLinkFrame(offer.copyWith(address = "192.168.1.300").encode())).isNull()
        assertThat(decodeLinkFrame(offer.copyWith(address = "192.168.1").encode())).isNull()
    }

    @Test
    fun `an offer with a short token is dropped`() {
        val short = offer.encode().let { it.copyOfRange(0, 2 + Long.SIZE_BYTES + 4) }
        assertThat(decodeLinkFrame(short)).isNull()
    }

    private fun LinkFrame.LanOffer.copyWith(address: String = this.address, port: Int = this.port) =
        LinkFrame.LanOffer(address, port, token)
}
