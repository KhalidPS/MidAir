package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class FileSpansTest {

    @Test
    fun `with nothing yet, the whole file is missing, in request-sized spans`() {
        assertThat(missingSpans(size = 25, have = emptyList(), maxBytes = 10))
            .containsExactly(ByteSpan(0, 10), ByteSpan(10, 20), ByteSpan(20, 25))
    }

    @Test
    fun `the gaps before, between and after the pieces are missing`() {
        val have = listOf(ByteSpan(10, 20), ByteSpan(40, 50))

        assertThat(missingSpans(size = 60, have = have, maxBytes = 100))
            .containsExactly(ByteSpan(0, 10), ByteSpan(20, 40), ByteSpan(50, 60))
    }

    @Test
    fun `overlapping and unordered pieces count once`() {
        val have = listOf(ByteSpan(30, 60), ByteSpan(0, 20), ByteSpan(15, 35))

        assertThat(missingSpans(size = 60, have = have, maxBytes = 100)).isEmpty()
        assertThat(coveredBytes(size = 60, have = have)).isEqualTo(60L)
    }

    @Test
    fun `a last piece reaching past the end covers only up to it`() {
        val have = listOf(ByteSpan(0, 10), ByteSpan(10, 1_000))

        assertThat(coveredBytes(size = 15, have = have)).isEqualTo(15L)
        assertThat(missingSpans(size = 15, have = have, maxBytes = 10)).isEmpty()
    }

    @Test
    fun `covered bytes are counted across pieces`() {
        val have = listOf(ByteSpan(0, 10), ByteSpan(5, 12), ByteSpan(50, 60))

        assertThat(coveredBytes(size = 100, have = have)).isEqualTo(22L)
    }

    @Test
    fun `an empty file is never missing anything`() {
        assertThat(missingSpans(size = 0, have = emptyList(), maxBytes = 10)).isEmpty()
        assertThat(assemblyPlan(size = 0, have = emptyList())).isEqualTo(emptyList())
    }

    @Test
    fun `the plan copies each byte once, from the piece reaching furthest`() {
        val have = listOf(ByteSpan(0, 10), ByteSpan(5, 30), ByteSpan(8, 12), ByteSpan(30, 40))

        assertThat(assemblyPlan(size = 40, have = have)).isEqualTo(
            listOf(0 to ByteSpan(0, 10), 1 to ByteSpan(10, 30), 3 to ByteSpan(30, 40)),
        )
    }

    @Test
    fun `the plan stops at the end of the file`() {
        val have = listOf(ByteSpan(0, 1_048_576))

        assertThat(assemblyPlan(size = 700, have = have)).isEqualTo(listOf(0 to ByteSpan(0, 700)))
    }

    @Test
    fun `there is no plan while a byte is missing`() {
        val have = listOf(ByteSpan(0, 10), ByteSpan(11, 20))

        assertThat(assemblyPlan(size = 20, have = have)).isNull()
    }
}
