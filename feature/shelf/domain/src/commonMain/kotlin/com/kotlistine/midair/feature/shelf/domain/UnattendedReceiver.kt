package com.kotlistine.midair.feature.shelf.domain

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.core.transfer.domain.TransferBoard
import com.kotlistine.midair.core.transfer.domain.TransferDirection
import com.kotlistine.midair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.midair.core.transfer.domain.TransferKey
import com.kotlistine.midair.core.transfer.domain.keeping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Cards remembered from their start to their let-go; older ones (never let go nor withdrawn) are forgotten. */
private const val MaxCards = 8

/**
 * Takes in the files of the peer's cards let go while no shelf screen is open.
 *
 * The connection outlives the screen: after the app is swiped away, a foreground service may keep it running, and the
 * peer can still slide a card over. A shelf screen takes the peer's cards itself while it is open ([attend]); with
 * none open, this receives the file the same way, without the card's flight, so it lands on the shelf and in
 * Downloads, and the next screen shows it.
 *
 * Runs on one thread ([scope] is the app-wide main-thread scope), like the screens that call [attend].
 */
class UnattendedReceiver(
    private val peer: SlidePeer,
    private val board: TransferBoard,
    private val keepAlive: TransferKeepAlive,
    private val scope: CoroutineScope,
) {
    /** Shelf screens open now. */
    private var screens = 0

    /** The peer's cards in the air, by their item id: kept from their start, so a let-go finds what it is. */
    private val cards = LinkedHashMap<String, Card>()

    init {
        peer.transport.incoming
            .onEach(::onMessage)
            .launchIn(scope)
    }

    /** A shelf screen opened: it takes the peer's cards itself until it calls the returned release. */
    fun attend(): () -> Unit {
        screens++
        var released = false
        return {
            if (!released) {
                released = true
                screens--
            }
        }
    }

    private fun onMessage(message: SlideMessage) {
        when (message) {
            is SlideMessage.DragStart -> {
                cards.remove(message.itemId)
                cards[message.itemId] = Card(message)
                while (cards.size > MaxCards) cards.remove(cards.keys.first())
            }
            is SlideMessage.PlaybackOffer -> cards[message.itemId]?.videoOffered = true
            is SlideMessage.DragCancel -> cards.remove(message.itemId)
            is SlideMessage.DragCommit -> {
                val card = cards.remove(message.itemId) ?: return
                if (screens == 0) scope.launch { receive(card) }
            }
            else -> Unit
        }
    }

    private suspend fun receive(card: Card) {
        val start = card.start
        val delivery = when {
            // On Wi-Fi, pulled in pieces, as a screen would.
            peer.media?.isFast == true -> Delivery.PULL
            // The peer offered the card's video and waits to hear how the file goes: nothing here plays it.
            card.videoOffered -> Delivery.ASK_TO_SEND
            else -> Delivery.SENT
        }
        keepAlive.keeping("Receiving “${start.name}”") {
            val key = TransferKey(TransferDirection.RECEIVING, start.itemId)
            val progress = board.add(key, start.name, start.mimeType, start.sizeBytes) { peer.cancelReceive(start.itemId) }
            var outcome: Result<String, DataError>? = null
            try {
                outcome = peer.receive(IncomingFile(start.itemId, start.name, start.mimeType, start.sizeBytes, delivery), progress)
            } finally {
                val error = when (val ended = outcome) {
                    is Result.Success -> null
                    is Result.Failure -> ended.error
                    // Cut short (the app-wide scope ended) counts as stopped.
                    null -> DataError.Network.CANCELLED
                }
                board.finish(key, error)
            }
        }
    }

    private class Card(val start: SlideMessage.DragStart) {
        var videoOffered = false
    }
}
