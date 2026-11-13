package com.kotlistine.transferingfilebyair.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferBoard
import com.kotlistine.transferingfilebyair.core.transfer.lan.JmdnsDiscovery
import com.kotlistine.transferingfilebyair.core.transfer.lan.LanMemory
import com.kotlistine.transferingfilebyair.core.transfer.lan.LanPeerLink
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.di.unattendedReceiverModule
import com.kotlistine.transferingfilebyair.shared.App
import com.kotlistine.transferingfilebyair.shared.appModules
import org.koin.core.context.startKoin
import java.io.File

fun main() {
    val koin = startKoin {
        modules(appModules + desktopModule + unattendedReceiverModule)
    }.koin
    val link = koin.get<PeerLink>()
    val lanLink = koin.get<LanPeerLink>()
    val density = koin.get<ScreenDensity>()
    val savedSide = koin.get<LanMemory>().link.value.peerSide
    val timings = SlideTimings(File(DesktopInstance.dataFolder, "slide-timings.log")).apply {
        start(link.transport.incoming, koin.getOrNull<TransferBoard>()?.transfers)
    }
    application {
        val windowState = rememberWindowState(size = DpSize(width = 480.dp, height = 860.dp))
        var phoneSide by remember { mutableStateOf(savedSide) }
        var showsQrCode by remember { mutableStateOf(false) }

        fun placePhone(side: ExitEdge) {
            phoneSide = side
            lanLink.placePeer(side, verticalOffsetMm = PhonePlacement.phoneTopBelowWindowMm(density))
            PhonePlacement.dock(windowState, side)
        }

        Window(
            onCloseRequest = {
                // Withdraws this computer from the network before quitting, so the phone stops listing it.
                koin.get<JmdnsDiscovery>().close()
                exitApplication()
            },
            title = DesktopInstance.windowTitle,
            state = windowState,
        ) {
            MenuBar {
                Menu("Phone") {
                    RadioButtonItem("On my left", selected = phoneSide == ExitEdge.LEFT) { placePhone(ExitEdge.LEFT) }
                    RadioButtonItem("On my right", selected = phoneSide == ExitEdge.RIGHT) { placePhone(ExitEdge.RIGHT) }
                    Separator()
                    Item("Pair with a QR code…") { showsQrCode = true }
                }
            }
            if (showsQrCode) QrInviteWindow(lanLink, onClose = { showsQrCode = false })
            LaunchedEffect(Unit) {
                // Where the user put the phone last time: the window stands against that edge again.
                savedSide?.let { PhonePlacement.dock(windowState, it) }
                // While the app is open, the phone it paired with before links up again by itself when in reach.
                link.reconnect()
            }
            SlideFrameWatch(timings)
            App()
        }
    }
}
