plugins {
    alias(libs.plugins.air.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Root of the shared app: theme + navigation graph + DI modules.
// Every platform entry point (Android Activity now, desktop/iOS later) just calls App().
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.presentation)
            implementation(projects.feature.shelf.data)
            implementation(projects.feature.shelf.presentation)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.serialization.core)
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
    }
}
