import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A feature's `presentation` module: MVI ViewModel + Root/Screen composables + the feature nav graph.
 * Wires in everything every screen needs so feature build files only list feature-specific dependencies.
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("air-kmp-compose"))
        pluginManager.apply(libs.pluginId("kotlin-serialization"))

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(project(":core:domain"))
                implementation(project(":core:presentation"))
                implementation(project(":core:designsystem"))

                implementation(libs.lib("jetbrains-lifecycle-viewmodel-compose"))
                implementation(libs.lib("jetbrains-lifecycle-runtime-compose"))
                implementation(libs.lib("jetbrains-navigation-compose"))
                implementation(libs.lib("kotlinx-coroutines-core"))
                implementation(libs.lib("kotlinx-serialization-core"))
                implementation(project.dependencies.platform(libs.lib("koin-bom")))
                implementation(libs.lib("koin-core"))
                implementation(libs.lib("koin-compose-viewmodel"))
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.lib("kotlinx-coroutines-test"))
                implementation(libs.lib("turbine"))
            }
        }
    }
}
