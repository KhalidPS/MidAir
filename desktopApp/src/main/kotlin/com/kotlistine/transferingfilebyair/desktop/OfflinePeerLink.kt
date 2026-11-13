package com.kotlistine.transferingfilebyair.desktop

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stands in for the link to the phone until the LAN link is built: never finds anyone, and every send fails as
 * disconnected. The shelf works on its own.
 */
internal class OfflinePeerLink : PeerLink, LinkGeometry {
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.Idle).asStateFlow()

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = emptyFlow()
        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> = disconnected()
    }

    private val geometry = MutableStateFlow(SlideLink())
    override val link: StateFlow<SlideLink> = geometry.asStateFlow()
    override fun update(link: SlideLink) {
        geometry.value = link
    }

    override suspend fun startSearching(): EmptyResult<DataError.Network> = disconnected()
    override fun stopSearching() = Unit
    override suspend fun reconnect() = Unit
    override fun pauseReconnecting() = Unit
    override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> = disconnected()
    override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> = disconnected()
    override fun decline(peer: PeerDevice) = Unit
    override fun disconnect() = Unit

    override suspend fun sendFile(
        uri: String,
        announce: suspend (transferId: Long) -> Unit,
        onProgress: (bytesSent: Long) -> Unit,
    ): EmptyResult<DataError.Network> = disconnected()

    override suspend fun receiveFile(
        transferId: Long,
        onProgress: (bytesReceived: Long) -> Unit,
    ): Result<String, DataError.Network> = disconnected()

    override fun cancelTransfer(transferId: Long) = Unit

    private fun disconnected() = Result.Failure(DataError.Network.DISCONNECTED)
}
