plugins {
    // Version catalog entries; applied per-module. Root only declares them so
    // `ktlintFormat` / `detekt` at the root resolve to the subprojects.
    // The Kotlin plugins must be on the root classpath too, otherwise ktlint/detekt (loaded here)
    // can't see KotlinBasePlugin and the subproject fails with "No Kotlin Targets Declared".
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}
