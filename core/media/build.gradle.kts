plugins {
    alias(libs.plugins.air.kmp.compose)
}

// Video playback for every feature that shows one: the engine interface and its surface in common code, the
// platform players behind them.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.media3.exoplayer)
        }
    }
}
