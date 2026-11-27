pluginManagement {
    repositories {
        google()
        // Google's GCS mirror of Maven Central avoids Maven Central's shared-IP throttling on
        // AWS-hosted GitHub Actions runners; gradlePluginPortal() and mavenCentral() remain as
        // fallbacks (Spotless is only on the plugin portal; brand-new releases sync shortly).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Runway"

include(":app")

include(":baselineprofile")

include(":fixture")
