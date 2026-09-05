// AGP 9 has built-in Kotlin. This classpath entry upgrades its bundled Kotlin
// Gradle plugin; do not apply org.jetbrains.kotlin.android anywhere.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}

detekt {
    source.setFrom("app/src")
    config.setFrom("config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = true
    parallel = true
}
