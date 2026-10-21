package com.kotlistine.transferingfilebyair.shared

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import coil3.compose.setSingletonImageLoaderFactory
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.presentation.image.newAirImageLoader
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.navigation.ShelfGraph
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.navigation.shelfGraph

/** Single entry point for every platform. Koin must be started (with [appModules]) before calling this. */
@Composable
fun App() {
    setSingletonImageLoaderFactory(::newAirImageLoader)
    AirTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = ShelfGraph) {
            shelfGraph()
        }
    }
}
