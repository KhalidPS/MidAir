plugins {
    alias(libs.plugins.air.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Root of the shared app: theme + navigation graph + DI modules.
// Every platform entry point (androidApp's Activity, desktopApp's window, iOS later) just calls App().
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.presentation)
            implementation(projects.core.media)
            implementation(projects.core.transfer.data)
            implementation(projects.feature.shelf.data)
            implementation(projects.feature.shelf.presentation)
            implementation(projects.feature.pairing.presentation)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.serialization.core)
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
    }
}
