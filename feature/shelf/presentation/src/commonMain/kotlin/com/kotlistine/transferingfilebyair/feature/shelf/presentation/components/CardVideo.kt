package com.kotlistine.transferingfilebyair.feature.shelf.presentation.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import com.kotlistine.transferingfilebyair.core.media.VideoEngine

/**
 * The video playing in one card, and the engine whose picture the card draws. Provided by the shelf screen, so the
 * card in the grid and its lifted copy both find it.
 */
@Stable
internal data class CardVideo(val engine: VideoEngine, val itemId: String, val isPaused: Boolean)

internal val LocalCardVideo = compositionLocalOf<CardVideo?> { null }
