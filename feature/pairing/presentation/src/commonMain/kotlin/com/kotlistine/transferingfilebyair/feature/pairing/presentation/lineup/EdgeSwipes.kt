package com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup

import androidx.compose.ui.Modifier

/**
 * Keeps swipes that start at the screen's side edges for this element, instead of the system's back gesture: a
 * line-up swipe arrives through the edge. Android allows this for a band up to 200 dp tall on each side.
 */
internal expect fun Modifier.keepEdgeSwipes(): Modifier
