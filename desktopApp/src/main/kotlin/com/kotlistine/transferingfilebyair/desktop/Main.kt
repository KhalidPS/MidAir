package com.kotlistine.transferingfilebyair.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.di.unattendedReceiverModule
import com.kotlistine.transferingfilebyair.shared.App
import com.kotlistine.transferingfilebyair.shared.appModules
import org.koin.core.context.startKoin

fun main() {
    startKoin {
        modules(appModules + desktopModule + unattendedReceiverModule)
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "TransferingFileByAir",
            state = rememberWindowState(size = DpSize(width = 480.dp, height = 860.dp)),
        ) {
            App()
        }
    }
}
