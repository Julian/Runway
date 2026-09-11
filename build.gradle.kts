// AGP 9 has built-in Kotlin. This classpath entry upgrades its bundled Kotlin
// Gradle plugin; do not apply org.jetbrains.kotlin.android anywhere.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
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
    source.setFrom("app/src", "baselineprofile/src", "fixture/src")
    config.setFrom("config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = true
    parallel = true
}

// `./gradlew preflight`: what CI's checks job runs, formatted first rather than checked, for a
// pass by hand before committing. The git hooks stay fast and run none of this.
tasks.register("preflight") {
    group = "verification"
    description = "Formats, then runs detekt, lint and the unit tests, as before a commit."
    dependsOn("spotlessApply", "detekt", ":app:lintDebug", ":app:testDebugUnitTest")
}

tasks.named("detekt") { mustRunAfter("spotlessApply") }
