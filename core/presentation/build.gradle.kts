plugins {
    alias(libs.plugins.air.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
