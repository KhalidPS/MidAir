package com.kotlistine.transferingfilebyair.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.lan.JmdnsDiscovery
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.di.unattendedReceiverModule
import com.kotlistine.transferingfilebyair.shared.App
import com.kotlistine.transferingfilebyair.shared.appModules
import org.koin.core.context.startKoin

fun main() {
    val koin = startKoin {
        modules(appModules + desktopModule + unattendedReceiverModule)
    }.koin
    val link = koin.get<PeerLink>()
    application {
        Window(
            onCloseRequest = {
                // Withdraws this computer from the network before quitting, so the phone stops listing it.
                koin.get<JmdnsDiscovery>().close()
                exitApplication()
            },
            title = DesktopInstance.windowTitle,
            state = rememberWindowState(size = DpSize(width = 480.dp, height = 860.dp)),
        ) {
            // While the app is open, the phone it paired with before links up again by itself when it is in reach.
            LaunchedEffect(Unit) { link.reconnect() }
            App()
        }
    }
}
