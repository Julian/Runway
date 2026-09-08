plugins { alias(libs.plugins.android.application) }

// A stand-in app the instrumented tests install beside the launcher: one that can be uninstalled
// (the emulator images ship only system apps) and that takes a web search (so there is always a
// second search handler to pick). Nothing here ships.
android {
    namespace = "com.grayvines.runway.fixture"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grayvines.runway.fixture"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// The debug APK itself, for :app to install beside its instrumented tests.
configurations.consumable("fixtureApk")

// By path, built by the packaging task, rather than through the variant's APK artifact: asking
// that for its file would happen while dependencies are resolved, before the task has run.
artifacts.add("fixtureApk", layout.buildDirectory.file("outputs/apk/debug/fixture-debug.apk")) {
    builtBy("packageDebug")
}
