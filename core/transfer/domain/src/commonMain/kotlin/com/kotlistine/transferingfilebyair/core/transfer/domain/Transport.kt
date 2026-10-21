package com.kotlistine.transferingfilebyair.core.transfer.domain

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import kotlinx.coroutines.flow.Flow

/** A message link to the paired device: Nearby on Android, a LAN socket on desktop, in-memory in the simulator. */
interface Transport {

    /** Messages from the peer, in the order it sent them. Malformed messages are skipped. */
    val incoming: Flow<SlideMessage>

    suspend fun send(message: SlideMessage): EmptyResult<DataError.Network>
}
