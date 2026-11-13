package com.kotlistine.transferingfilebyair.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.lan.LanPeerLink
import java.net.Inet4Address
import java.net.NetworkInterface

/** Names of network adapters that never lead to the phone (virtual machines, WSL, VPNs): their addresses go last. */
private val VirtualAdapter = Regex("virtual|vmware|vbox|hyper-v|vethernet|wsl|docker|vpn|tap|tun", RegexOption.IGNORE_CASE)

/**
 * The laptop's invitation, as a QR code for the phone to scan: for networks where the phone can't find the laptop by
 * itself (mDNS blocked), or simply to pair without comparing codes. Closes once the phone has paired; closing it
 * takes the invitation back.
 */
@Composable
internal fun QrInviteWindow(link: LanPeerLink, onClose: () -> Unit) {
    var matrix by remember { mutableStateOf<BitMatrix?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(link) {
        val invite = link.invite(localAddresses())
        if (invite == null) failed = true else matrix = qrMatrix(invite.toText())
    }
    LaunchedEffect(link) {
        link.state.collect { if (it is LinkState.Connected) onClose() }
    }

    DialogWindow(
        onCloseRequest = {
            link.withdrawInvite()
            onClose()
        },
        title = "Pair with a QR code",
        state = rememberDialogState(size = DpSize(380.dp, 480.dp)),
        resizable = false,
    ) {
        AirTheme {
            Column(
                modifier = Modifier.fillMaxSize().background(AirColors.Background).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                val code = matrix
                when {
                    failed -> Text("This computer isn't on a network the phone can reach.", color = AirColors.OnSurface)
                    code != null -> QrCode(code)
                }
                Text(
                    text = "On the phone, open Devices and tap “Scan code”, then point it here.",
                    color = AirColors.OnSurfaceMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Dark modules on paper: a scanner needs the contrast, whatever the app's theme. */
@Composable
private fun QrCode(matrix: BitMatrix) {
    Canvas(
        modifier = Modifier
            .size(280.dp)
            .background(AirColors.Paper, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        val module = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = AirColors.Background,
                        topLeft = Offset(x * module, y * module),
                        // A hair wider, so neighbouring modules join without seams.
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}

private fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
)

/** This computer's IPv4 addresses on its local networks: real adapters first, virtual ones last. */
private fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .sortedBy { VirtualAdapter.containsMatchIn(it.displayName.orEmpty() + " " + it.name) }
        .flatMap { adapter -> adapter.inetAddresses.toList().filterIsInstance<Inet4Address>() }
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
        .distinct()
}.getOrDefault(emptyList())
