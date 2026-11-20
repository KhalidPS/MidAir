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
    implementation(projects.core.transfer.lan)
    implementation(projects.core.designsystem)
    implementation(libs.jetbrains.compose.material3)
    implementation(projects.feature.shelf.presentation)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    // Draws the pairing QR code.
    implementation(libs.zxing.core)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
}

// `./gradlew :desktopApp:run -PairInstance=2` starts a second copy with its own data, to pair two windows.
tasks.withType<JavaExec>().configureEach {
    providers.gradleProperty("airInstance").orNull?.let { environment("AIR_INSTANCE", it) }
}

compose.desktop {
    application {
        mainClass = "com.kotlistine.transferingfilebyair.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "MidAir"
            packageVersion = "1.0.0"
        }
    }
}
