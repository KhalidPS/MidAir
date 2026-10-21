plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = libs.plugins.air.kmp.library.get().pluginId
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = libs.plugins.air.kmp.compose.get().pluginId
            implementationClass = "KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id = libs.plugins.air.kmp.feature.get().pluginId
            implementationClass = "KmpFeatureConventionPlugin"
        }
    }
}
