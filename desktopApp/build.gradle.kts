import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

// The desktop shell: starts Koin with the shared modules plus the desktop's own, and opens a window running App().
// Compiled for Java 17 by whichever JDK runs Gradle (no toolchain download), like the shared modules' jvm() target.
val desktopJvmTarget = libs.versions.desktopJvmTarget.get()
kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(desktopJvmTarget))
}
java {
    sourceCompatibility = JavaVersion.toVersion(desktopJvmTarget)
    targetCompatibility = JavaVersion.toVersion(desktopJvmTarget)
}

dependencies {
    implementation(projects.shared)
    implementation(projects.core.transfer.domain)
    implementation(projects.feature.shelf.presentation)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
}

compose.desktop {
    application {
        mainClass = "com.kotlistine.transferingfilebyair.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "TransferingFileByAir"
            packageVersion = "1.0.0"
        }
    }
}
