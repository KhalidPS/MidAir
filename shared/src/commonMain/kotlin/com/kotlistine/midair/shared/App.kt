package com.kotlistine.midair.shared

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import coil3.compose.setSingletonImageLoaderFactory
import com.kotlistine.midair.core.designsystem.theme.AirTheme
import com.kotlistine.midair.core.presentation.image.newAirImageLoader
import com.kotlistine.midair.feature.pairing.presentation.navigation.PairingGraph
import com.kotlistine.midair.feature.pairing.presentation.navigation.pairingGraph
import com.kotlistine.midair.feature.shelf.presentation.navigation.ShelfGraph
import com.kotlistine.midair.feature.shelf.presentation.navigation.shelfGraph

/** Single entry point for every platform. Koin must be started (with [appModules]) before calling this. */
@Composable
fun App() {
    setSingletonImageLoaderFactory(::newAirImageLoader)
    AirTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = ShelfGraph) {
            shelfGraph(onDevicesClick = { navController.navigate(PairingGraph) })
            // Pops only pairing: a second call (or one arriving late) can never pop the shelf too.
            pairingGraph(onDone = { navController.popBackStack(PairingGraph, inclusive = true) })
        }
    }
}
