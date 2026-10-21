package com.kotlistine.transferingfilebyair.feature.pairing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.onFailure
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The pairing ritual: both phones search; when one person holds Connect, the link comes up and both phones light up
 * together. Searching starts only once the user has allowed finding nearby devices.
 */
class PairingViewModel(private val link: PeerLink) : ViewModel() {

    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state.asStateFlow()

    // Buffered, so announcing a pairing never holds up following the link's later changes.
    private val eventChannel = Channel<PairingEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var hasPermission = false
    private var wasConnected = link.state.value is LinkState.Connected

    init {
        link.state.onEach(::follow).launchIn(viewModelScope)
    }

    fun onAction(action: PairingAction) {
        when (action) {
            is PairingAction.OnPermissionsResult -> {
                hasPermission = action.granted
                if (action.granted) {
                    search()
                } else {
                    _state.update {
                        it.copy(phase = PairingPhase.FAILED, error = DataError.Network.PERMISSION_MISSING)
                    }
                }
            }
            PairingAction.OnHoldComplete -> connectOrAccept()
            PairingAction.OnDeclineClick -> (link.state.value as? LinkState.Incoming)?.let { link.decline(it.peer) }
            PairingAction.OnRetryClick -> search()
            PairingAction.OnDisconnectClick -> link.disconnect()
            // Withdraws the request; the link goes idle and searching starts again.
            PairingAction.OnCancelClick -> link.stopSearching()
            PairingAction.OnAllowClick, PairingAction.OnCloseClick -> Unit
        }
    }

    private fun search() {
        if (!hasPermission) return
        viewModelScope.launch { link.startSearching() }
    }

    /** Holding Connect asks the phone found to pair, or agrees to the phone that asked. */
    private fun connectOrAccept() {
        val linkState = link.state.value
        viewModelScope.launch {
            val result = when (linkState) {
                is LinkState.Found -> link.connect(linkState.peer)
                is LinkState.Incoming -> link.accept(linkState.peer)
                else -> return@launch
            }
            result.onFailure { error ->
                _state.update { it.copy(phase = PairingPhase.FAILED, error = error) }
            }
        }
    }

    private suspend fun follow(linkState: LinkState) {
        val isConnected = linkState is LinkState.Connected
        _state.update { current ->
            when (linkState) {
                LinkState.Idle -> current.copy(
                    phase = if (hasPermission) PairingPhase.SEARCHING else current.phase,
                    peerName = null,
                    code = null,
                    error = null,
                )
                LinkState.Searching ->
                    current.copy(phase = PairingPhase.SEARCHING, peerName = null, code = null, error = null)
                is LinkState.Found ->
                    current.copy(phase = PairingPhase.FOUND, peerName = linkState.peer.name, code = null, error = null)
                is LinkState.Incoming -> current.copy(
                    phase = PairingPhase.INCOMING,
                    peerName = linkState.peer.name,
                    code = linkState.code,
                    error = null,
                )
                is LinkState.Connecting -> current.copy(
                    phase = PairingPhase.CONNECTING,
                    peerName = linkState.peer.name,
                    code = linkState.code,
                    error = null,
                )
                is LinkState.Connected ->
                    current.copy(phase = PairingPhase.CONNECTED, peerName = linkState.peer.name, code = null, error = null)
                is LinkState.Failed -> current.copy(phase = PairingPhase.FAILED, code = null, error = linkState.error)
            }
        }
        // The link went quiet (the other phone left, or disconnected): look again, so the screen never claims to be
        // searching while it isn't.
        if (linkState is LinkState.Idle) search()
        if (isConnected && !wasConnected) {
            eventChannel.send(PairingEvent.Paired((linkState as LinkState.Connected).peer.name))
        }
        wasConnected = isConnected
    }

    override fun onCleared() {
        // Leaving unpaired: stop being visible. A live link stays up for the shelf to use.
        if (link.state.value !is LinkState.Connected) link.stopSearching()
    }
}
