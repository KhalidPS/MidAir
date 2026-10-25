package com.kotlistine.transferingfilebyair.feature.shelf.presentation.model

import androidx.compose.runtime.Immutable

/** The video playing in its card on the shelf. */
@Immutable
data class PlayingVideoUi(
    val itemId: String,
    /** Paused by the user, or by the system (audio focus lost, headphones unplugged). */
    val isPaused: Boolean = false,
)
