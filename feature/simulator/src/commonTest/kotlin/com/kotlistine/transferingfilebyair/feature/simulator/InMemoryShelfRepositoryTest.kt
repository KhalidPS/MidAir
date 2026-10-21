package com.kotlistine.transferingfilebyair.feature.simulator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class InMemoryShelfRepositoryTest {

    private val storage = SimulatedStorage()

    private fun shelf(prefix: String, importFile: suspend (String) -> Result<com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem, DataError.Local> = {
        Result.Success(file("imported", uri = it))
    }) = InMemoryShelfRepository(prefix, storage, nowEpochMillis = { 99 }, importFile = importFile)

    @Test
    fun `stocking keeps the order and gives each item an id of this twin`() = runTest {
        val left = shelf("L")

        left.reset(listOf(file("a"), file("b")))

        assertThat(left.observeItems().first().map { it.id }).containsExactly("L1", "L2")
        assertThat(left.observeItems().first().map { it.uri }).containsExactly("content://file/a", "content://file/b")
    }

    @Test
    fun `an added file goes on top as the newest item`() = runTest {
        val left = shelf("L")
        left.reset(listOf(file("a")))

        left.addFile("content://new")

        val items = left.observeItems().first()
        assertThat(items.map { it.uri }).containsExactly("content://new", "content://file/a")
        assertThat(items.first().addedAtEpochMillis).isEqualTo(99)
    }

    @Test
    fun `adding a file that is already there changes nothing`() = runTest {
        val left = shelf("L")
        left.reset(listOf(file("a")))

        left.addFile("content://file/a")

        assertThat(left.observeItems().first().map { it.id }).containsExactly("L1")
    }

    @Test
    fun `a file that can't be read is not added`() = runTest {
        val left = shelf("L", importFile = { Result.Failure(DataError.Local.PERMISSION_DENIED) })

        assertThat(left.addFile("content://locked")).isEqualTo(Result.Failure(DataError.Local.PERMISSION_DENIED))
        assertThat(left.observeItems().first()).isEmpty()
    }

    @Test
    fun `removing an item that isn't there is reported`() = runTest {
        val left = shelf("L")

        assertThat(left.removeItem("L9")).isEqualTo(Result.Failure(DataError.Local.FILE_NOT_FOUND))
    }

    @Test
    fun `a twin receives a copy of what the other twin sent`() = runTest {
        val left = shelf("L")
        val right = shelf("R")
        left.reset(listOf(file("a", name = "River Valley.jpg")))
        val peer = TwinPeer(FakeLink().right, ExitEdge.LEFT, gapMm = { 8f }, storage, right)

        val received = peer.receive(IncomingFile("L1", "River Valley.jpg", "image/jpeg", 1_000))

        assertThat(received).isEqualTo(Result.Success("R1"))
        val landed = right.observeItems().first().single()
        assertThat(landed.name).isEqualTo("River Valley.jpg")
        assertThat(storage["R1"]).isNotNull()
    }

    @Test
    fun `receiving something no twin sent fails`() = runTest {
        val right = shelf("R")
        val peer = TwinPeer(FakeLink().right, ExitEdge.LEFT, gapMm = { 8f }, storage, right)

        assertThat(peer.receive(IncomingFile("L404", "gone.jpg", "image/jpeg", 1)))
            .isEqualTo(Result.Failure(DataError.Local.FILE_NOT_FOUND))
    }

    @Test
    fun `a twin's link faces the other twin across the current gap`() {
        var gap = 8f
        val peer = TwinPeer(FakeLink().left, ExitEdge.RIGHT, gapMm = { gap }, storage, shelf("L"))

        gap = 15f

        assertThat(peer.link.gapMm).isEqualTo(15f)
        assertThat(peer.link.peerSide).isEqualTo(ExitEdge.RIGHT)
    }
}
