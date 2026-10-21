package com.kotlistine.transferingfilebyair.feature.shelf.presentation.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfRoot
import kotlinx.serialization.Serializable

/** Entry point other features (and the app graph) navigate to. */
@Serializable
data object ShelfGraph

@Serializable
internal data object ShelfRoute

/** [onDevicesClick]: the user wants to pair with a device nearby. */
fun NavGraphBuilder.shelfGraph(onDevicesClick: () -> Unit) {
    navigation<ShelfGraph>(startDestination = ShelfRoute) {
        composable<ShelfRoute> { ShelfRoot(onDevicesClick = onDevicesClick) }
    }
}
