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

fun NavGraphBuilder.shelfGraph() {
    navigation<ShelfGraph>(startDestination = ShelfRoute) {
        composable<ShelfRoute> { ShelfRoot() }
    }
}
