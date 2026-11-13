import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Base for every shared module: Kotlin Multiplatform with an Android target and a desktop (JVM) target.
 * Adding iOS later means adding a target here, not touching each module.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-multiplatform"))
        pluginManager.apply(libs.pluginId("android-kotlin-multiplatform-library"))

        extensions.configure<KotlinMultiplatformExtension> {
            val android = (this as ExtensionAware).extensions.getByName("android")
                as KotlinMultiplatformAndroidLibraryTarget
            android.namespace = derivedNamespace
            android.compileSdk = libs.version("compileSdk").toInt()
            android.minSdk = libs.version("minSdk").toInt()
            android.withHostTest {}

            // The desktop app (Windows/macOS/Linux) runs the same modules on the JVM.
            jvm {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(libs.version("desktopJvmTarget")))
            }

            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.lib("kotlin-test"))
                implementation(libs.lib("assertk"))
            }
        }
    }
}
