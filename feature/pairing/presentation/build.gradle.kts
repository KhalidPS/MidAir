plugins {
    alias(libs.plugins.air.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.transfer.domain)
        }
        androidMain.dependencies {
            // The runtime permission request for finding nearby devices.
            implementation(libs.androidx.activity.compose)
        }
    }
}
