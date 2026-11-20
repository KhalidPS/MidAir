package com.kotlistine.midair.core.media

import androidx.media3.common.PlaybackException
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.kotlistine.midair.core.domain.util.DataError
import kotlin.test.Test

class PlaybackErrorTest {

    @Test
    fun `a missing file is reported as not found`() {
        assertThat(playbackErrorToDataError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
            .isEqualTo(DataError.Local.FILE_NOT_FOUND)
    }

    @Test
    fun `a file the app may no longer read is reported as permission denied`() {
        assertThat(playbackErrorToDataError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
            .isEqualTo(DataError.Local.PERMISSION_DENIED)
    }

    @Test
    fun `a container or codec the phone can't handle is reported as an unsupported format`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        codes.forEach { code ->
            assertThat(playbackErrorToDataError(code)).isEqualTo(DataError.Local.UNSUPPORTED_FORMAT)
        }
    }

    @Test
    fun `any other failure is reported as unknown`() {
        assertThat(playbackErrorToDataError(PlaybackException.ERROR_CODE_UNSPECIFIED))
            .isEqualTo(DataError.Local.UNKNOWN)
    }
}
