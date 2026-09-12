plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.baselineprofile)
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
// the one before it. The count is read when a variant is packaged, not at configuration, so a
// tree without git still configures, and packages with code 1; so does a shallow clone, which
// is why the release job checks out the whole history.
val versionNameFromTag =
    providers.gradleProperty("runwayVersionName").map { it.removePrefix("v") }.orElse("0.0-dev")
val commitCount =
    providers
        .exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }
        .standardOutput
        .asText
        .map { it.trim().toIntOrNull() ?: 1 }
// The Keychain is asked only when a release task was requested: `security` costs a moment on
// every invocation otherwise, and a missing entry must fail the release build, not `installDebug`.
val wantsRelease = gradle.startParameter.taskNames.any { "Release" in it }
val keychainPassword =
    if (wantsRelease) {
        providers
            .exec {
                commandLine(
                    "security",
                    "find-generic-password",
                    "-s",
                    "runway-release-signing",
                    "-w",
                )
                isIgnoreExitValue = true
            }
            .standardOutput
            .asText
            .map { it.trim() }
    } else {
        provider { "" }
    }
val releasePassword = providers.gradleProperty("runwayStorePassword").orElse(keychainPassword)

android {
    namespace = "com.grayvines.runway"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grayvines.runway"
        minSdk = 36
        targetSdk = 37
        versionName = versionNameFromTag.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore.get().exists() && wantsRelease) {
            val password = releasePassword.get()
            check(password.isNotEmpty()) {
                "${releaseStore.get()} exists but no password was found: neither the " +
                    "runwayStorePassword property nor a Keychain entry runway-release-signing."
            }
            create("release") {
                storeFile = releaseStore.get()
                storePassword = password
                keyAlias = "runway"
                keyPassword = password
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
        // A library's release day must not break CI. Updates are a chore done on purpose. Two
        // checks say the same thing, one from the network and one from the Gradle cache.
        disable += "NewerVersionAvailable"
        disable += "GradleDependency"
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
        // Emulators Gradle creates, boots and discards itself, so the instrumented tests run on
        // the same images everywhere: `./gradlew phonesGroupDebugAndroidTest`, or one device's
        // task such as `pixel10ProApi37DebugAndroidTest`.
        managedDevices {
            localDevices {
                create("pixel9Api36") {
                    device = "Pixel 9"
                    apiLevel = 36 // our minSdk
                    systemImageSource = "google"
                }
                create("pixel10ProApi37") {
                    device = "Pixel 10 Pro"
                    apiLevel = 37 // the phone Runway is developed on
                    systemImageSource = "google"
                }
            }
            groups {
                create("phones") {
                    targetDevices.add(localDevices["pixel9Api36"])
                    targetDevices.add(localDevices["pixel10ProApi37"])
                }
            }
        }
    }
}

androidComponents {
    onVariants { variant -> variant.outputs.forEach { it.versionCode.set(commitCount) } }
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

// The instrumented test tasks only say "there were failing tests"; name them, with messages.
// One printer per test task, reading that run's own results directory, so a connected run does
// not reprint a managed device's failures from last week, nor one device the other's.
fun registerFailurePrinter(testTask: String, resultsDir: String) =
    tasks.register("print${testTask.replaceFirstChar(Char::uppercase)}Failures") {
        val results = layout.buildDirectory.dir("outputs/androidTest-results/$resultsDir")
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
                    logger.error(
                        "FAILED {}.{}\n    {}",
                        case.getAttribute("classname"),
                        case.getAttribute("name"),
                        message.lineSequence().first(),
                    )
                }
        }
    }

// Instrumented tests on a connected device uninstall the app afterwards; put the debug build
// back as home. Managed devices are thrown away, so there is nothing to put back. A device
// group's task runs each device's own task, which prints for itself.
val connectedTest = "connectedDebugAndroidTest"
val managedDeviceTests =
    android.testOptions.managedDevices.localDevices.names.associateBy { "${it}DebugAndroidTest" }

val connectedPrinter = registerFailurePrinter(connectedTest, "connected/debug")

tasks.named { it == connectedTest }.configureEach { finalizedBy(connectedPrinter, "installAsHome") }

managedDeviceTests.forEach { (testTask, device) ->
    val printer = registerFailurePrinter(testTask, "managedDevice/debug/$device")
    tasks.named { it == testTask }.configureEach { finalizedBy(printer) }
}

// The baseline profile tells ART which code to compile at install time: the startup, drawer and
// drag paths the baselineprofile module's journey walks. CI records it in a job of its own,
// `:app:generateReleaseBaselineProfile` on a managed AOSP emulator, which writes it into src for
// the release build to pick up there, so every release ships one recorded from its own code and
// nothing is checked in. -PrunwayProfile instead records and bakes in within the one build, for
// recording by hand; a release built with neither simply carries no profile.
val recordProfile = providers.gradleProperty("runwayProfile").isPresent

baselineProfile {
    automaticGenerationDuringBuild = recordProfile
    // The plugin insists on one of the two; a by-hand generation lands in src (ignored by git).
    saveInSrc = !recordProfile
}

dependencies {
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)

    // Pinned so the instrumented tests' coroutines-test matches the app's coroutines-core.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
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
    // Installed beside the launcher before the instrumented tests run.
    androidTestUtil(project(mapOf("path" to ":fixture", "configuration" to "fixtureApk")))

    constraints {
        // Lifecycle pulls in kotlinx.serialization 1.7; Room's testing artifact reads the
        // exported schema with a newer runtime, and the instrumented classpath must match the
        // app's, so the app's is raised.
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
    }
    debugImplementation(libs.compose.ui.test.manifest)
}
