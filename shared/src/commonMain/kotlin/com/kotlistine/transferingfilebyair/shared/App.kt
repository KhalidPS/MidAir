package com.kotlistine.transferingfilebyair.shared

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.feature.shelf.ShelfRoute
import kotlinx.serialization.Serializable

@Serializable
internal data object ShelfDestination

/** Single entry point for every platform. Koin must be started (with [appModules]) before calling this. */
@Composable
fun App() {
    AirTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = ShelfDestination) {
            composable<ShelfDestination> { ShelfRoute() }
        }
    }
}
