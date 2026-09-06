plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// Release signing. The key is a PKCS#12 file kept with application data (Application Support on
// macOS, XDG data on Linux) and its password lives in the macOS Keychain under
// "runway-release-signing". Either can be overridden with a project property, which is how CI
// would supply them from secrets. With no key file the release build is unsigned.
val home: String = System.getProperty("user.home")
val defaultStore =
    if (System.getProperty("os.name").startsWith("Mac")) {
        "$home/Library/Application Support/Runway/release.p12"
    } else {
        "${System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"}/runway/release.p12"
    }
val releaseStore = providers.gradleProperty("runwayStoreFile").orElse(defaultStore).map(::file)

// Versioning: the name is the release tag (runwayVersionName, "v0.1.0" -> "0.1.0"), locally a
// dev marker; the code is the commit count, which only ever grows, so every build can update
// the one before it.
val versionNameFromTag =
    providers.gradleProperty("runwayVersionName").map { it.removePrefix("v") }.orElse("0.0-dev")
val commitCount =
    providers
        .exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput
        .asText
        .map { it.trim().toInt() }
val keychainPassword =
    providers
        .exec {
            commandLine("security", "find-generic-password", "-s", "runway-release-signing", "-w")
        }
        .standardOutput
        .asText
        .map { it.trim() }
val releasePassword = providers.gradleProperty("runwayStorePassword").orElse(keychainPassword)

android {
    namespace = "com.grayvines.runway"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grayvines.runway"
        minSdk = 36
        targetSdk = 37
        versionCode = commitCount.get()
        versionName = versionNameFromTag.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore.get().exists()) {
            create("release") {
                storeFile = releaseStore.get()
                storePassword = releasePassword.get()
                keyAlias = "runway"
                keyPassword = releasePassword.get()
            }
        }
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
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

room3 { schemaDirectory("$projectDir/schemas") }

// The exported schemas ride along with the instrumented tests for the migration test.
android.sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// `./gradlew installAsHome` (debug) and `installReleaseAsHome`: install, make it the home app, go
// home.
val adb = androidComponents.sdkComponents.adb.map { it.asFile.absolutePath }

fun registerInstallAsHome(name: String, installTask: String, applicationId: String) {
    val setHome =
        tasks.register<Exec>("${name}SetDefaultHome") {
            mustRunAfter(installTask)
            commandLine(
                adb.get(),
                "shell",
                "cmd",
                "package",
                "set-home-activity",
                "$applicationId/com.grayvines.runway.LauncherActivity",
            )
        }
    val goHome =
        tasks.register<Exec>("${name}GoHome") {
            mustRunAfter(setHome)
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
    tasks.register(name) { dependsOn(installTask, setHome, goHome) }
}

registerInstallAsHome("installAsHome", "installDebug", "com.grayvines.runway.debug")

registerInstallAsHome("installReleaseAsHome", "installRelease", "com.grayvines.runway")

// The connected test task only says "there were failing tests"; name them, with messages.
val printConnectedTestFailures =
    tasks.register("printConnectedTestFailures") {
        val results = layout.buildDirectory.dir("outputs/androidTest-results/connected")
        doLast {
            val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            results
                .get()
                .asFile
                .walkTopDown()
                .filter { it.extension == "xml" }
                .flatMap { file ->
                    val cases = parser.parse(file).getElementsByTagName("testcase")
                    (0 until cases.length).asSequence().map {
                        cases.item(it) as org.w3c.dom.Element
                    }
                }
                .filter { it.getElementsByTagName("failure").length > 0 }
                .forEach { case ->
                    val failure =
                        case.getElementsByTagName("failure").item(0) as org.w3c.dom.Element
                    val message = failure.getAttribute("message").ifEmpty { failure.textContent }
                    // A failed assumption is a skip, which the XML records as a failure anyway.
                    val verdict =
                        if ("AssumptionViolatedException" in message) "SKIPPED" else "FAILED"
                    logger.error(
                        "{} {}.{}\n    {}",
                        verdict,
                        case.getAttribute("classname"),
                        case.getAttribute("name"),
                        message.lineSequence().first(),
                    )
                }
        }
    }

// Instrumented tests uninstall the app afterwards; put the debug build back as home.
tasks
    .matching { it.name == "connectedDebugAndroidTest" }
    .configureEach {
        finalizedBy(printConnectedTestFailures, "installAsHome")
    }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

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
    androidTestImplementation(libs.room3.testing)

    constraints {
        // Lifecycle pulls in kotlinx.serialization 1.7; Room's testing artifact reads the
        // exported schema with a newer runtime, and the instrumented classpath must match the
        // app's, so the app's is raised.
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
    }
    debugImplementation(libs.compose.ui.test.manifest)
}
