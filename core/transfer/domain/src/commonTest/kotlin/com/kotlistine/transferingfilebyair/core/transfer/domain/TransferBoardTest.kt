package com.kotlistine.transferingfilebyair.core.transfer.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import kotlin.test.Test

class TransferBoardTest {

    private var now = 0L
    private val board = TransferBoard(nowMillis = { now })
    private val video = TransferKey(TransferDirection.SENDING, "4")
    private val photo = TransferKey(TransferDirection.RECEIVING, "4")

    private fun add(key: TransferKey = video, size: Long = 1_000_000, onCancel: () -> Unit = {}) =
        board.add(key, name = "Walrus.mp4", mimeType = "video/mp4", sizeBytes = size, onCancel = onCancel)

    private fun transfer(key: TransferKey = video) = board.transfers.value.first { it.key == key }

    @Test
    fun `a new transfer is queued with nothing moved`() {
        add()

        assertThat(transfer().status).isEqualTo(TransferStatus.QUEUED)
        assertThat(transfer().bytesDone).isEqualTo(0L)
    }

    @Test
    fun `the same item id sending and receiving are two transfers`() {
        add(video)
        add(photo)

        assertThat(board.transfers.value.map { it.key }).containsExactly(video, photo)
    }

    @Test
    fun `progress moves it to active and measures its speed`() {
        val progress = add()

        progress.progressed(0)
        now = 1_000
        progress.progressed(400_000)

        assertThat(transfer().status).isEqualTo(TransferStatus.ACTIVE)
        assertThat(transfer().bytesDone).isEqualTo(400_000L)
        assertThat(transfer().bytesPerSecond).isEqualTo(400_000L)
        assertThat(transfer().fraction).isEqualTo(0.4f)
        // 600 kB left at 400 kB/s.
        assertThat(transfer().secondsLeft).isEqualTo(2L)
    }

    @Test
    fun `readings close together only update the board when the percent changes`() {
        val progress = add(size = 1_000_000)
        progress.progressed(0)
        now = 1_000
        progress.progressed(100_000)
        val before = board.transfers.value

        now = 1_010
        progress.progressed(100_500)

        assertThat(board.transfers.value).isEqualTo(before)

        now = 1_020
        progress.progressed(110_000)

        assertThat(transfer().bytesDone).isEqualTo(110_000L)
    }

    @Test
    fun `a transfer waiting its turn shows as queued again until it moves`() {
        val progress = add()

        progress.queued()

        assertThat(transfer().status).isEqualTo(TransferStatus.QUEUED)
    }

    @Test
    fun `saving shows the whole file as here`() {
        val progress = add()
        progress.progressed(10)

        progress.saving()

        assertThat(transfer().status).isEqualTo(TransferStatus.SAVING)
        assertThat(transfer().fraction).isEqualTo(1f)
    }

    @Test
    fun `finishing records how it ended`() {
        add(video)
        add(photo)
        val third = TransferKey(TransferDirection.SENDING, "5")
        add(third)

        board.finish(video)
        board.finish(photo, DataError.Network.CANCELLED)
        board.finish(third, DataError.Network.DISCONNECTED)

        assertThat(transfer(video).status).isEqualTo(TransferStatus.DONE)
        assertThat(transfer(video).bytesDone).isEqualTo(1_000_000L)
        assertThat(transfer(photo).status).isEqualTo(TransferStatus.CANCELLED)
        assertThat(transfer(photo).error).isNull()
        assertThat(transfer(third).status).isEqualTo(TransferStatus.FAILED)
        assertThat(transfer(third).error).isEqualTo(DataError.Network.DISCONNECTED)
    }

    @Test
    fun `progress after the end changes nothing`() {
        val progress = add()
        board.finish(video, DataError.Network.CANCELLED)

        progress.progressed(500)
        progress.saving()

        assertThat(transfer().status).isEqualTo(TransferStatus.CANCELLED)
    }

    @Test
    fun `cancelling one transfer stops only that one`() {
        val stopped = mutableListOf<String>()
        add(video) { stopped += "video" }
        add(photo) { stopped += "photo" }

        board.cancel(photo)

        assertThat(stopped).containsExactly("photo")
    }

    @Test
    fun `cancelling all stops every transfer still under way`() {
        val stopped = mutableListOf<String>()
        add(video) { stopped += "video" }
        add(photo) { stopped += "photo" }
        board.finish(video)

        board.cancelAll()

        assertThat(stopped).containsExactly("photo")
    }

    @Test
    fun `clearing takes only finished transfers off the board`() {
        add(video)
        add(photo)
        board.finish(video)

        board.clearFinished()

        assertThat(board.transfers.value.map { it.key }).containsExactly(photo)
    }

    @Test
    fun `a new transfer for the same item replaces the finished one`() {
        add(video)
        board.finish(video, DataError.Network.DISCONNECTED)

        add(video)

        assertThat(board.transfers.value.map { it.status }).containsExactly(TransferStatus.QUEUED)
    }

    @Test
    fun `dismissing a running transfer leaves it on the board`() {
        add(video)

        board.dismiss(video)

        assertThat(board.transfers.value.map { it.key }).containsExactly(video)
        board.finish(video)
        board.dismiss(video)
        assertThat(board.transfers.value).isEmpty()
    }
}
