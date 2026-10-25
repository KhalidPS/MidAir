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
    }
}
