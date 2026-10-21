import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** A shared module with Compose Multiplatform UI. */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("air-kmp-library"))
        pluginManager.apply(libs.pluginId("kotlin-compose"))

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.lib("jetbrains-compose-runtime"))
                implementation(libs.lib("jetbrains-compose-foundation"))
                implementation(libs.lib("jetbrains-compose-ui"))
                implementation(libs.lib("jetbrains-compose-material3"))
                implementation(libs.lib("jetbrains-compose-ui-tooling-preview"))
            }
        }
    }
}
