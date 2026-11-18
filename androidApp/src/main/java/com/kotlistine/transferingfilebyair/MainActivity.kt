package com.kotlistine.transferingfilebyair

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.shared.App
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val link: PeerLink by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: light system bar icons on a transparent bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { App() }
    }

    // While the app is on screen, the phone it paired with before links up again by itself when it is in reach.
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { link.reconnect() }
    }

    override fun onStop() {
        link.pauseReconnecting()
        super.onStop()
    }
}
