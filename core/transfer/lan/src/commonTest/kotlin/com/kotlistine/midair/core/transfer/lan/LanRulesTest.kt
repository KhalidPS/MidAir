package com.kotlistine.midair.core.transfer.lan

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class LanRulesTest {

    @Test
    fun `two devices that both prove the old key link up without asking`() {
        assertThat(decideAuth(AuthMode.TRUSTED, AuthMode.TRUSTED, mayAskUsers = false)).isEqualTo(AuthOutcome.LINK)
    }

    @Test
    fun `a proof that doesn't hold ends the connection, even while pairing`() {
        assertThat(decideAuth(AuthMode.TRUSTED, theirs = null, mayAskUsers = true)).isEqualTo(AuthOutcome.REFUSE)
        assertThat(decideAuth(AuthMode.PAIR, theirs = null, mayAskUsers = true)).isEqualTo(AuthOutcome.REFUSE)
    }

    @Test
    fun `a scanned QR code on both ends pairs them without asking`() {
        assertThat(decideAuth(AuthMode.QR, AuthMode.QR, mayAskUsers = false)).isEqualTo(AuthOutcome.LINK_NEW_PAIRING)
    }

    @Test
    fun `a first meeting asks the users only while they are pairing`() {
        assertThat(decideAuth(AuthMode.PAIR, AuthMode.PAIR, mayAskUsers = true)).isEqualTo(AuthOutcome.ASK_USERS)
        assertThat(decideAuth(AuthMode.PAIR, AuthMode.PAIR, mayAskUsers = false)).isEqualTo(AuthOutcome.REFUSE)
    }

    @Test
    fun `a device that forgot the other one goes back to the code check`() {
        assertThat(decideAuth(AuthMode.TRUSTED, AuthMode.PAIR, mayAskUsers = true)).isEqualTo(AuthOutcome.ASK_USERS)
        assertThat(decideAuth(AuthMode.TRUSTED, AuthMode.PAIR, mayAskUsers = false)).isEqualTo(AuthOutcome.REFUSE)
    }

    @Test
    fun `exactly one of two devices hands out the key and makes the call`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 4)
        assertThat(handsOutKey(a, b)).isTrue()
        assertThat(handsOutKey(b, a)).isFalse()
        assertThat(dialsToReconnect(a, b)).isTrue()
        assertThat(dialsToReconnect(b, a)).isFalse()
        // Bytes compare unsigned: 0xF0 is bigger than 0x10.
        assertThat(handsOutKey(byteArrayOf(0xF0.toByte()), byteArrayOf(0x10))).isFalse()
    }

    @Test
    fun `the pairing code is six digits, padded with zeros`() {
        assertThat(pairingCode(byteArrayOf(0, 0, 0, 7))).isEqualTo("000007")
        assertThat(pairingCode(byteArrayOf(-1, -1, -1, -1))).isEqualTo("967295")
    }

    @Test
    fun `hex reads back as the bytes it came from`() {
        val bytes = byteArrayOf(0, 15, -1, 16)
        assertThat(bytes.toHex()).isEqualTo("000fff10")
        assertThat("000fff10".hexToBytesOrNull()!!.toList()).isEqualTo(bytes.toList())
        assertThat("abc".hexToBytesOrNull()).isEqualTo(null)
    }
}
