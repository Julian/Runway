plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "com.grayvines.runway"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grayvines.runway"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Coexists with an installed release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        checkAllWarnings = true
        abortOnError = true
        checkDependencies = true
    }

    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

room3 { schemaDirectory("$projectDir/schemas") }

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// `./gradlew installAsHome`: install the debug build, make it the home app, go home.
val adb = androidComponents.sdkComponents.adb.map { it.asFile.absolutePath }

tasks.register<Exec>("setDefaultHome") {
    mustRunAfter("installDebug")
    commandLine(
        adb.get(),
        "shell",
        "cmd",
        "package",
        "set-home-activity",
        "com.grayvines.runway.debug/com.grayvines.runway.LauncherActivity",
    )
}

tasks.register<Exec>("goHome") {
    mustRunAfter("setDefaultHome")
    commandLine(
        adb.get(),
        "shell",
        "am",
        "start",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.HOME",
    )
}

tasks.register("installAsHome") {
    dependsOn("installDebug", "setDefaultHome", "goHome")
}

// Instrumented tests uninstall the app afterwards; put the debug build back as home.
tasks
    .matching { it.name == "connectedDebugAndroidTest" }
    .configureEach {
        finalizedBy("installAsHome")
    }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // Pinned so the instrumented tests' coroutines-test matches the app's coroutines-core.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.room3.runtime)
    implementation(libs.sqlite.framework)
    ksp(libs.room3.compiler)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.bundled.jvm)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
