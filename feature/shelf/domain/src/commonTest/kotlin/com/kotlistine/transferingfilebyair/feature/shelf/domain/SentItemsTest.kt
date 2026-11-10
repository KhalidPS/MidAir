package com.kotlistine.transferingfilebyair.feature.shelf.domain

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class SentItemsTest {

    private val sent = SentItems()

    @Test
    fun `an item is marked once`() {
        assertThat(sent.add("1")).isTrue()
        assertThat(sent.add("1")).isFalse()
        assertThat(sent.ids.value).containsOnly("1")
        assertThat("1" in sent).isTrue()
    }

    @Test
    fun `bringing an item back says whether it was marked`() {
        sent.add("1")

        assertThat(sent.remove("1")).isTrue()
        assertThat(sent.remove("1")).isFalse()
        assertThat("1" in sent).isFalse()
    }

    @Test
    fun `clearing brings every item back`() {
        sent.add("1")
        sent.add("2")

        sent.clear()

        assertThat(sent.ids.value).isEmpty()
    }
}
