package com.kotlistine.midair.core.transfer.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LoopbackLinkTest {

    @Test
    fun `a message sent on one end arrives on the other`() = runTest(UnconfinedTestDispatcher()) {
        val link = LoopbackLink(backgroundScope)

        link.b.incoming.test {
            link.a.send(SlideMessage.Ping(sentAtMillis = 5))

            assertThat(awaitItem()).isEqualTo(SlideMessage.Ping(sentAtMillis = 5))
        }
    }

    @Test
    fun `both directions work`() = runTest(UnconfinedTestDispatcher()) {
        val link = LoopbackLink(backgroundScope)

        link.a.incoming.test {
            link.b.send(SlideMessage.DragCancel("3"))

            assertThat(awaitItem()).isEqualTo(SlideMessage.DragCancel("3"))
        }
    }

    @Test
    fun `messages arrive only after the latency and in the order they were sent`() = runTest(UnconfinedTestDispatcher()) {
        val link = LoopbackLink(backgroundScope, latency = 40.milliseconds)

        link.b.incoming.test {
            link.a.send(SlideMessage.Ping(sentAtMillis = 1))
            link.a.send(SlideMessage.Ping(sentAtMillis = 2))
            advanceTimeBy(39.milliseconds)
            expectNoEvents()

            advanceTimeBy(2.milliseconds)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(SlideMessage.Ping(sentAtMillis = 1))
            assertThat(awaitItem()).isEqualTo(SlideMessage.Ping(sentAtMillis = 2))
        }
    }

    @Test
    fun `a message too big for the transport is refused, not sent`() = runTest(UnconfinedTestDispatcher()) {
        val link = LoopbackLink(backgroundScope)
        val huge = SlideMessage.DragStart(
            itemId = "1",
            name = "big.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1,
            cardWidthMm = 1f,
            cardHeightMm = 1f,
            thumbnail = ByteArray(SlideMessageCodec.MaxMessageBytes),
        )

        link.b.incoming.test {
            assertThat(link.a.send(huge)).isEqualTo(Result.Failure(DataError.Network.PAYLOAD_TOO_LARGE))
            expectNoEvents()
        }
    }
}
