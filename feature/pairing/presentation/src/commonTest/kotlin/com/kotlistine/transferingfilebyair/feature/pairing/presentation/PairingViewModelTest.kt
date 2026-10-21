package com.kotlistine.transferingfilebyair.feature.pairing.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isZero
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val pixel = PeerDevice(id = "ep-1", name = "Khalid's Pixel")
    private lateinit var link: FakePeerLink
    private lateinit var viewModel: PairingViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        link = FakePeerLink()
        viewModel = PairingViewModel(link)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is searched for until nearby devices are allowed`() {
        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.NEEDS_PERMISSION)
        assertThat(link.searches).isZero()
    }

    @Test
    fun `allowing nearby devices starts the search`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))

        assertThat(link.searches).isEqualTo(1)
        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.SEARCHING)
    }

    @Test
    fun `declining nearby devices explains what is missing`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = false))

        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.FAILED)
        assertThat(viewModel.state.value.error).isEqualTo(DataError.Network.PERMISSION_MISSING)
        assertThat(link.searches).isZero()
    }

    @Test
    fun `a phone found nearby is named and ready to connect`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))

        link.state.value = LinkState.Found(pixel)

        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.FOUND)
        assertThat(viewModel.state.value.peerName).isEqualTo("Khalid's Pixel")
    }

    @Test
    fun `holding Connect connects to the phone found`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Found(pixel)

        viewModel.onAction(PairingAction.OnHoldComplete)

        assertThat(link.connected).containsExactly(pixel)
    }

    @Test
    fun `holding Connect with nobody found does nothing`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))

        viewModel.onAction(PairingAction.OnHoldComplete)

        assertThat(link.connected.size).isZero()
    }

    @Test
    fun `a connection that can't be made says why`() {
        link.connectResult = Result.Failure(DataError.Network.RADIO_OFF)
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Found(pixel)

        viewModel.onAction(PairingAction.OnHoldComplete)

        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.FAILED)
        assertThat(viewModel.state.value.error).isEqualTo(DataError.Network.RADIO_OFF)
    }

    @Test
    fun `the link coming up plays the pairing moment once`() = runTest {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        viewModel.events.test {
            link.state.value = LinkState.Connecting(pixel)
            link.state.value = LinkState.Connected(pixel)

            assertThat(awaitItem()).isEqualTo(PairingEvent.Paired("Khalid's Pixel"))
            assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.CONNECTED)
            expectNoEvents()
        }
    }

    @Test
    fun `opening pairing while already paired shows the phone without replaying the moment`() = runTest {
        link.state.value = LinkState.Connected(pixel)
        viewModel = PairingViewModel(link)

        viewModel.events.test {
            assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.CONNECTED)
            assertThat(viewModel.state.value.peerName).isEqualTo("Khalid's Pixel")
            expectNoEvents()
        }
    }

    @Test
    fun `disconnecting looks for phones again`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Connected(pixel)

        viewModel.onAction(PairingAction.OnDisconnectClick)

        assertThat(link.disconnects).isEqualTo(1)
        assertThat(link.searches).isEqualTo(2)
        assertThat(viewModel.state.value.peerName).isNull()
    }

    @Test
    fun `a phone asking to pair shows its name and the code to check`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))

        link.state.value = LinkState.Incoming(pixel, code = "4821")

        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.INCOMING)
        assertThat(viewModel.state.value.peerName).isEqualTo("Khalid's Pixel")
        assertThat(viewModel.state.value.code).isEqualTo("4821")
    }

    @Test
    fun `holding Connect accepts the phone that asked, and nothing else`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Incoming(pixel, code = "4821")

        viewModel.onAction(PairingAction.OnHoldComplete)

        assertThat(link.accepted).containsExactly(pixel)
        assertThat(link.connected.size).isZero()
    }

    @Test
    fun `declining turns the request down`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Incoming(pixel, code = "4821")

        viewModel.onAction(PairingAction.OnDeclineClick)

        assertThat(link.declined).containsExactly(pixel)
    }

    @Test
    fun `cancelling a request withdraws it and looks again`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Connecting(pixel, code = "4821")

        viewModel.onAction(PairingAction.OnCancelClick)

        assertThat(link.withdrawals).isEqualTo(1)
        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.SEARCHING)
    }

    @Test
    fun `a link lost from the other side starts looking again`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Connected(pixel)

        link.state.value = LinkState.Idle

        assertThat(link.searches).isEqualTo(2)
        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.SEARCHING)
    }

    @Test
    fun `trying again after a failure searches again`() {
        viewModel.onAction(PairingAction.OnPermissionsResult(granted = true))
        link.state.value = LinkState.Failed(DataError.Network.UNKNOWN)

        viewModel.onAction(PairingAction.OnRetryClick)

        assertThat(link.searches).isEqualTo(2)
        assertThat(viewModel.state.value.phase).isEqualTo(PairingPhase.SEARCHING)
    }
}

private class FakePeerLink : PeerLink {
    override val state = MutableStateFlow<LinkState>(LinkState.Idle)
    var searches = 0
    var disconnects = 0
    val connected = mutableListOf<PeerDevice>()
    val accepted = mutableListOf<PeerDevice>()
    val declined = mutableListOf<PeerDevice>()
    var connectResult: EmptyResult<DataError.Network> = Result.Success(Unit)

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = emptyFlow()
        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> =
            Result.Failure(DataError.Network.DISCONNECTED)
    }

    override suspend fun startSearching(): EmptyResult<DataError.Network> {
        searches++
        state.value = LinkState.Searching
        return Result.Success(Unit)
    }

    var withdrawals = 0

    override fun stopSearching() {
        withdrawals++
        state.value = LinkState.Idle
    }

    override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> {
        connected += peer
        return connectResult
    }

    override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> {
        accepted += peer
        return Result.Success(Unit)
    }

    override fun decline(peer: PeerDevice) {
        declined += peer
        state.value = LinkState.Searching
    }

    override fun disconnect() {
        disconnects++
        state.value = LinkState.Idle
    }
}
