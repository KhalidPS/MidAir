package com.kotlistine.midair.core.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.kotlistine.midair.core.domain.util.ByteStream
import com.kotlistine.midair.core.domain.util.Result
import kotlinx.coroutines.runBlocking

/**
 * Reads a [VideoSource.Remote] for the player: each range the player asks for (the header, wherever it is in the
 * file, then the frames from where it plays) is opened on the other device and streamed from there. The player calls
 * this on its loader thread, so waiting for the bytes blocks that thread only.
 */
@OptIn(UnstableApi::class)
internal class RemoteDataSource(private val source: VideoSource.Remote) : BaseDataSource(/* isNetwork = */ true) {

    private var stream: ByteStream? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var isOpen = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val position = dataSpec.position
        if (position > source.sizeBytes) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        stream = when (val opened = runBlocking { source.open(position) }) {
            is Result.Success -> opened.data
            is Result.Failure -> throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val toEnd = source.sizeBytes - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) toEnd else minOf(dataSpec.length, toEnd)
        isOpen = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = stream ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val read = try {
            stream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        // The other end stopped before the range was done: the link broke.
        if (read < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        stream?.close()
        stream = null
        if (isOpen) {
            isOpen = false
            transferEnded()
        }
    }
}
