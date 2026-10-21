package com.kotlistine.transferingfilebyair.core.transfer.data

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor

/**
 * Turns messages into compact CBOR and back, for any byte-based transport (Nearby BYTES payloads, sockets, loopback).
 */
@OptIn(ExperimentalSerializationApi::class)
object SlideMessageCodec {

    /** Nearby Connections' limit for one BYTES payload; every transport keeps to it so they stay interchangeable. */
    const val MaxMessageBytes = 32 * 1024

    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(message: SlideMessage): Result<ByteArray, DataError.Network> {
        val bytes = cbor.encodeToByteArray(SlideMessageDto.serializer(), message.toDto())
        return if (bytes.size > MaxMessageBytes) Result.Failure(DataError.Network.PAYLOAD_TOO_LARGE) else Result.Success(bytes)
    }

    /** Bytes come from another device: anything that doesn't parse, or carries impossible values, is malformed. */
    fun decode(bytes: ByteArray): Result<SlideMessage, DataError.Network> =
        try {
            val message = cbor.decodeFromByteArray(SlideMessageDto.serializer(), bytes).toDomain()
            if (message.isPlausible()) Result.Success(message) else Result.Failure(DataError.Network.MALFORMED_MESSAGE)
        } catch (_: SerializationException) {
            Result.Failure(DataError.Network.MALFORMED_MESSAGE)
        } catch (_: IllegalArgumentException) {
            // Truncated or garbled input can also fail below the serialization layer.
            Result.Failure(DataError.Network.MALFORMED_MESSAGE)
        }
}
