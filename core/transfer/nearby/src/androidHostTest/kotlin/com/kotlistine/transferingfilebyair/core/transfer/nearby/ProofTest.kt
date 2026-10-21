package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class ProofTest {

    private val key = ByteArray(KeyBytes) { it.toByte() }
    private val nonce = ByteArray(NonceBytes) { 3 }
    private val id = ByteArray(DeviceIdBytes) { 5 }

    @Test
    fun `the same key, nonce and id always prove the same`() {
        val proof = prove(key, nonce, id)

        assertThat(proof.size).isEqualTo(ProofBytes)
        assertThat(prove(key, nonce, id).contentEquals(proof)).isTrue()
    }

    @Test
    fun `another key, a fresh nonce or another phone's id give a different proof`() {
        val proof = prove(key, nonce, id)

        assertThat(prove(ByteArray(KeyBytes), nonce, id).contentEquals(proof)).isFalse()
        assertThat(prove(key, ByteArray(NonceBytes) { 4 }, id).contentEquals(proof)).isFalse()
        assertThat(prove(key, nonce, ByteArray(DeviceIdBytes) { 6 }).contentEquals(proof)).isFalse()
    }
}
