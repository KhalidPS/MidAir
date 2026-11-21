package com.kotlistine.midair.core.transfer.lan

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class LanInviteTest {
    private val invite = LanInvite(
        hosts = listOf("192.168.1.20", "10.0.0.5"),
        port = 58_682,
        deviceId = "00112233445566778899aabbccddeeff",
        name = "Khalid's Laptop & Co = 100%",
        token = ByteArray(InviteTokenBytes) { it.toByte() },
    )

    @Test
    fun `an invitation reads back from its text, name and all`() {
        val read = LanInvite.parse(invite.toText())
        assertThat(read).isNotNull()
        read!!
        assertThat(read.hosts).isEqualTo(invite.hosts)
        assertThat(read.port).isEqualTo(invite.port)
        assertThat(read.deviceId).isEqualTo(invite.deviceId)
        assertThat(read.name).isEqualTo(invite.name)
        assertThat(read.token.toList()).isEqualTo(invite.token.toList())
    }

    @Test
    fun `any other QR code is not an invitation`() {
        assertThat(LanInvite.parse("https://example.com")).isNull()
        assertThat(LanInvite.parse("")).isNull()
    }

    @Test
    fun `an invitation missing a part, or with a short secret, is not read`() {
        val text = invite.toText()
        assertThat(LanInvite.parse(text.replace(Regex("&t=[0-9a-f]+"), ""))).isNull()
        assertThat(LanInvite.parse(text.replace(Regex("t=[0-9a-f]+"), "t=00ff"))).isNull()
        assertThat(LanInvite.parse(text.replace("p=58682", "p=70000"))).isNull()
        assertThat(LanInvite.parse(text.replace(Regex("h=[^&]+"), "h="))).isNull()
    }
}
