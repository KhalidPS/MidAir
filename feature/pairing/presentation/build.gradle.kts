plugins {
    alias(libs.plugins.air.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.transfer.domain)
        }
        androidMain.dependencies {
            // The camera, and the QR decoder, for scanning a laptop's pairing code.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
            // The runtime permission request for finding nearby devices.
            implementation(libs.androidx.activity.compose)
        }
    }
}
