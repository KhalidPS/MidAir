package com.kotlistine.midair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test

class ReconnectTest {

    private val idA = ByteArray(DeviceIdBytes) { 1 }
    private val idB = ByteArray(DeviceIdBytes) { (0x80 + it).toByte() }
    private val nonce = ByteArray(NonceBytes) { it.toByte() }

    private fun roundTrip(frame: LinkFrame): LinkFrame? = decodeLinkFrame(frame.encode())

    @Test
    fun `every link frame survives a round trip`() {
        assertThat(roundTrip(LinkFrame.Hello(idA, nonce))).isNotNull().isInstanceOf(LinkFrame.Hello::class).given {
            assertThat(it.deviceId.contentEquals(idA)).isTrue()
            assertThat(it.nonce.contentEquals(nonce)).isTrue()
        }
        assertThat(roundTrip(LinkFrame.Proof(ByteArray(ProofBytes) { 7 })))
            .isNotNull().isInstanceOf(LinkFrame.Proof::class)
            .prop(LinkFrame.Proof::mac).given { assertThat(it.contentEquals(ByteArray(ProofBytes) { 7 })).isTrue() }
        assertThat(roundTrip(LinkFrame.Trust(ByteArray(KeyBytes) { 9 }))).isNotNull().isInstanceOf(LinkFrame.Trust::class)
        assertThat(roundTrip(LinkFrame.Forget)).isEqualTo(LinkFrame.Forget)
    }

    @Test
    fun `a frame of the wrong length or kind is dropped`() {
        val proof = LinkFrame.Proof(ByteArray(ProofBytes)).encode()

        assertThat(decodeLinkFrame(proof.copyOf(proof.size - 1))).isNull()
        assertThat(decodeLinkFrame(proof + 0)).isNull()
        assertThat(decodeLinkFrame(byteArrayOf(0xFF.toByte(), 42))).isNull()
        assertThat(decodeLinkFrame(byteArrayOf(0xFF.toByte()))).isNull()
    }

    @Test
    fun `slide messages are not taken for link frames`() {
        // A CBOR map, as every encoded slide message starts.
        assertThat(isLinkFrame(byteArrayOf(0xA2.toByte(), 0x61, 0x74))).isFalse()
        assertThat(isLinkFrame(LinkFrame.Forget.encode())).isTrue()
    }

    @Test
    fun `exactly one of two remembered phones asks to link up, and the same one hands out the key`() {
        assertThat(asksToReconnect(idA, idB)).isTrue()
        assertThat(asksToReconnect(idB, idA)).isFalse()
        assertThat(handsOutKey(idA, idB)).isTrue()
        assertThat(handsOutKey(idB, idA)).isFalse()
        // Bytes compare unsigned: 0x80 is bigger than 0x01.
        assertThat(asksToReconnect(idB, idA)).isEqualTo(false)
    }

    @Test
    fun `a proof signs the asker's nonce with the answerer's id`() {
        assertThat(proofInput(nonce, idB).contentEquals(nonce + idB)).isTrue()
    }
}
