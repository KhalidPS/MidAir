plugins {
    alias(libs.plugins.air.kmp.library)
}

// The link to a device on the same local network (phone ↔ laptop): found over mDNS, two TCP connections (card
// messages, file bytes), encrypted end to end. Its sockets and crypto are Java's, so the link itself lives in
// `jvmSharedMain`, shared by the Android and desktop targets; an iOS app would get its own. The protocol's frames and
// rules are plain Kotlin in commonMain.
kotlin {
    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(projects.core.transfer.domain)
            implementation(projects.core.transfer.data)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.jmdns)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
