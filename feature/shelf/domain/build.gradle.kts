plugins {
    alias(libs.plugins.air.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            api(projects.core.transfer.domain)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
