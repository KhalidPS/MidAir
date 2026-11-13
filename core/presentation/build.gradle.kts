plugins {
    alias(libs.plugins.air.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.coil.video)
        }
        jvmMain.dependencies {
            // A video card's picture: its first frame, read by the desktop's FFmpeg.
            implementation(projects.core.media)
        }
    }
}
