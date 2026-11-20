package com.kotlistine.midair.core.transfer.data

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.core.transfer.domain.SimulatedLink
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.core.transfer.domain.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Two in-memory transports wired to each other: the Twin Simulator's "air" and a test double for the protocol.
 * Every message goes through the real codec and size limit, then arrives [latency] later on the other end.
 * Messages sent with the same latency arrive in the order they were sent.
 */
class LoopbackLink(
    private val scope: CoroutineScope,
    override var latency: Duration = Duration.ZERO,
) : SimulatedLink {

    private val endpointA = Endpoint()
    private val endpointB = Endpoint()

    init {
        endpointA.peer = endpointB
        endpointB.peer = endpointA
    }

    val a: Transport get() = endpointA
    val b: Transport get() = endpointB
    override val left: Transport get() = a
    override val right: Transport get() = b

    private inner class Endpoint : Transport {
        lateinit var peer: Endpoint

        // Nothing is queued for a side nobody is listening to, like a radio link.
        val inbox = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

        override val incoming: Flow<SlideMessage> = inbox.mapNotNull { bytes ->
            (SlideMessageCodec.decode(bytes) as? Result.Success)?.data
        }

        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
            val bytes = when (val encoded = SlideMessageCodec.encode(message)) {
                is Result.Failure -> return encoded
                is Result.Success -> encoded.data
            }
            val delay = latency
            scope.launch {
                if (delay > Duration.ZERO) delay(delay)
                peer.inbox.emit(bytes)
            }
            return Result.Success(Unit)
        }
    }
}
