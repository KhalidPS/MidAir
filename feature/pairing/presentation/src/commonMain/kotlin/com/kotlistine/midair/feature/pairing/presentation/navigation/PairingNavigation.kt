package com.kotlistine.midair.feature.pairing.presentation.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.kotlistine.midair.feature.pairing.presentation.PairingRoot
import kotlinx.serialization.Serializable

/** Entry point other features (and the app graph) navigate to. */
@Serializable
data object PairingGraph

@Serializable
internal data object PairingRoute

/** [onDone]: paired, or left without pairing. */
fun NavGraphBuilder.pairingGraph(onDone: () -> Unit) {
    navigation<PairingGraph>(startDestination = PairingRoute) {
        composable<PairingRoute> { PairingRoot(onDone = onDone) }
    }
}
