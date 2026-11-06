plugins {
    alias(libs.plugins.air.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.shelf.domain)
            implementation(projects.core.transfer.domain)
            implementation(projects.core.media)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            // The runtime camera permission request for air gestures.
            implementation(libs.androidx.activity.compose)
        }
    }
}
