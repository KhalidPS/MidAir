package com.kotlistine.midair.feature.pairing.presentation.lineup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.midair.core.transfer.domain.ClockSample
import com.kotlistine.midair.core.transfer.domain.EdgeCrossing
import com.kotlistine.midair.core.transfer.domain.LinkGeometry
import com.kotlistine.midair.core.transfer.domain.LinkState
import com.kotlistine.midair.core.transfer.domain.PeerLink
import com.kotlistine.midair.core.transfer.domain.ScreenDensity
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.core.transfer.domain.lineUp
import com.kotlistine.midair.core.transfer.domain.peerClockAheadMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock

/** Round trips measured to compare the two clocks; the quickest one counts. */
private const val ClockProbes = 10
private const val ClockProbeSpacingMillis = 150L

/** How long one half of a swipe waits for the other phone's half before it counts as missed. */
internal const val MissAfterMillis = 1_500L

/** Two halves further apart than this (once the clocks are compared) are different swipes. */
private const val SameSwipeMillis = 2_000L

/**
 * Lining the paired screens up: one finger swipes from one phone straight onto the other. Each phone notes where and
 * when the swipe crossed its edge and tells the other; with both halves (and the two clocks compared), each works out
 * which of its edges faces the other phone, the gap, and how far up or down the other screen sits, and slides follow
 * that from then on. Either phone can start the swipe, and a new swipe measures again.
 */
class LineUpViewModel(
    private val link: PeerLink,
    private val geometry: LinkGeometry,
    private val density: ScreenDensity,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LineUpState(peerName = (link.state.value as? LinkState.Connected)?.peer?.name),
    )
    val state: StateFlow<LineUpState> = _state.asStateFlow()

    private val eventChannel = Channel<LineUpEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val clockSamples = mutableListOf<ClockSample>()
    private var mine: EdgeCrossing? = null
    private var theirs: EdgeCrossing? = null
    private var missJob: Job? = null

    init {
        link.transport.incoming.onEach(::onPeerMessage).launchIn(viewModelScope)
        // The other phone's shelf answers with its clock's time (see ShelfViewModel).
        viewModelScope.launch {
            repeat(ClockProbes) {
                link.transport.send(SlideMessage.Ping(now()))
                delay(ClockProbeSpacingMillis)
            }
        }
    }

    fun onAction(action: LineUpAction) {
        when (action) {
            is LineUpAction.OnEdgeCrossed -> onCrossed(action.crossing)
            LineUpAction.OnDoneClick, LineUpAction.OnSkipClick -> Unit
        }
    }

    private fun onCrossed(px: SwipeCrossingPx) {
        val crossing = EdgeCrossing(
            leaving = px.leaving,
            edge = px.edge,
            alongEdgeMm = density.yPxToMm(px.alongEdgePx),
            // The swipe's last touch was just now.
            atMillis = now() + px.millisFromLast,
            acrossMmPerS = density.xPxToMm(px.acrossPxPerS),
            alongEdgeMmPerS = density.yPxToMm(px.alongEdgePxPerS),
        )
        mine = crossing
        // The other phone's half may already be here; a half from an earlier swipe doesn't count.
        theirs = theirs?.takeIf { isSameSwipe(crossing, it) }
        viewModelScope.launch { link.transport.send(SlideMessage.SwipeCrossed(crossing)) }
        settle()
    }

    private fun onPeerMessage(message: SlideMessage) {
        when (message) {
            is SlideMessage.Pong -> if (message.answeredAtMillis != 0L) {
                clockSamples += ClockSample(message.pingSentAtMillis, message.answeredAtMillis, now())
            }
            is SlideMessage.SwipeCrossed -> {
                theirs = message.crossing
                mine = mine?.takeIf { isSameSwipe(it, message.crossing) }
                settle()
            }
            else -> Unit
        }
    }

    /** With both halves of a swipe, line up (or call it missed); with one, wait a little for the other. */
    private fun settle() {
        val mine = mine
        val theirs = theirs
        missJob?.cancel()
        if (mine == null || theirs == null) {
            _state.update { it.copy(phase = LineUpPhase.MEASURING) }
            missJob = viewModelScope.launch {
                delay(MissAfterMillis)
                forgetSwipe()
                _state.update { it.copy(phase = LineUpPhase.MISSED) }
            }
            return
        }
        forgetSwipe()
        val lined = lineUp(mine, theirs, peerClockAheadMillis(clockSamples))
        if (lined == null) {
            _state.update { it.copy(phase = LineUpPhase.MISSED) }
            return
        }
        geometry.update(lined)
        _state.update {
            it.copy(
                phase = LineUpPhase.LINED_UP,
                peerSide = lined.peerSide,
                gapMm = lined.gapMm,
                // The offset turns the peer's heights into this screen's: a positive one means its top sits lower.
                peerLowerByMm = lined.verticalOffsetMm,
            )
        }
        viewModelScope.launch { eventChannel.send(LineUpEvent.LinedUp) }
    }

    private fun forgetSwipe() {
        mine = null
        theirs = null
    }

    private fun isSameSwipe(mine: EdgeCrossing, theirs: EdgeCrossing): Boolean {
        // Without a clock comparison yet, the two clocks may be far apart: any pair counts.
        val peerAhead = peerClockAheadMillis(clockSamples) ?: return true
        return abs(mine.atMillis - (theirs.atMillis - peerAhead)) <= SameSwipeMillis
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()
}
