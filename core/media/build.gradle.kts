plugins {
    alias(libs.plugins.air.kmp.compose)
}

// The FFmpeg build (decoders only, LGPL) for the computer building the app: Windows, macOS or Linux, x86-64 or ARM.
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase().let { "aarch64" in it || "arm64" in it }) "arm64" else "x86_64"
    when {
        "win" in os -> "windows-$arch"
        "mac" in os -> "macosx-$arch"
        else -> "linux-$arch"
    }
}

/** [library] as `group:name:version`, plus a [classifier]: the build of its native code for one platform. */
fun coordinates(library: Provider<MinimalExternalModuleDependency>, classifier: String? = null): String =
    library.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" + classifier?.let { c -> ":$c" }.orEmpty() }

// Video playback for every feature that shows one: the engine interface and its surface in common code, the
// platform players behind them (ExoPlayer on Android, FFmpeg through JavaCV on the desktop).
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.media3.exoplayer)
        }
        jvmMain.dependencies {
            // JavaCV's FFmpeg reader only: the other libraries it ships wrappers for aren't used.
            implementation(coordinates(libs.bytedeco.javacv)) {
                listOf(
                    "opencv", "openblas", "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense",
                    "librealsense2", "videoinput", "artoolkitplus", "leptonica", "tesseract",
                ).forEach { exclude(group = "org.bytedeco", module = it) }
            }
            implementation(libs.bytedeco.ffmpeg)
            // The native code the plain artifacts (Java classes only) load, for this computer.
            runtimeOnly(coordinates(libs.bytedeco.ffmpeg, javacppPlatform))
            runtimeOnly(coordinates(libs.bytedeco.javacpp, javacppPlatform))
        }
        jvmTest.dependencies {
            // Skia's native code, which the desktop app gets from Compose Desktop: the engine makes Skia images.
            val desktop = javacppPlatform.replace("macosx", "macos").replace("x86_64", "x64")
            implementation("org.jetbrains.compose.desktop:desktop-jvm-$desktop:${libs.versions.composeMultiplatform.get()}")
        }
    }
}
