plugins {
    alias(libs.plugins.air.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// OpenCV's native build for the computer building the desktop app: Windows, macOS or Linux, x86-64 or ARM.
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

kotlin {
    android {
        // Real Room + SQLite runs only on Android here: run with `connectedAndroidDeviceTest` on a device/emulator.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.shelf.domain)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            // Air gestures: the front camera, and the hand-pose model that reads it.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.mediapipe.tasks.vision)
        }
        jvmMain.dependencies {
            // Air gestures on the desktop: the webcam (OpenCV's videoio) and the hand models (OpenCV's dnn).
            implementation(libs.bytedeco.opencv)
            runtimeOnly(coordinates(libs.bytedeco.opencv, javacppPlatform))
            runtimeOnly(coordinates(libs.bytedeco.openblas, javacppPlatform))
            runtimeOnly(coordinates(libs.bytedeco.javacpp, javacppPlatform))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.junit)
            implementation(libs.junit)
            implementation(libs.assertk)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}
