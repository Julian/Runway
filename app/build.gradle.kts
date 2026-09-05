plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
}

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

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
