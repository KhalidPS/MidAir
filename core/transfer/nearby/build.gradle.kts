plugins {
    alias(libs.plugins.air.kmp.library)
}

// The Android PeerLink, on Google's Nearby Connections. Kept out of the shared modules: it needs Play services,
// and a desktop or iOS receiver gets its own link.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.transfer.domain)
        }
        androidMain.dependencies {
            implementation(projects.core.transfer.data)
            implementation(libs.play.services.nearby)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
    }
}
