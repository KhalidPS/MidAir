package com.kotlistine.transferingfilebyair

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kotlistine.transferingfilebyair.feature.simulator.TwinSimulatorApp
import com.kotlistine.transferingfilebyair.feature.simulator.di.simulatorModule
import org.koin.core.context.loadKoinModules

/** Debug-only: two virtual devices side by side, to tune the slide across the gap without two phones. */
class SimulatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!simulatorModuleLoaded) {
            loadKoinModules(simulatorModule)
            simulatorModuleLoaded = true
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { TwinSimulatorApp() }
    }

    private companion object {
        // Koin outlives the activity; load the module once per process.
        var simulatorModuleLoaded = false
    }
}
