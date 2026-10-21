plugins {
    alias(libs.plugins.air.kmp.feature)
}

// Debug-only tool: two virtual devices side by side, joined by an in-memory link. The app depends on it only in
// debug builds.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.transfer.domain)
            implementation(projects.feature.shelf.domain)
            implementation(projects.feature.shelf.presentation)
        }
    }
}
